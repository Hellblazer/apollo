/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.salesforce.apollo.gorgoneion.client.client.comm;

import com.salesforce.apollo.archipelago.ManagedServerChannel;
import com.salesforce.apollo.archipelago.ServerConnectionCache.CreateClientCommunications;
import com.salesforce.apollo.gorgoneion.proto.AdmissionsGrpc;
import com.salesforce.apollo.gorgoneion.proto.Credentials;
import com.salesforce.apollo.gorgoneion.proto.Establishment;
import com.salesforce.apollo.gorgoneion.proto.SignedNonce;
import com.salesforce.apollo.membership.Member;
import com.salesforce.apollo.stereotomy.event.proto.KERL_;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * @author hal.hildebrand
 */
public class AdmissionsClient implements Admissions {

    private final ManagedServerChannel                  channel;
    private final AdmissionsGrpc.AdmissionsBlockingStub client;
    private final GorgoneionClientMetrics               metrics;

    public AdmissionsClient(ManagedServerChannel channel, GorgoneionClientMetrics metrics) {
        this.channel = channel;
        this.client = channel.wrap(AdmissionsGrpc.newBlockingStub(channel));
        this.metrics = metrics;
    }

    public static CreateClientCommunications<Admissions> getCreate(GorgoneionClientMetrics metrics) {
        return (c) -> new AdmissionsClient(c, metrics);

    }

    @Override
    public SignedNonce apply(KERL_ application, Duration timeout) {
        if (metrics != null) {
            var serializedSize = application.getSerializedSize();
            metrics.outboundBandwidth().mark(serializedSize);
            metrics.outboundApplication().update(serializedSize);
        }

        SignedNonce result = client.withDeadlineAfter(timeout.toNanos(), TimeUnit.NANOSECONDS).apply(application);
        if (metrics != null) {
            var serializedSize = result.getSerializedSize();
            metrics.inboundBandwidth().mark(serializedSize);
            metrics.inboundApplication().update(serializedSize);
        }
        return result;
    }

    @Override
    public void close() {
        channel.release();
    }

    @Override
    public Member getMember() {
        return channel.getMember();
    }

    @Override
    public Establishment register(Credentials credentials, Duration timeout) {
        if (metrics != null) {
            var serializedSize = credentials.getSerializedSize();
            metrics.outboundBandwidth().mark(serializedSize);
            metrics.outboundCredentials().update(serializedSize);
        }

        var result = client.withDeadlineAfter(timeout.toNanos(), TimeUnit.NANOSECONDS).register(credentials);
        if (metrics != null) {
            try {
                var serializedSize = result.getSerializedSize();
                metrics.inboundBandwidth().mark(serializedSize);
                metrics.inboundInvitation().update(serializedSize);
            } catch (Throwable e) {
                // nothing
            }
        }
        return result;
    }
}
