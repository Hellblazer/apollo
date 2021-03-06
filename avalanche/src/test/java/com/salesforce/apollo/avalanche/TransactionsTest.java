/*
 * Copyright (c) 2019, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.salesforce.apollo.avalanche;

import static com.salesforce.apollo.protocols.Conversion.hashOf;
import static com.salesforce.apollo.protocols.Conversion.serialize;
import static java.util.Arrays.asList;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.stream.Collectors;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.salesforce.apollo.avalanche.WorkingSet.FinalizationData;
import com.salesforce.apollo.avalanche.WorkingSet.Node;
import com.salesforce.apollo.avro.DagEntry;
import com.salesforce.apollo.protocols.HashKey;
import com.salesforce.apollo.protocols.Utils;

/**
 * @author hal.hildebrand
 * @since 222
 */
public class TransactionsTest {

    private static File baseDir;

    @BeforeClass
    public static void beforeClass() {
        baseDir = new File(System.getProperty("user.dir"), "target/txn-tst");
        Utils.clean(baseDir);
        baseDir.mkdirs();
    }

    private WorkingSet          dag;
    private Random              entropy;
    private AvalancheParameters parameters;
    private DagEntry            root;
    private HashKey             rootKey;

    @Before
    public void before() throws Exception {
        entropy = new Random(0x1638);
        parameters = new AvalancheParameters();
        dag = new WorkingSet(parameters, new DagWood(parameters.dagWood), null);
        root = new DagEntry();
        root.setDescription(WellKnownDescriptions.GENESIS.toHash());
        root.setData(ByteBuffer.wrap("Ye root".getBytes()));
        rootKey = dag.insert(root, 0);
        assertNotNull(rootKey);
    }

    @Test
    public void consecutiveCounter() throws Exception {
        int oldBeta1 = parameters.core.beta1;
        int oldBeta2 = parameters.core.beta2;
        try {
            parameters.core.beta1 = 100;
            parameters.core.beta2 = 20;
            List<HashKey> ordered = new ArrayList<>();
            Map<HashKey, DagEntry> stored = new ConcurrentSkipListMap<>();
            stored.put(rootKey, root);
            ordered.add(rootKey);

            HashKey last = rootKey;
            HashKey firstCommit = newDagEntry("1st commit", ordered, stored, Arrays.asList(last));
            last = firstCommit;
            HashKey secondCommit = newDagEntry("2nd commit", ordered, stored, Arrays.asList(last));
            last = secondCommit;

            for (int i = 0; i < parameters.core.beta2; i++) {
                last = newDagEntry("entry: " + i, ordered, stored, Arrays.asList(last));
            }

            for (int i = ordered.size() - 1; i >= 4; i--) {
                dag.prefer(ordered.get(i));
                dag.tryFinalize(ordered.get(i));
            }

            assertEquals(parameters.core.beta2 - 1, dag.get(firstCommit).getConflictSet().getCounter());
            assertEquals(parameters.core.beta2 - 1, dag.get(secondCommit).getConflictSet().getCounter());

            assertFalse(dag.isFinalized(rootKey));
            assertFalse(dag.isFinalized(firstCommit));
            assertFalse(dag.isFinalized(secondCommit));

//            DagViz.dumpClosure(ordered, dag);
//            Graphviz.fromGraph(DagViz.visualize("smoke", dag, false)).render(Format.PNG).toFile(new File("smoke.png"));

            dag.prefer(ordered.get(ordered.size() - 1));
            dag.tryFinalize(ordered.get(ordered.size() - 1));
            assertTrue(dag.isFinalized(rootKey));
            assertTrue(dag.isFinalized(firstCommit));
            assertTrue(dag.isFinalized(secondCommit));
        } finally {
            parameters.core.beta1 = oldBeta1;
            parameters.core.beta2 = oldBeta2;
        }
    }

