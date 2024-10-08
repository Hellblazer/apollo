package com.salesforce.apollo.model;

import com.salesforce.apollo.archipelago.Portal;
import com.salesforce.apollo.choam.Parameters;
import com.salesforce.apollo.comm.grpc.DomainSocketServerInterceptor;
import com.salesforce.apollo.cryptography.Digest;
import com.salesforce.apollo.cryptography.JohnHancock;
import com.salesforce.apollo.cryptography.Signer;
import com.salesforce.apollo.cryptography.proto.Digeste;
import com.salesforce.apollo.demesne.proto.DemesneParameters;
import com.salesforce.apollo.demesne.proto.SubContext;
import com.salesforce.apollo.membership.Member;
import com.salesforce.apollo.membership.stereotomy.ControlledIdentifierMember;
import com.salesforce.apollo.model.demesnes.Demesne;
import com.salesforce.apollo.model.demesnes.JniBridge;
import com.salesforce.apollo.model.demesnes.comm.OuterContextServer;
import com.salesforce.apollo.model.demesnes.comm.OuterContextService;
import com.salesforce.apollo.stereotomy.event.Seal;
import com.salesforce.apollo.stereotomy.event.proto.AttachmentEvent;
import com.salesforce.apollo.stereotomy.event.proto.KeyState_;
import com.salesforce.apollo.stereotomy.identifier.BasicIdentifier;
import com.salesforce.apollo.stereotomy.identifier.SelfAddressingIdentifier;
import com.salesforce.apollo.stereotomy.identifier.spec.IdentifierSpecification;
import com.salesforce.apollo.stereotomy.identifier.spec.InteractionSpecification;
import com.salesforce.apollo.stereotomy.services.grpc.StereotomyMetrics;
import io.grpc.BindableService;
import io.grpc.ManagedChannel;
import io.grpc.netty.DomainSocketNegotiatorHandler;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.NettyServerBuilder;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.unix.DomainSocketAddress;
import org.joou.ULong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.salesforce.apollo.comm.grpc.DomainSocketServerInterceptor.IMPL;
import static com.salesforce.apollo.cryptography.QualifiedBase64.qb64;

/**
 * @author hal.hildebrand
 **/
public class ProcessContainerDomain extends ProcessDomain {

    private final static Logger                                                    log                   = LoggerFactory.getLogger(
    ProcessContainerDomain.class);
    private final static Class<? extends io.netty.channel.Channel>                 channelType           = IMPL.getChannelType();
    protected final      Executor                                                  executor              = Executors.newVirtualThreadPerTaskExecutor();
    private final        DomainSocketAddress                                       bridge;
    private final        EventLoopGroup                                            clientEventLoopGroup  = IMPL.getEventLoopGroup();
    private final        Path                                                      communicationsDirectory;
    private final        EventLoopGroup                                            contextEventLoopGroup = IMPL.getEventLoopGroup();
    private final        Map<Digest, Demesne>                                      hostedDomains         = new ConcurrentHashMap<>();
    private final        Portal<Member>                                            portal;
    private final        DomainSocketAddress                                       portalEndpoint;
    private final        EventLoopGroup                                            portalEventLoopGroup  = IMPL.getEventLoopGroup();
    private final        Map<String, DomainSocketAddress>                          routes                = new HashMap<>();
    private final        IdentifierSpecification.Builder<SelfAddressingIdentifier> subDomainSpecification;

    public ProcessContainerDomain(Digest group, ControlledIdentifierMember member, ProcessDomainParameters parameters,
                                  Parameters.Builder builder, Parameters.RuntimeParameters.Builder runtime,
                                  String endpoint, Path commDirectory,
                                  com.salesforce.apollo.fireflies.Parameters.Builder ff,
                                  IdentifierSpecification.Builder<SelfAddressingIdentifier> subDomainSpecification,
                                  StereotomyMetrics stereotomyMetrics) {
        super(group, member, parameters, builder, runtime, endpoint, ff, stereotomyMetrics);
        communicationsDirectory = commDirectory;
        bridge = new DomainSocketAddress(communicationsDirectory.resolve(UUID.randomUUID().toString()).toFile());
        portalEndpoint = new DomainSocketAddress(
        communicationsDirectory.resolve(UUID.randomUUID().toString()).toFile());
        portal = new Portal<>(member.getId(), NettyServerBuilder.forAddress(portalEndpoint)
                                                                .protocolNegotiator(
                                                                new DomainSocketNegotiatorHandler.DomainSocketNegotiator(
                                                                IMPL))
                                                                .executor(Executors.newVirtualThreadPerTaskExecutor())
                                                                .withChildOption(ChannelOption.TCP_NODELAY, true)
                                                                .channelType(IMPL.getServerDomainSocketChannelClass())
                                                                .workerEventLoopGroup(portalEventLoopGroup)
                                                                .bossEventLoopGroup(portalEventLoopGroup)
                                                                .intercept(new DomainSocketServerInterceptor()),
                              s -> handler(portalEndpoint), bridge, Duration.ofMillis(1), s -> routes.get(s));
        this.subDomainSpecification = subDomainSpecification;
    }

