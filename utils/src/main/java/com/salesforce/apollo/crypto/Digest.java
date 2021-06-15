/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.salesforce.apollo.crypto;

import java.nio.ByteBuffer;

import org.bouncycastle.util.encoders.Hex;

import com.google.protobuf.ByteString;

/**
 * A computed digest
 * 
 * @author hal.hildebrand
 *
 */
public class Digest implements Comparable<Digest> {

    public static final Digest NONE = new Digest(DigestAlgorithm.NONE, new byte[0]);

    public static int compare(byte[] o1, byte[] o2) {
        if (o1 == null) {
            return o2 == null ? 0 : -1;
        } else if (o2 == null) {
            return 1;
        }
        if (o1.length != o2.length) {
            return o1.length - o2.length;
        }
        for (int i = 0; i < o1.length; i++) {
            final int diff = (o1[i] & 0xFF) - (o2[i] & 0xFF);
            if (diff != 0) {
                return diff;
            }
        }
        return 0;
    }

    public static Digest from(ByteString bs) {
        return new Digest(bs);
    }

    public static Digest normalized(DigestAlgorithm digestAlgorithm, byte[] bs) {
        if (bs.length > digestAlgorithm.digestLength()) {
            throw new IllegalArgumentException();
        }
        byte[] hash = new byte[digestAlgorithm.digestLength()];
        for (int i = 0; i < bs.length; i++) {
            hash[i] = bs[i];
        }
        return new Digest(digestAlgorithm, hash);
    }

    private final DigestAlgorithm algorithm;

    private final long[] hash;

    public Digest(byte code, long[] hash) {
        algorithm = DigestAlgorithm.fromDigestCode(code);
        assert hash.length == algorithm.longLength();
        this.hash = hash;
    }

    public Digest(ByteBuffer buff) {
        algorithm = DigestAlgorithm.fromDigestCode(buff.get());
        hash = new long[algorithm.longLength()];
        for (int i = 0; i < hash.length; i++) {
            hash[i] = buff.getLong();
        }
    }

    public Digest(ByteString encoded) {
        this(encoded.asReadOnlyByteBuffer());
    }

    public Digest(DigestAlgorithm algorithm, byte[] bytes) {
        assert bytes != null && algorithm != null;

        if (bytes.length != algorithm.digestLength()) {
            throw new IllegalArgumentException(
                    "Invalid bytes length.  Require: " + algorithm.digestLength() + " found: " + bytes.length);
        }
        this.algorithm = algorithm;
        int length = algorithm.longLength();
        this.hash = new long[length];
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        for (int i = 0; i < length; i++) {
            hash[i] = buffer.getLong();
        }
    }

    public Digest(DigestAlgorithm algo, long[] hash) {
        if (hash.length != algo.longLength()) {
            throw new IllegalArgumentException("hash length incorrect for algorithm");
        }
        algorithm = algo;
        this.hash = hash;
    }

    @Override
    public int compareTo(Digest id) {
        for (int i = 0; i < hash.length; i++) {
            int compare = Long.compareUnsigned(hash[i], id.hash[i]);
            if (compare != 0) {
                return compare;
            }
        }
        return 0;
    }

    public int digestCode() {
        return algorithm.digestCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof Digest)) {
            return false;
        }
        Digest other = (Digest) obj;
        if (algorithm != other.algorithm) {
            return false;
        }

        for (int i = 0; i < hash.length; i++) {
            if (hash[i] != other.hash[i]) {
                return false;
            }
        }
        return true;
    }

    public DigestAlgorithm getAlgorithm() {
        return algorithm;
    }

    public byte[] getBytes() {
        byte[] bytes = new byte[algorithm.digestLength()];
        ByteBuffer buff = ByteBuffer.wrap(bytes);
        for (int i = 0; i < hash.length; i++) {
            buff.putLong(hash[i]);
        }
        return bytes;
    }

    public long[] getLongs() {
        return hash;
    }

    @Override
    public int hashCode() {
        for (long l : hash) {
            if (l != 0) {
                return (int) (l & 0xFFFFFFFF);
            }
        }
        return 0;
    }

    public Digest prefix(byte[]... prefixes) {
        int prefixLength = 0;
        for (byte[] p : prefixes) {
            prefixLength += p.length;
        }
        ByteBuffer buffer = ByteBuffer.allocate(hash.length * 8 + prefixLength);
        for (byte[] prefix : prefixes) {
            buffer.put(prefix);
        }
        for (long h : hash) {
            buffer.putLong(h);
        }
        buffer.flip();
        Digest d = getAlgorithm().digest(buffer);
        return new Digest(getAlgorithm(), d.getBytes());
    }

    public Digest prefix(long... prefixes) {
        ByteBuffer buffer = ByteBuffer.allocate(hash.length * 8 + (prefixes.length * 8));
        for (long prefix : prefixes) {
            buffer.putLong(prefix);
        }
        for (long h : hash) {
            buffer.putLong(h);
        }
        buffer.flip();
        Digest d = getAlgorithm().digest(buffer);
        return new Digest(getAlgorithm(), d.getBytes());
    }

    public ByteString toByteString() {
        ByteBuffer buffer = ByteBuffer.allocate(1 + hash.length * 8);
        buffer.put(algorithm.digestCode());

        for (long l : hash) {
            buffer.putLong(l);
        }
        buffer.flip();
        return ByteString.copyFrom(buffer);
    }

    @Override
    public String toString() {
        return "[" + Hex.toHexString(getBytes()).substring(0, 12) + ":" + algorithm.digestCode() + "]";
    }

    public Digest xor(Digest b) {
        if (algorithm != b.algorithm) {
            throw new IllegalArgumentException("Cannot xor digests of different algorithms");
        }
        long[] xord = new long[hash.length];
        long[] bHash = b.hash;

        for (int i = 0; i < hash.length; i++) {
            xord[i] = hash[i] ^ bHash[i];
        }
        return new Digest(algorithm, xord);
    }
}