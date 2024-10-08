/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.salesforce.apollo.state;

import com.salesforce.apollo.choam.proto.Transaction;
import com.salesforce.apollo.cryptography.Digest;
import com.salesforce.apollo.cryptography.DigestAlgorithm;
import com.salesforce.apollo.state.proto.Txn;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * @author hal.hildebrand
 */
public class ScriptTest {

    @Test
    public void services() throws Exception {
        SqlStateMachine machine = new SqlStateMachine("jdbc:h2:mem:test_script_service", new Properties(),
                                                      new File("target/chkpoints"));
        SqlStateMachine.CallService service = mock(SqlStateMachine.CallService.class);
        machine.register("foo", service);
        machine.getExecutor().genesis(DigestAlgorithm.DEFAULT.getLast(), Collections.emptyList());
        Connection connection = machine.newConnection();
        createAndInsert(connection);
        connection.commit();
        Txn txn = Txn.newBuilder()
                     .setScript(new Mutator(null, machine.getSession()).callScript("test.DbAccess", "callWitServices",
                                                                                   new BufferedReader(
                                                                                   new InputStreamReader(
                                                                                   getClass().getResourceAsStream(
                                                                                   "/scripts/dbaccess.java"),
                                                                                   StandardCharsets.UTF_8)).lines()
                                                                                                           .collect(
                                                                                                           Collectors.joining(
                                                                                                           "\n"))))
                     .build();
        CompletableFuture<Object> completion = new CompletableFuture<>();
        machine.getExecutor()
               .execute(0, Digest.NONE, Transaction.newBuilder().setContent(txn.toByteString()).build(), completion);

        assertTrue(ResultSet.class.isAssignableFrom(completion.get().getClass()));
        ResultSet rs = (ResultSet) completion.get();
        assertTrue(rs.next());
        assertTrue(rs.next());
        assertTrue(rs.next());
        assertTrue(rs.next());
        assertTrue(rs.next());
        assertFalse(rs.next());
        verify(service).call("hello world");
    }

    @Test
    public void smoke() throws Exception {
        SqlStateMachine machine = new SqlStateMachine("jdbc:h2:mem:test_script", new Properties(),
                                                      new File("target/chkpoints"));
        machine.getExecutor().genesis(DigestAlgorithm.DEFAULT.getLast(), Collections.emptyList());
        Connection connection = machine.newConnection();
        createAndInsert(connection);
        connection.commit();
        Txn txn = Txn.newBuilder()
                     .setScript(new Mutator(null, machine.getSession()).callScript("test.DbAccess", "call",
                                                                                   new BufferedReader(
                                                                                   new InputStreamReader(
                                                                                   getClass().getResourceAsStream(
                                                                                   "/scripts/dbaccess.java"),
                                                                                   StandardCharsets.UTF_8)).lines()
                                                                                                           .collect(
                                                                                                           Collectors.joining(
                                                                                                           "\n"))))
                     .build();
        CompletableFuture<Object> completion = new CompletableFuture<>();
        machine.getExecutor()
               .execute(0, Digest.NONE, Transaction.newBuilder().setContent(txn.toByteString()).build(), completion);

        assertTrue(ResultSet.class.isAssignableFrom(completion.get().getClass()));
        ResultSet rs = (ResultSet) completion.get();
        assertTrue(rs.next());
        assertTrue(rs.next());
        assertTrue(rs.next());
        assertTrue(rs.next());
        assertTrue(rs.next());
        assertFalse(rs.next());
    }

    private Statement createAndInsert(Connection connection) throws SQLException {
        connection.setAutoCommit(false);
        Statement s = connection.createStatement();

        s.execute("create schema s");
        s.execute("create table s.books (id int, title varchar(50), author varchar(50), price float, qty int)");

        s.execute("insert into s.books values (1001, 'Java for dummies', 'Tan Ah Teck', 11.11, 11)");
        s.execute("insert into s.books values (1002, 'More Java for dummies', 'Tan Ah Teck', 22.22, 22)");
        s.execute("insert into s.books values (1003, 'More Java for more dummies', 'Mohammad Ali', 33.33, 33)");
        s.execute("insert into s.books values (1004, 'A Cup of Java', 'Kumar', 44.44, 44)");
        s.execute("insert into s.books values (1005, 'A Teaspoon of Java', 'Kevin Jones', 55.55, 55)");
        return s;
    }
}
