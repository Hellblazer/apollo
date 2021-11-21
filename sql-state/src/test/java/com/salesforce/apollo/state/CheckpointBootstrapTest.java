/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.salesforce.apollo.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.codahale.metrics.Counter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.salesforce.apollo.membership.Member;
import com.salesforce.apollo.membership.SigningMember;
import com.salesforce.apollo.utils.Utils;

/**
 * @author hal.hildebrand
 *
 */
public class CheckpointBootstrapTest extends AbstractLifecycleTest {

    @Test
    public void checkpointBootstrap() throws Exception {
        final SigningMember testSubject = members.get(CARDINALITY - 1);
        final Duration timeout = Duration.ofSeconds(3);
        AtomicBoolean proceed = new AtomicBoolean(true);
        MetricRegistry reg = new MetricRegistry();
        Timer latency = reg.timer("Transaction latency");
        Counter timeouts = reg.counter("Transaction timeouts");
        AtomicInteger lineTotal = new AtomicInteger();
        var transactioneers = new ArrayList<Transactioneer>();
        final int waitFor = 23;
        final int clientCount = 10;
        final int max = 15;
        final CountDownLatch countdown = new CountDownLatch((choams.size() - 1) * clientCount);

        routers.entrySet().stream().filter(e -> !e.getKey().equals(testSubject.getId())).map(e -> e.getValue())
               .forEach(r -> r.start());
        choams.entrySet().stream().filter(e -> !e.getKey().equals(testSubject.getId())).map(e -> e.getValue())
              .forEach(ch -> ch.start());
        Thread.sleep(1000);

        var success = Utils.waitForCondition(30_000, 100,
                                             () -> members.stream().map(m -> updaters.get(m))
                                                          .map(ssm -> ssm.getCurrentBlock()).filter(cb -> cb != null)
                                                          .mapToLong(cb -> cb.height()).filter(l -> l >= waitFor)
                                                          .count() > toleranceLevel);
        assertTrue(success, "States: " + choams.values().stream().map(e -> e.getCurrentState()).toList());

        final var initial = choams.get(members.get(0).getId()).getSession()
                                  .submit(ForkJoinPool.commonPool(), initialInsert(), timeout, txScheduler);
        initial.get(timeout.toMillis(), TimeUnit.MILLISECONDS);

        for (int i = 0; i < clientCount; i++) {
            choams.entrySet().stream().filter(e -> !e.getKey().equals(testSubject.getId())).map(e -> e.getValue())
                  .map(c -> new Transactioneer(c, timeout, timeouts, latency, proceed, lineTotal, max, countdown,
                                               txScheduler))
                  .forEach(e -> transactioneers.add(e));
        }
        System.out.println("# of clients: " + (choams.size() - 1) * clientCount);
        System.out.println("Starting txns");
        transactioneers.stream().forEach(e -> e.start());
        checkpointOccurred.whenComplete((s, t) -> {
            System.out.println("Starting late joining node");
            choams.get(testSubject.getId()).start();
            routers.get(testSubject.getId()).start();
        });

        try {
            countdown.await(120, TimeUnit.SECONDS);
            assertTrue(checkpointOccurred.get(60, TimeUnit.SECONDS));
        } finally {
            proceed.set(false);
        }

        final long target = members.stream().map(m -> updaters.get(m)).map(ssm -> ssm.getCurrentBlock())
                                   .filter(cb -> cb != null).mapToLong(cb -> cb.height()).max().getAsLong()
        + 30;

        success = Utils.waitForCondition(60_000, 100,
                                         () -> members.stream().map(m -> updaters.get(m))
                                                      .map(ssm -> ssm.getCurrentBlock()).filter(cb -> cb != null)
                                                      .mapToLong(cb -> cb.height()).filter(l -> l >= target)
                                                      .count() == members.size());
        assertTrue(success, "Results: " + choams.values().stream().map(e -> e.getCurrentState()).toList());

        System.out.println();
        System.out.println();
        System.out.println();

        record row(float price, int quantity) {}

        System.out.println("Checking replica consistency");

        Map<Member, Map<Integer, row>> manifested = new HashMap<>();

        for (Member m : members) {
            Connection connection = updaters.get(m).newConnection();
            Statement statement = connection.createStatement();
            ResultSet results = statement.executeQuery("select ID, PRICE, QTY from books");
            while (results.next()) {
                manifested.computeIfAbsent(m, k -> new HashMap<>())
                          .put(results.getInt("ID"), new row(results.getFloat("PRICE"), results.getInt("QTY")));
            }
            connection.close();
        }

        Map<Integer, row> standard = manifested.get(members.get(0));
        for (Member m : members) {
            var candidate = manifested.get(m);
            for (var entry : standard.entrySet()) {
                assertTrue(candidate.containsKey(entry.getKey()));
                assertEquals(entry.getValue(), candidate.get(entry.getKey()));
            }
        }
    }
}