    // test early commit logic
    @Test
    public void earlyCommit() throws Exception {
        List<HashKey> ordered = new ArrayList<>();
        Map<HashKey, DagEntry> stored = new ConcurrentSkipListMap<>();
        stored.put(rootKey, root);
        ordered.add(rootKey);

        HashKey last = rootKey;
        HashKey firstCommit = newDagEntry("1st commit", ordered, stored, Arrays.asList(last));
        last = firstCommit;
        HashKey secondCommit = newDagEntry("2nd commit", ordered, stored, Arrays.asList(last));
        last = secondCommit;

        for (int i = 0; i < parameters.core.beta1 - 2; i++) {
            last = newDagEntry("entry: " + i, ordered, stored, Arrays.asList(last));
        }

        for (int i = ordered.size() - 1; i >= 2; i--) {
            dag.prefer(ordered.get(i));
            dag.tryFinalize(ordered.get(i));

            assertFalse(dag.isFinalized(firstCommit));
            assertFalse(dag.isFinalized(secondCommit));
        }

        assertEquals(parameters.core.beta1 - 1, dag.get(firstCommit).getConflictSet().getCounter());
        assertEquals(parameters.core.beta1 - 1, dag.get(secondCommit).getConflictSet().getCounter());

        dag.prefer(ordered.get(3));
        dag.tryFinalize(ordered.get(3));

        assertTrue(dag.isFinalized(rootKey));
        assertTrue(dag.isFinalized(firstCommit));
        assertTrue(dag.isFinalized(secondCommit));
    }

    @Test
    public void finalizedSet() {
        List<HashKey> ordered = new ArrayList<>();
        Map<HashKey, DagEntry> stored = new ConcurrentSkipListMap<>();
        stored.put(rootKey, root);
        ordered.add(rootKey);

        HashKey last = rootKey;

        for (int i = 0; i < parameters.core.beta1; i++) {
            last = newDagEntry("entry: " + i, ordered, stored, Arrays.asList(last));
        }
        FinalizationData finalized;

        for (int i = ordered.size() - 1; i > ordered.size() - parameters.core.beta1; i--) {
            HashKey key = ordered.get(i);
            dag.prefer(key);
            finalized = dag.tryFinalize(key);
            assertEquals(0, finalized.finalized.size());
            assertEquals(0, finalized.deleted.size());
        }

        HashKey lastKey = ordered.get(ordered.size() - 1);
        dag.prefer(lastKey);

        finalized = dag.tryFinalize(lastKey);
        assertNotNull(finalized);
        assertEquals(3, finalized.finalized.size());
        assertEquals(0, finalized.deleted.size());
        for (HashKey key : finalized.finalized) {
            assertTrue("not finalized: " + key, dag.isFinalized(key));
        }
    }

    @Test
    public void frontier() throws Exception {
        List<HashKey> ordered = new ArrayList<>();
        Map<HashKey, DagEntry> stored = new ConcurrentSkipListMap<>();
        stored.put(rootKey, root);
        ordered.add(rootKey);

        HashKey last = rootKey;
        HashKey firstCommit = newDagEntry("1st commit", ordered, stored, Arrays.asList(rootKey));
        ordered.add(new HashKey(firstCommit.bytes()));
        last = firstCommit;

        HashKey secondCommit = newDagEntry("2nd commit", ordered, stored, Arrays.asList(rootKey));
        ordered.add(new HashKey(secondCommit.bytes()));
        last = secondCommit;

        TreeSet<HashKey> frontier = dag.frontier(entropy).stream().collect(Collectors.toCollection(TreeSet::new));

        assertEquals(3, frontier.size());

        assertTrue(frontier.contains(secondCommit));

        HashKey userTxn = newDagEntry("Ye test transaction", ordered, stored, dag.sampleParents(entropy));
        ordered.add(new HashKey(userTxn.bytes()));

        frontier = dag.frontier(entropy).stream().collect(Collectors.toCollection(TreeSet::new));

        assertEquals(4, frontier.size());

        assertTrue(frontier.contains(secondCommit) || frontier.contains(firstCommit));
        assertTrue(frontier.contains(userTxn));

        last = userTxn;
        last = newDagEntry("entry: " + 0, ordered, stored, Arrays.asList(last));

        frontier = dag.frontier(entropy).stream().collect(Collectors.toCollection(TreeSet::new));

        assertEquals(5, frontier.size());

        assertTrue(frontier.contains(secondCommit) || frontier.contains(firstCommit));
        assertTrue(frontier.contains(last));
    }

