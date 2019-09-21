/*
 * Copyright 2019, salesforce.com
 * All Rights Reserved
 * Company Confidential
 */
package com.salesforce.apollo.ghost;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.salesforce.apollo.avro.Interval;
import com.salesforce.apollo.avro.HASH;

/**
 * @author hal.hildebrand
 * @since 220
 */
public class CombinedIntervals implements Predicate<HASH> {
    private final List<KeyInterval> intervals = new ArrayList<>();

    public CombinedIntervals(List<KeyInterval> allIntervals) {
        if (allIntervals.isEmpty()) {
            return;
        }
        Collections.sort(allIntervals, new Comparator<KeyInterval>() {
            @Override
            public int compare(KeyInterval o1, KeyInterval o2) {
                int comparison = o1.getBegin().compareTo(o2.getBegin());

                return comparison == 0 // if both intervals begin the same
                        ? o1.getEnd().compareTo(o2.getEnd()) // compare their ends
                        : comparison;
            }
        });
        KeyInterval current = allIntervals.get(0);
        intervals.add(current);
        for (int i = 1; i < allIntervals.size(); i++) {
            KeyInterval next = allIntervals.get(i);

            int compare = current.getEnd().compareTo(next.getBegin());
            if (compare < 0) {
                intervals.add(next);
                current = next;
            } else {
                // overlapping intervals
                current = new KeyInterval(current.getBegin(), next.getEnd());
                intervals.set(intervals.size() - 1, current);
            }
        }
    }

    public List<KeyInterval> getIntervals() {
        return intervals;
    }

    @Override
    public boolean test(HASH t) {
        return intervals.stream().filter(i -> i.test(t)).findFirst().isPresent();
    }

    public List<Interval> toIntervals() {
        return intervals.stream()
                        .map(e -> new Interval(e.getBegin().toHash(), e.getEnd().toHash()))
                        .collect(Collectors.toList());
    }
}