    public SelfAddressingIdentifier spawn(DemesneParameters.Builder prototype) {
        final var witness = member.getIdentifier().newEphemeral().get();
        final var cloned = prototype.clone();
        var parameters = cloned.setCommDirectory(communicationsDirectory.toString())
                               .setPortal(portalEndpoint.path())
                               .build();
        var ctxId = Digest.from(parameters.getContext());
        final AtomicBoolean added = new AtomicBoolean();
        final var demesne = new JniBridge(parameters);
        var computed = hostedDomains.computeIfAbsent(ctxId, k -> {
            added.set(true);
            return demesne;
        });
        if (added.get()) {
            var newSpec = subDomainSpecification.clone();
            // the receiver is a witness to the subdomain's delegated key
            var newWitnesses = new ArrayList<>(subDomainSpecification.getWitnesses());
            newWitnesses.add(new BasicIdentifier(witness.getPublic()));
            newSpec.setWitnesses(newWitnesses);
            var incp = demesne.inception(member.getIdentifier().getIdentifier().toIdent(), newSpec);
            var sigs = new HashMap<Integer, JohnHancock>();
            sigs.put(0, new Signer.SignerImpl(witness.getPrivate(), ULong.MIN).sign(incp.toKeyEvent_().toByteString()));
            var attached = new com.salesforce.apollo.stereotomy.event.AttachmentEvent.AttachmentImpl(sigs);
            var seal = Seal.EventSeal.construct(incp.getIdentifier(), incp.hash(dht.digestAlgorithm()),
                                                incp.getSequenceNumber().longValue());
            var builder = InteractionSpecification.newBuilder().addAllSeals(Collections.singletonList(seal));
            KeyState_ ks = dht.append(AttachmentEvent.newBuilder()
                                                     .setCoordinates(incp.getCoordinates().toEventCoords())
                                                     .setAttachment(attached.toAttachemente())
                                                     .build());
            var coords = member.getIdentifier().seal(builder);
            demesne.commit(coords.toEventCoords());
            demesne.start();
            return (SelfAddressingIdentifier) incp.getIdentifier();
        }
        return computed.getId();
    }

    @Override
    protected void startServices() {
        super.startServices();
        try {
            portal.start();
        } catch (IOException e) {
            throw new IllegalStateException(
            "Unable to start portal, local address: " + bridge.path() + " on: " + params.member().getId());
        }
    }

    @Override
    protected void stopServices() {
        super.stopServices();
        portal.close(Duration.ofSeconds(30));
        hostedDomains.values().forEach(d -> d.stop());
        var portalELG = portalEventLoopGroup.shutdownGracefully();
        var serverELG = contextEventLoopGroup.shutdownGracefully();
        var clientELG = clientEventLoopGroup.shutdownGracefully();
        try {
            if (clientELG.await(30, TimeUnit.SECONDS)) {
                log.info("Did not completely shutdown client event loop group for process: {}", member.getId());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            if (!serverELG.await(30, TimeUnit.SECONDS)) {
                log.info("Did not completely shutdown server event loop group for process: {}", member.getId());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            if (!portalELG.await(30, TimeUnit.SECONDS)) {
                log.info("Did not completely shutdown portal event loop group for process: {}", member.getId());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private ManagedChannel handler(DomainSocketAddress address) {
        return NettyChannelBuilder.forAddress(address)
                                  .withOption(ChannelOption.TCP_NODELAY, true)
                                  .executor(executor)
                                  .eventLoopGroup(clientEventLoopGroup)
                                  .channelType(channelType)
                                  .keepAliveTime(1, TimeUnit.SECONDS)
                                  .usePlaintext()
                                  .build();
    }

    private BindableService outerContextService() {
        return new OuterContextServer(new OuterContextService() {

            @Override
            public void deregister(Digeste context) {
                routes.remove(qb64(Digest.from(context)));
            }

            @Override
            public void register(SubContext context) {
                //                routes.put("",qb64(Digest.from(context)));
            }
        }, null);
    }
}
