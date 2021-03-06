/*
 * Copyright (c) 2019, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.apollo.avalanche;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.HTreeMap;
import org.mapdb.Serializer;

import com.salesforce.apollo.protocols.HashKey;

/**
 * @author hhildebrand
 *
 *         Manages the multi storage tier DAG db for Apollo
 */
public class DagWood {

    public static class DagWoodParameters {
        public long maxCache = 150_000;

    }

    private static final String CACHE = "dagwood.cache";

    private final HTreeMap<byte[], byte[]> cache;
    private final DB                       dbMemory;

    public DagWood(DagWoodParameters parameters) {
        dbMemory = DBMaker.memoryDirectDB().cleanerHackEnable().make();

        cache = dbMemory.hashMap(CACHE)
                        .keySerializer(Serializer.BYTE_ARRAY)
                        .valueSerializer(Serializer.BYTE_ARRAY)
                        .expireAfterCreate()
                        .counterEnable()
                        .createOrOpen();
    }

    public List<HashKey> allFinalized() {
        List<HashKey> all = new ArrayList<>();
        cache.keySet().forEach(e -> all.add(new HashKey(e)));
        return all;
    }

    public boolean cacheContainsKey(byte[] key) {
        return cache.containsKey(key);
    }

    public void close() {
        dbMemory.close();
    }

    public boolean containsKey(byte[] key) {
        return cache.containsKey(key);
    }

    public byte[] get(byte[] key) {
        return cache.get(key);

    }

    public Set<byte[]> keySet() {
        return cache.keySet();
    }

    public void put(byte[] key, byte[] entry) {
        cache.putIfAbsent(key, entry);
    }

    public int size() {
        return cache.getSize();
    }
}
