/*
 * Copyright (c) 2020, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.salesforce.apollo.fireflies;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.salesforce.apollo.cryptography.Digest;
import com.salesforce.apollo.protocols.EndpointMetricsImpl;

import static com.codahale.metrics.MetricRegistry.name;

/**
 * @author hal.hildebrand
 */
public class FireflyMetricsImpl extends EndpointMetricsImpl implements FireflyMetrics {
    private final Meter     accusations;
    private final Meter     filteredNotes;
    private final Histogram gossipReply;
    private final Histogram gossipResponse;
    private final Histogram inboundGateway;
    private final Histogram inboundGossip;
    private final Timer     inboundGossipTimer;
    private final Histogram inboundJoin;
    private final Timer     inboundJoinDuration;
    private final Histogram inboundRedirect;
    private final Histogram inboundSeed;
    private final Timer     inboundSeedDuration;
    private final Histogram inboundUpdate;
    private final Timer     inboundUpdateTimer;
    private final Timer     joinDuration;
    private final Meter     joining;
    private final Meter     leaving;
    private final Meter     notes;
    private final Histogram outboundGateway;
    private final Histogram outboundGossip;
    private final Histogram outboundJoin;
    private final Histogram outboundRedirect;
    private final Histogram outboundSeed;
    private final Histogram outboundUpdate;
    private final Timer     outboundUpdateTimer;
    private final Timer     seedDuration;
    private final Meter     shunnedGossip;
    private final Meter     viewChanges;
    private final Timer     inboundEnjoinDuration;

    public FireflyMetricsImpl(Digest context, MetricRegistry registry) {
        super(registry);
        inboundGateway = registry.histogram(name(context.shortString(), "ff.gateway.inbound.bytes"));
        inboundJoin = registry.histogram(name(context.shortString(), "ff.join.inbound.bytes"));
        inboundJoinDuration = registry.timer(name(context.shortString(), "ff.join.inbound.duration"));
        inboundSeedDuration = registry.timer(name(context.shortString(), "ff.seed.inbound.duration"));
        outboundJoin = registry.histogram(name(context.shortString(), "ff.join.outbound.bytes"));
        joinDuration = registry.timer(name(context.shortString(), "ff.join.duration"));
        outboundGateway = registry.histogram(name(context.shortString(), "ff.gateway.outbound.bytes"));
        outboundRedirect = registry.histogram(name(context.shortString(), "ff.redirect.outbound.bytes"));
        outboundUpdateTimer = registry.timer(name(context.shortString(), "ff.update.outbound.duration"));
        inboundUpdateTimer = registry.timer(name(context.shortString(), "ff.update.inbound.duration"));
        outboundUpdate = registry.histogram(name(context.shortString(), "ff.update.outbound.bytes"));
        inboundUpdate = registry.histogram(name(context.shortString(), "ff.update.inbound.bytes"));

        inboundGossipTimer = registry.timer(name(context.shortString(), "ff.gossip.inbound.duration"));
        outboundGossip = registry.histogram(name(context.shortString(), "ff.gossip.outbound.bytes"));
        gossipResponse = registry.histogram(name(context.shortString(), "ff.gossip.reply.inbound.bytes"));
        inboundGossip = registry.histogram(name(context.shortString(), "ff.gossip.inbound.bytes"));
        gossipReply = registry.histogram(name(context.shortString(), "ff.gossip.reply.outbound.bytes"));
        accusations = registry.meter(name(context.shortString(), "ff.gossip.accusations"));
        notes = registry.meter(name(context.shortString(), "ff.gossip.notes"));
        joining = registry.meter(name(context.shortString(), "ff.joining"));
        leaving = registry.meter(name(context.shortString(), "ff.leaving"));
        filteredNotes = registry.meter(name(context.shortString(), "ff.gossip.notes.filtered"));
        inboundRedirect = registry.histogram(name(context.shortString(), "ff.redirect.inbound.bytes"));
        outboundSeed = registry.histogram(name(context.shortString(), "ff.seed.outbound.bytes"));
        seedDuration = registry.timer(name(context.shortString(), "ff.seed.duration"));
        shunnedGossip = registry.meter(name(context.shortString(), "ff.gossip.shunned"));
        inboundSeed = registry.histogram(name(context.shortString(), "ff.seed.inbound.bytes"));
        viewChanges = registry.meter(name(context.shortString(), "ff.view.change"));
        inboundEnjoinDuration = registry.timer(name(context.shortString(), "ff.enjoin.duration"));
    }

    @Override
    public Meter accusations() {
        return accusations;
    }

    @Override
    public Meter filteredNotes() {
        return filteredNotes;
    }

    @Override
    public Histogram gossipReply() {
        return gossipReply;
    }

    @Override
    public Histogram gossipResponse() {
        return gossipResponse;
    }

    @Override
    public Timer inboundEnjoinDuration() {
        return inboundEnjoinDuration;
    }

    @Override
    public Histogram inboundGateway() {
        return inboundGateway;
    }

    @Override
    public Histogram inboundGossip() {
        return inboundGossip;
    }

    @Override
    public Timer inboundGossipDuration() {
        return inboundGossipTimer;
    }

    @Override
    public Histogram inboundJoin() {
        return inboundJoin;
    }

    @Override
    public Timer inboundJoinDuration() {
        return inboundJoinDuration;
    }

    @Override
    public Histogram inboundRedirect() {
        return inboundRedirect;
    }

    @Override
    public Histogram inboundSeed() {
        return inboundSeed;
    }

    @Override
    public Timer inboundSeedDuration() {
        return inboundSeedDuration;
    }

    @Override
    public Histogram inboundUpdate() {
        return inboundUpdate;
    }

    @Override
    public Timer inboundUpdateTimer() {
        return inboundUpdateTimer;
    }

    @Override
    public Timer joinDuration() {
        return joinDuration;
    }

    @Override
    public Meter joins() {
        return joining;
    }

    @Override
    public Meter leaves() {
        return leaving;
    }

    @Override
    public Meter notes() {
        return notes;
    }

    @Override
    public Histogram outboundGateway() {
        return outboundGateway;
    }

    @Override
    public Histogram outboundGossip() {
        return outboundGossip;
    }

    @Override
    public Histogram outboundJoin() {
        return outboundJoin;
    }

    @Override
    public Histogram outboundRedirect() {
        return outboundRedirect;
    }

    @Override
    public Histogram outboundSeed() {
        return outboundSeed;
    }

    @Override
    public Histogram outboundUpdate() {
        return outboundUpdate;
    }

    @Override
    public Timer outboundUpdateTimer() {
        return outboundUpdateTimer;
    }

    @Override
    public Timer seedDuration() {
        return seedDuration;
    }

    @Override
    public Meter shunnedGossip() {
        return shunnedGossip;
    }

    @Override
    public Meter viewChanges() {
        return viewChanges;
    }
}