    @Test
    public void isStronglyPreferred() throws Exception {
        List<HashKey> ordered = new ArrayList<>();
        Map<HashKey, DagEntry> stored = new ConcurrentSkipListMap<>();
        stored.put(rootKey, root);
        ordered.add(rootKey);

        DagEntry entry = new DagEntry();
        entry.setDescription(WellKnownDescriptions.BYTE_CONTENT.toHash());
        entry.setData(ByteBuffer.wrap(String.format("DagEntry: %s", 1).getBytes()));
        entry.setLinks(asList(rootKey.toHash()));
        HashKey key = dag.insert(entry, 0);
        stored.put(key, entry);
        ordered.add(key);

        entry = new DagEntry();
        entry.setDescription(WellKnownDescriptions.BYTE_CONTENT.toHash());
        entry.setData(ByteBuffer.wrap(String.format("DagEntry: %s", 2).getBytes()));
        entry.setLinks(asList(key.toHash()));
        key = dag.insert(entry, 0);
        stored.put(key, entry);
        ordered.add(key);

        entry = new DagEntry();
        entry.setDescription(WellKnownDescriptions.BYTE_CONTENT.toHash());
        entry.setData(ByteBuffer.wrap(String.format("DagEntry: %s", 3).getBytes()));
        entry.setLinks(asList(ordered.get(1).toHash(), ordered.get(2).toHash()));
        key = dag.insert(entry, 0);
        stored.put(key, entry);
        ordered.add(key);

        HashKey zero = new HashKey(new byte[32]);
        assertNull("Not exist returned true: ", dag.isStronglyPreferred(zero));

        byte[] o = new byte[32];
        Arrays.fill(o, (byte) 1);
        HashKey one = new HashKey(o);
        assertNull("Not exist returned true: ", dag.isStronglyPreferred(one));
        assertArrayEquals("Aggregate failed: ", new Boolean[] { null, null },
                          dag.isStronglyPreferred(Arrays.asList(zero, one)).toArray(new Boolean[2]));

        // All are strongly preferred
        for (int i = 0; i < ordered.size(); i++) {
            HashKey test = ordered.get(i);
            assertTrue(String.format("node %s is not strongly preferred", i), dag.isStronglyPreferred(test));
        }

        entry = new DagEntry();
        entry.setDescription(WellKnownDescriptions.BYTE_CONTENT.toHash());
        entry.setData(ByteBuffer.wrap(String.format("DagEntry: %s", 4).getBytes()));
        entry.setLinks(asList(ordered.get(1).toHash(), ordered.get(2).toHash()));
        key = dag.insert(entry, ordered.get(3), 0);
        stored.put(key, entry);
        ordered.add(key);

        assertTrue(String.format("node 3 is not strongly preferred: " + ordered.get(4)),
                   dag.isStronglyPreferred(ordered.get(3)));

        assertFalse(String.format("node 4 is strongly preferred: " + ordered.get(4)),
                    dag.isStronglyPreferred(ordered.get(4)));

        for (int i = 0; i < 4; i++) {
            int it = i;
            assertTrue(String.format("node %s is not strongly preferred", i), dag.isStronglyPreferred(ordered.get(it)));
        }

        entry = new DagEntry();
        entry.setData(ByteBuffer.wrap(String.format("DagEntry: %s", 5).getBytes()));
        entry.setLinks(asList(ordered.get(1).toHash(), ordered.get(4).toHash()));
        key = dag.insert(entry, 0);
        stored.put(key, entry);
        ordered.add(key);

        // check transitivity of isStronglyPreferred()
        assertFalse(String.format("node 5 is strongly preferred"), dag.isStronglyPreferred(ordered.get(5)));

        Boolean[] expected = new Boolean[] { true, true, true, true, false, false };
        List<HashKey> all = ordered.stream().map(e -> e).collect(Collectors.toList());
        assertArrayEquals("Aggregate failed: ", expected,
                          dag.isStronglyPreferred(all).toArray(new Boolean[ordered.size()]));
        dag.finalize(rootKey);
        assertTrue(dag.isFinalized(rootKey));
        assertTrue(dag.isStronglyPreferred(rootKey));
        assertArrayEquals("Aggregate failed: ", expected,
                          dag.isStronglyPreferred(all).toArray(new Boolean[ordered.size()]));
    }

