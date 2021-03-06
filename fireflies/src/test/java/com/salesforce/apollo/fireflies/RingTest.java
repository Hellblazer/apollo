/*
 * Copyright (c) 2019, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.salesforce.apollo.fireflies;

import static io.github.olivierlemasle.ca.CA.createCsr;
import static io.github.olivierlemasle.ca.CA.dn;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.salesforce.apollo.fireflies.ca.CertificateAuthority;

import io.github.olivierlemasle.ca.CSR;
import io.github.olivierlemasle.ca.Certificate;
import io.github.olivierlemasle.ca.RootCertificate;

/**
 * @author hal.hildebrand
 * @since 220
 */
public class RingTest {
    private static List<Member> members;

    @BeforeClass
    public static void beforeClass() {
        RootCertificate root = CertificateAuthority.mint(dn().setCn("test-ca.com")
                                                             .setO("World Company")
                                                             .setOu("IT dep")
                                                             .setSt("CA")
                                                             .setC("US")
                                                             .build(),
                                                         10_000, 0.012, 25, "");
        CertificateAuthority ca = new CertificateAuthority(root);

        members = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Member m = createMember(ca);
            members.add(m);
        }
    }

    private static Member createMember(CertificateAuthority ca) {
        final CSR csr = createCsr().generateRequest(dn().setCn("test.com")
                                                        .setL("1638:1639:1640")
                                                        .setO("World Company")
                                                        .setOu("IT dep")
                                                        .setSt("CA")
                                                        .setC("US")
                                                        .build());
        Certificate certificate = ca.mintNode(csr);

        FirefliesParameters parameters = new FirefliesParameters(ca.getRoot());
        return new Member(certificate.getX509Certificate(), parameters);
    }

    private Ring ring;

    @Before
    public void before() {
        int index = 0;
        ring = new Ring(index);
        members.forEach(m -> ring.insert(m));

        Collections.sort(members, new Comparator<Member>() {
            @Override
            public int compare(Member o1, Member o2) {
                return o1.hashFor(index).compareTo(o2.hashFor(index));
            }
        });
    }

    @Test
    public void betweenPredecessor() {
        int start = 5;
        int stop = 3;
        int index = start - 1;

        for (Member test : ring.betweenPredecessors(members.get(start), members.get(stop))) {
            if (index == -1) {
                index = members.size() - 1; // wrap around
            }
            assertEquals(index, members.indexOf(test));
            index--;
        }

        start = 3;
        stop = 5;
        index = start - 1;

        for (Member test : ring.betweenPredecessors(members.get(start), members.get(stop))) {
            if (index == -1) {
                index = members.size() - 1; // wrap around
            }
            assertEquals(index, members.indexOf(test));
            index--;
        }
    }

    @Test
    public void betweenSuccessor() {
        int start = 5;
        int stop = 3;
        int index = start + 1;

        for (Member test : ring.betweenSuccessor(members.get(start), members.get(stop))) {
            if (index == members.size()) {
                index = 0; // wrap around
            }
            assertEquals("error at index: " + index, members.get(index), test);
            index++;
        }
    }

    @Test
    public void predecessors() {
        Collection<Member> predecessors = ring.streamPredecessors(members.get(5),
                                                                  m -> m.equals(members.get(members.size() - 3)))
                                              .collect(Collectors.toList());
        assertFalse(predecessors.isEmpty());
        assertEquals(7, predecessors.size());
    }

    @Test
    public void successors() {
        Collection<Member> successors = ring.streamSuccessors(members.get(5), m -> m.equals(members.get(3)))
                                            .collect(Collectors.toList());
        assertFalse(successors.isEmpty());
        assertEquals(7, successors.size());
    }

    @Test
    public void predecessor() {
        assertEquals(5, members.indexOf(ring.predecessor(members.get(6))));
        assertEquals(members.size() - 1, members.indexOf(ring.predecessor(members.get(0))));
    }

    @Test
    public void rank() {
        assertEquals(members.size() - 2, ring.rank(members.get(0), members.get(members.size() - 1)));

        assertEquals(members.size() - 2, ring.rank(members.get(members.size() - 1), members.get(members.size() - 2)));

        assertEquals(members.size() - 2, ring.rank(members.get(1), members.get(0)));

        assertEquals(2, ring.rank(members.get(0), members.get(3)));

        assertEquals(6, ring.rank(members.get(0), members.get(7)));
    }

    @Test
    public void successor() {
        assertEquals(5, members.indexOf(ring.successor(members.get(4))));
        assertEquals(0, members.indexOf(ring.successor(members.get(members.size() - 1))));

        for (int i = 0; i < members.size(); i++) {
            int successor = (i + 1) % members.size();
            assertEquals(successor, members.indexOf(ring.successor(members.get(i))));
        }
    }

    @Test
    public void theRing() {

        assertEquals(members.size(), ring.size());

        for (int start = 0; start < members.size(); start++) {
            int index = start + 1;
            for (Member member : ring.traverse(members.get(start))) {
                if (index == members.size()) {
                    index = 0; // wrap around
                }
                assertEquals("Member " + index + " failed", members.get(index), member);
                index++;
            }
        }
    }
}
