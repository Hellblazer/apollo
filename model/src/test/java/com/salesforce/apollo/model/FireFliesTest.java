/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.salesforce.apollo.model;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import com.salesforce.apollo.choam.Parameters;
import com.salesforce.apollo.choam.Parameters.Builder;
import com.salesforce.apollo.choam.Parameters.ProducerParameters;
import com.salesforce.apollo.choam.Parameters.RuntimeParameters;
import com.salesforce.apollo.comm.LocalRouter;
import com.salesforce.apollo.comm.ServerConnectionCache;
import com.salesforce.apollo.crypto.Digest;
import com.salesforce.apollo.crypto.DigestAlgorithm;
import com.salesforce.apollo.fireflies.View;
import com.salesforce.apollo.fireflies.View.Participant;
import com.salesforce.apollo.membership.Context;
import com.salesforce.apollo.membership.ContextImpl;
import com.salesforce.apollo.membership.Member;
import com.salesforce.apollo.stereotomy.ControlledIdentifier;
import com.salesforce.apollo.stereotomy.StereotomyImpl;
import com.salesforce.apollo.stereotomy.identifier.SelfAddressingIdentifier;
import com.salesforce.apollo.stereotomy.mem.MemKERL;
import com.salesforce.apollo.stereotomy.mem.MemKeyStore;
import com.salesforce.apollo.stereotomy.services.EventValidation;
import com.salesforce.apollo.utils.Utils;

/**
 * @author hal.hildebrand
 *
 */
public class FireFliesTest {
    private static final int    CARDINALITY     = 5;
    private static final Digest GENESIS_VIEW_ID = DigestAlgorithm.DEFAULT.digest("Give me food or give me slack or kill me".getBytes());

    private final List<ProcessDomain>             domains = new ArrayList<>();
    private final Map<ProcessDomain, LocalRouter> routers = new HashMap<>();
    private final Map<ProcessDomain, View>        views   = new HashMap<>();

    @AfterEach
    public void after() {
        domains.forEach(n -> n.stop());
        domains.clear();
        routers.values().forEach(r -> r.close());
        routers.clear();
        views.values().forEach(v -> v.stop());
        views.clear();
    }

    @BeforeEach
    public void before() throws SQLException {
        final var prefix = UUID.randomUUID().toString();
        Path checkpointDirBase = Path.of("target", "ct-chkpoints-" + Utils.bitStreamEntropy().nextLong());
        Utils.clean(checkpointDirBase.toFile());
        var params = params();
        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(params.getDigestAlgorithm()),
                                            new SecureRandom());

        var identities = IntStream.range(0, CARDINALITY)
                                  .parallel()
                                  .mapToObj(i -> stereotomy.newIdentifier().get())
                                  .map(ci -> {
                                      @SuppressWarnings("unchecked")
                                      var casted = (ControlledIdentifier<SelfAddressingIdentifier>) ci;
                                      return casted;
                                  })
                                  .collect(Collectors.toMap(controlled -> controlled.getIdentifier().getDigest(),
                                                            controlled -> controlled));

        var scheduler = Executors.newScheduledThreadPool(CARDINALITY * 5);

        var foundations = new HashMap<Member, Context<Participant>>();

        var exec = Executors.newCachedThreadPool();
        identities.forEach((digest, id) -> {
            var context = new ContextImpl<>(DigestAlgorithm.DEFAULT.getLast(), CARDINALITY, 0.2, 3);
            var localRouter = new LocalRouter(prefix, ServerConnectionCache.newBuilder().setTarget(30),
                                              Executors.newFixedThreadPool(2));
            var foundation = Context.<Participant>newBuilder().setCardinality(CARDINALITY).build();
            var node = new ProcessDomain(id, params, "jdbc:h2:mem:", checkpointDirBase,
                                         RuntimeParameters.newBuilder()
                                                          .setScheduler(scheduler)
                                                          .setContext(context)
                                                          .setExec(exec)
                                                          .setCommunications(localRouter));
            domains.add(node);
            foundations.put(node.getMember(), foundation);
            routers.put(node, localRouter);
            localRouter.setMember(node.getMember());
            localRouter.start();
        });
        domains.forEach(m -> {
            views.put(m, new View(foundations.get(m.getMember()), m.getMember(), new InetSocketAddress(0),
                                  EventValidation.NONE, routers.get(m), 0.0125, DigestAlgorithm.DEFAULT, null));
        });
    }

//    @Test
    public void smokin() throws Exception {
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        domains.forEach(n -> n.start());
        views.values()
             .forEach(v -> v.start(Duration.ofMillis(10),
                                   domains.stream()
                                          .map(n -> View.identityFor(0, new InetSocketAddress(0),
                                                                     n.getMember().getEvent()))
                                          .toList(),
                                   scheduler));
        Thread.sleep(10_000);
    }

    private Builder params() {
        var params = Parameters.newBuilder()
                               .setSynchronizationCycles(1)
                               .setSynchronizeTimeout(Duration.ofSeconds(1))
                               .setGenesisViewId(GENESIS_VIEW_ID)
                               .setGossipDuration(Duration.ofMillis(50))
                               .setProducer(ProducerParameters.newBuilder()
                                                              .setGossipDuration(Duration.ofMillis(50))
                                                              .setBatchInterval(Duration.ofMillis(100))
                                                              .setMaxBatchByteSize(1024 * 1024)
                                                              .setMaxBatchCount(3000)
                                                              .build())
                               .setCheckpointBlockSize(200);

        params.getProducer().ethereal().setNumberOfEpochs(4);
        return params;
    }
}