    @Test
    public void knownUnknowns() throws Exception {
        int oldBeta1 = parameters.core.beta1;
        int oldBeta2 = parameters.core.beta2;
        try {
            parameters.core.beta1 = 11;
            parameters.core.beta2 = 150;
            List<HashKey> ordered = new ArrayList<>();
            Map<HashKey, DagEntry> stored = new ConcurrentSkipListMap<>();
            stored.put(rootKey, root);
            ordered.add(rootKey);

            HashKey last = rootKey;
            HashKey firstCommit = newDagEntry("1st commit", ordered, stored, Arrays.asList(last), false);
            last = firstCommit;
            HashKey secondCommit = newDagEntry("2nd commit", ordered, stored, Arrays.asList(last), false);
            last = secondCommit;

            for (int i = 0; i < parameters.core.beta2; i++) {
                last = newDagEntry("entry: " + i, ordered, stored, Arrays.asList(last));
            }

            for (int i = 2; i < ordered.size() - 1; i++) {
                final Node node = dag.get(ordered.get(i));
                assertNotNull("Node " + i + " is not present!", node);
                assertEquals("Node " + i + " has no dependents", 1, node.dependents().size());
            }

            for (int i = 3; i < ordered.size() - 1; i++) {
                dag.prefer(ordered.get(i));
                dag.tryFinalize(ordered.get(i));
            }

            for (int i = 0; i < ordered.size(); i++) {
                assertFalse(dag.isFinalized(ordered.get(i)));
            }

            dag.prefer(ordered.get(ordered.size() - 1));
            dag.tryFinalize(ordered.get(ordered.size() - 1));

            for (int i = 3; i < 143; i++) {
                assertFalse("node " + i + " is not finalized", dag.isFinalized(ordered.get(i)));
            }
        } finally {
            parameters.core.beta1 = oldBeta1;
            parameters.core.beta2 = oldBeta2;
        }
    }

    @Test
    public void multipleParents() {
        List<HashKey> ordered = new ArrayList<>();
        Map<HashKey, DagEntry> stored = new ConcurrentSkipListMap<>();
        stored.put(rootKey, root);
        ordered.add(rootKey);

        HashKey last = rootKey;
        HashKey firstCommit = newDagEntry("1st commit", ordered, stored, Arrays.asList(last));
        last = firstCommit;
        HashKey secondCommit = newDagEntry("2nd commit", ordered, stored, Arrays.asList(last));
        last = secondCommit;

        HashKey userTxn = newDagEntry("Ye test transaction", ordered, stored, dag.sampleParents(entropy));

        last = userTxn;

        for (int i = 0; i < parameters.core.beta2; i++) {
            last = newDagEntry("entry: " + i, ordered, stored, Arrays.asList(last));
        }

        for (int i = ordered.size() - 1; i >= 4; i--) {
            dag.prefer(ordered.get(i));
        }

        dag.prefer(userTxn);
        dag.tryFinalize(userTxn);
        assertTrue(dag.isFinalized(userTxn));
    }

