/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.salesforce.apollo.stereotomy.services.grpc;

import com.salesforce.apollo.stereotomy.event.proto.Binding;
import com.salesforce.apollo.stereotomy.event.proto.Ident;
import com.salesforce.apollo.archipelago.LocalServer;
import com.salesforce.apollo.archipelago.Router;
import com.salesforce.apollo.archipelago.ServerConnectionCache;
import com.salesforce.apollo.cryptography.DigestAlgorithm;
import com.salesforce.apollo.membership.stereotomy.ControlledIdentifierMember;
import com.salesforce.apollo.stereotomy.StereotomyImpl;
import com.salesforce.apollo.stereotomy.mem.MemKERL;
import com.salesforce.apollo.stereotomy.mem.MemKeyStore;
import com.salesforce.apollo.stereotomy.services.grpc.resolver.ResolverClient;
import com.salesforce.apollo.stereotomy.services.grpc.resolver.ResolverServer;
import com.salesforce.apollo.stereotomy.services.proto.ProtoResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author hal.hildebrand
 */
public class TestResolver {

    private Router clientRouter;
    private Router serverRouter;

    @AfterEach
    public void after() {
        if (serverRouter != null) {
            serverRouter.close(Duration.ofSeconds(0));
            serverRouter = null;
        }
        if (clientRouter != null) {
            clientRouter.close(Duration.ofSeconds(0));
            clientRouter = null;
        }
    }

    @Test
    public void resolver() throws Exception {
        var context = DigestAlgorithm.DEFAULT.getOrigin();
        var prefix = UUID.randomUUID().toString();
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy);

        var serverMember = new ControlledIdentifierMember(stereotomy.newIdentifier());
        var clientMember = new ControlledIdentifierMember(stereotomy.newIdentifier());

        var builder = ServerConnectionCache.newBuilder();
        serverRouter = new LocalServer(prefix, serverMember).router(builder);
        clientRouter = new LocalServer(prefix, clientMember).router(builder);

        serverRouter.start();
        clientRouter.start();

        ProtoResolver protoService = new ProtoResolver() {

            @Override
            public Optional<Binding> lookup(Ident prefix) {
                return Optional.of(Binding.getDefaultInstance());
            }
        };

        serverRouter.create(serverMember, context, protoService, protoService.getClass().toString(),
                            r -> new ResolverServer(r, null), null, null);

        var clientComms = clientRouter.create(clientMember, context, protoService, protoService.getClass().toString(),
                                              r -> new ResolverServer(r, null), ResolverClient.getCreate(null), null);

        var client = clientComms.connect(serverMember);

        assertEquals(Binding.getDefaultInstance(), client.lookup(Ident.getDefaultInstance()).get());
    }
}