    @Test
    public void parentSelection() throws Exception {
        List<HashKey> ordered = new ArrayList<>();
        Map<HashKey, DagEntry> stored = new ConcurrentSkipListMap<>();
        stored.put(rootKey, root);
        ordered.add(rootKey);

        // Finalize ye root
        dag.finalize(rootKey);
        assertTrue(dag.isFinalized(rootKey));

        // 1 elegible parent, the root
        Set<HashKey> sampled = dag.sampleParents(entropy).stream().collect(Collectors.toCollection(TreeSet::new));
        assertEquals(1, sampled.size());
        assertTrue(sampled.contains(ordered.get(0)));

        DagEntry entry = new DagEntry();
        entry.setDescription(WellKnownDescriptions.BYTE_CONTENT.toHash());
        entry.setData(ByteBuffer.wrap(String.format("DagEntry: %s", 1).getBytes()));
        entry.setLinks(asList(rootKey.toHash()));
        HashKey key = dag.insert(entry, 0);
        stored.put(key, entry);
        ordered.add(key);

        sampled = dag.sampleParents(entropy).stream().collect(Collectors.toCollection(TreeSet::new));
        assertEquals(1, sampled.size());
        assertTrue(sampled.contains(ordered.get(1)));

        entry = new DagEntry();
        entry.setDescription(WellKnownDescriptions.BYTE_CONTENT.toHash());
        entry.setData(ByteBuffer.wrap(String.format("DagEntry: %s", 2).getBytes()));
        entry.setLinks(asList(key.toHash()));
        key = dag.insert(entry, 0);
        stored.put(key, entry);
        ordered.add(key);

        sampled = dag.sampleParents(entropy).stream().collect(Collectors.toCollection(TreeSet::new));
        assertEquals(2, sampled.size());
        assertTrue(sampled.contains(ordered.get(1)));
        assertTrue(sampled.contains(ordered.get(2)));

        entry = new DagEntry();
        entry.setDescription(WellKnownDescriptions.BYTE_CONTENT.toHash());
        entry.setData(ByteBuffer.wrap(String.format("DagEntry: %s", 3).getBytes()));
        entry.setLinks(asList(ordered.get(1).toHash(), ordered.get(2).toHash()));
        key = dag.insert(entry, 0);
        stored.put(key, entry);
        ordered.add(key);

        entry = new DagEntry();
        entry.setDescription(WellKnownDescriptions.BYTE_CONTENT.toHash());
        entry.setData(ByteBuffer.wrap(String.format("DagEntry: %s", 4).getBytes()));
        entry.setLinks(asList(ordered.get(1).toHash(), ordered.get(2).toHash()));
        key = dag.insert(entry, ordered.get(3), 0);
        stored.put(key, entry);
        ordered.add(key);

        entry = new DagEntry();
        entry.setDescription(WellKnownDescriptions.BYTE_CONTENT.toHash());
        entry.setData(ByteBuffer.wrap(String.format("DagEntry: %s", 5).getBytes()));
        entry.setLinks(asList(ordered.get(1).toHash(), ordered.get(3).toHash()));
        key = dag.insert(entry, 0);
        stored.put(key, entry);
        ordered.add(key);

        sampled = dag.sampleParents(entropy).stream().collect(Collectors.toCollection(TreeSet::new));
        assertEquals(3, sampled.size());

        assertTrue(sampled.contains(ordered.get(1)));
        assertTrue(sampled.contains(ordered.get(2)));
        assertTrue(sampled.contains(ordered.get(5)));

        // Add a new node to the frontier
        entry = new DagEntry();
        entry.setDescription(WellKnownDescriptions.BYTE_CONTENT.toHash());
        entry.setData(ByteBuffer.wrap(String.format("DagEntry: %s", 6).getBytes()));
        entry.setLinks(asList(ordered.get(1).toHash(), ordered.get(5).toHash()));
        key = dag.insert(entry, 0);
        stored.put(key, entry);
        ordered.add(key);

        sampled = dag.sampleParents(entropy).stream().collect(Collectors.toCollection(TreeSet::new));

        assertEquals(4, sampled.size());

        assertTrue(sampled.contains(ordered.get(1)));
        assertTrue(sampled.contains(ordered.get(2)));
        assertTrue(sampled.contains(ordered.get(5)));
        assertTrue(sampled.contains(ordered.get(6)));
    }

    @Test
    public void parentSelectionWithPreferred() throws Exception {
        List<HashKey> ordered = new ArrayList<>();
        Map<HashKey, DagEntry> stored = new ConcurrentSkipListMap<>();
        stored.put(rootKey, root);
        ordered.add(rootKey);

        DagEntry entry = new DagEntry();
        entry.setDescription(WellKnownDescriptions.BYTE_CONTENT.toHash());
        entry.setData(ByteBuffer.wrap(String.format("DagEntry: %s", 1).getBytes()));
        entry.setLinks(asList(rootKey.toHash()));
        HashKey key = dag.insert(entry, 0);
        stored.put(key, entry);
        ordered.add(key);

        entry = new DagEntry();
        entry.setDescription(WellKnownDescriptions.BYTE_CONTENT.toHash());
        entry.setData(ByteBuffer.wrap(String.format("DagEntry: %s", 2).getBytes()));
        entry.setLinks(asList(key.toHash()));
        key = dag.insert(entry, 0);
        stored.put(key, entry);
        ordered.add(key);

        entry = new DagEntry();
        entry.setDescription(WellKnownDescriptions.BYTE_CONTENT.toHash());
        entry.setData(ByteBuffer.wrap(String.format("DagEntry: %s", 3).getBytes()));
        entry.setLinks(asList(ordered.get(1).toHash(), ordered.get(2).toHash()));
        key = dag.insert(entry, 0);
        stored.put(key, entry);
        ordered.add(key);

        entry = new DagEntry();
        entry.setDescription(WellKnownDescriptions.BYTE_CONTENT.toHash());
        entry.setData(ByteBuffer.wrap(String.format("DagEntry: %s", 4).getBytes()));
        entry.setLinks(asList(ordered.get(1).toHash(), ordered.get(2).toHash()));
        key = dag.insert(entry, ordered.get(3), 0);
        stored.put(key, entry);
        ordered.add(key);

        entry = new DagEntry();
        entry.setDescription(WellKnownDescriptions.BYTE_CONTENT.toHash());
        entry.setData(ByteBuffer.wrap(String.format("DagEntry: %s", 5).getBytes()));
        entry.setLinks(asList(ordered.get(1).toHash(), ordered.get(3).toHash()));
        key = dag.insert(entry, 0);
        stored.put(key, entry);
        ordered.add(key);

        Set<HashKey> frontier = dag.frontier(entropy)
                                   .stream()

                                   .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(5, frontier.size());

        // Nodes 3 and 4 are in conflict and are always excluded
        assertTrue(frontier.contains(ordered.get(3)));
        assertFalse(frontier.contains(ordered.get(4)));

        // Add a new node to the frontier
        entry = new DagEntry();
        entry.setDescription(WellKnownDescriptions.BYTE_CONTENT.toHash());
        entry.setData(ByteBuffer.wrap(String.format("DagEntry: %s", 6).getBytes()));
        entry.setLinks(asList(ordered.get(1).toHash(), ordered.get(5).toHash()));
        key = dag.insert(entry, 0);
        stored.put(key, entry);
        ordered.add(key);

        frontier = dag.singularFrontier(entropy).stream().collect(Collectors.toCollection(TreeSet::new));

        assertEquals(5, frontier.size());

        // prefer node 6, raising the confidence of nodes 3, 2, 1 and 0
        dag.prefer(ordered.get(6));
        frontier = dag.frontier(entropy).stream().collect(Collectors.toCollection(TreeSet::new));

        assertEquals(6, frontier.size());

        assertTrue(frontier.contains(ordered.get(3)));
        assertFalse(frontier.contains(ordered.get(4)));
    }

    @Test
    public void prefer() throws Exception {
        List<HashKey> ordered = new ArrayList<>();
        Map<HashKey, DagEntry> stored = new TreeMap<>();
        stored.put(rootKey, root);
        ordered.add(rootKey);

        DagEntry entry = new DagEntry();
        entry.setDescription(WellKnownDescriptions.BYTE_CONTENT.toHash());
        entry.setData(ByteBuffer.wrap(String.format("DagEntry: %s", 1).getBytes()));
        entry.setLinks(asList(rootKey.toHash()));
        HashKey key = dag.insert(entry, 0);
        stored.put(key, entry);
        ordered.add(key);

        entry = new DagEntry();
        entry.setDescription(WellKnownDescriptions.BYTE_CONTENT.toHash());
        entry.setData(ByteBuffer.wrap(String.format("DagEntry: %s", 2).getBytes()));
        entry.setLinks(asList(key.toHash()));
        key = dag.insert(entry, 0);
        stored.put(key, entry);
        ordered.add(key);

        // Nodes 3, 4 conflict. 3 is the preference initially
        entry = new DagEntry();
        entry.setDescription(WellKnownDescriptions.BYTE_CONTENT.toHash());
        entry.setData(ByteBuffer.wrap(String.format("DagEntry: %s", 3).getBytes()));
        entry.setLinks(asList(ordered.get(1).toHash(), ordered.get(2).toHash()));
        key = dag.insert(entry, 0);
        stored.put(key, entry);
        ordered.add(key);

        entry = new DagEntry();
        entry.setDescription(WellKnownDescriptions.BYTE_CONTENT.toHash());
        entry.setData(ByteBuffer.wrap(String.format("DagEntry: %s", 4).getBytes()));
        entry.setLinks(asList(ordered.get(1).toHash(), ordered.get(2).toHash()));
        key = dag.insert(entry, ordered.get(3), 0);
        stored.put(key, entry);
        ordered.add(key);

        entry = new DagEntry();
        entry.setDescription(WellKnownDescriptions.BYTE_CONTENT.toHash());
        entry.setData(ByteBuffer.wrap(String.format("DagEntry: %s", 5).getBytes()));
        entry.setLinks(asList(ordered.get(1).toHash(), ordered.get(4).toHash()));
        key = dag.insert(entry, 0);
        stored.put(key, entry);
        ordered.add(key);

//		dumpClosure(ordered, create);
        for (HashKey e : ordered) {
            assertEquals(0, dag.get(e).getConfidence());
        }

        dag.prefer(ordered.get(3));

//		dumpClosure(ordered, create);
        assertFalse(String.format("node 4 is strongly preferred: ") + ordered.get(4),
                    dag.isStronglyPreferred(ordered.get(4)));

        assertTrue(dag.get(ordered.get(3)).getChit());
        assertEquals(1, dag.get(ordered.get(0)).getConfidence());
        assertEquals(1, dag.get(ordered.get(2)).getConfidence());
        assertEquals(0, dag.get(ordered.get(4)).getConfidence());

        assertTrue(String.format("node 0 is not strongly preferred"), dag.isStronglyPreferred(ordered.get(0)));
        assertTrue(String.format("node 1 is not strongly preferred"), dag.isStronglyPreferred(ordered.get(1)));
        assertTrue(String.format("node 2 is not strongly preferred"), dag.isStronglyPreferred(ordered.get(2)));
        assertTrue(String.format("node 3 is not strongly preferred"), dag.isStronglyPreferred(ordered.get(3)));
        assertFalse(String.format("node 4 is strongly preferred"), dag.isStronglyPreferred(ordered.get(4)));

        dag.prefer(ordered.get(4));

        assertTrue(dag.get(ordered.get(4)).getChit());
        assertEquals(2, dag.get(ordered.get(0)).getConfidence());
        assertEquals(2, dag.get(ordered.get(2)).getConfidence());
        assertEquals(1, dag.get(ordered.get(3)).getConfidence());
        assertEquals(1, dag.get(ordered.get(4)).getConfidence());

        assertTrue(String.format("node 3 is not strongly preferred " + ordered.get(3)),
                   dag.isStronglyPreferred(ordered.get(3)));
        assertFalse(String.format("node 4 is strongly preferred"), dag.isStronglyPreferred(ordered.get(4)));

        entry = new DagEntry();
        entry.setDescription(WellKnownDescriptions.BYTE_CONTENT.toHash());
        entry.setData(ByteBuffer.wrap(String.format("DagEntry: %s", 6).getBytes()));
        entry.setLinks(asList(ordered.get(1).toHash(), ordered.get(5).toHash()));
        key = dag.insert(entry, 0);
        stored.put(key, entry);
        ordered.add(key);

        dag.prefer(ordered.get(5));

        assertTrue(dag.get(ordered.get(4)).getChit());
        assertEquals(3, dag.get(ordered.get(0)).getConfidence());
        assertEquals(3, dag.get(ordered.get(2)).getConfidence());
        assertEquals(1, dag.get(ordered.get(3)).getConfidence());
        assertEquals(2, dag.get(ordered.get(4)).getConfidence());

        assertFalse(String.format("node 3 is strongly preferred " + ordered.get(3)),
                    dag.isStronglyPreferred(ordered.get(3)));
        assertTrue(String.format("node 4 is not strongly preferred"), dag.isStronglyPreferred(ordered.get(4)));
    }

    HashKey newDagEntry(String contents, List<HashKey> ordered, Map<HashKey, DagEntry> stored, List<HashKey> links) {
        return newDagEntry(contents, ordered, stored, links, true);
    }

    HashKey newDagEntry(String contents, List<HashKey> ordered, Map<HashKey, DagEntry> stored, List<HashKey> links,
                        boolean store) {
        return newDagEntry(contents, ordered, stored, links, null, store);
    }

    HashKey newDagEntry(String contents, List<HashKey> ordered, Map<HashKey, DagEntry> stored, List<HashKey> links,
                        HashKey conflictSet, boolean store) {
        DagEntry entry = new DagEntry();
        entry.setDescription(WellKnownDescriptions.BYTE_CONTENT.toHash());
        entry.setData(ByteBuffer.wrap(contents.getBytes()));
        entry.setLinks(links.stream().map(e -> e.toHash()).collect(Collectors.toList()));
        HashKey key = store ? dag.insert(entry, conflictSet, 0) : new HashKey(hashOf(serialize(entry)));
        stored.put(key, entry);
        ordered.add(key);
        return key;
    }

}
