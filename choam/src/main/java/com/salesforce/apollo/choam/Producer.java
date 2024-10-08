/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.salesforce.apollo.choam;

import com.chiralbehaviors.tron.Fsm;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.salesforce.apollo.choam.fsm.Driven;
import com.salesforce.apollo.choam.fsm.Driven.Earner;
import com.salesforce.apollo.choam.fsm.Driven.Transitions;
import com.salesforce.apollo.choam.proto.*;
import com.salesforce.apollo.choam.proto.SubmitResult.Result;
import com.salesforce.apollo.choam.support.HashedBlock;
import com.salesforce.apollo.choam.support.HashedCertifiedBlock;
import com.salesforce.apollo.choam.support.TxDataSource;
import com.salesforce.apollo.cryptography.Digest;
import com.salesforce.apollo.cryptography.DigestAlgorithm;
import com.salesforce.apollo.ethereal.Config;
import com.salesforce.apollo.ethereal.Config.Builder;
import com.salesforce.apollo.ethereal.Dag;
import com.salesforce.apollo.ethereal.Ethereal;
import com.salesforce.apollo.ethereal.memberships.ChRbcGossip;
import com.salesforce.apollo.membership.Member;
import com.salesforce.apollo.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An "Earner"
 *
 * @author hal.hildebrand
 */
public class Producer {

    private static final Logger                       log                = LoggerFactory.getLogger(Producer.class);
    private final        AtomicReference<HashedBlock> checkpoint         = new AtomicReference<>();
    private final        Ethereal                     controller;
    private final        ChRbcGossip                  coordinator;
    private final        TxDataSource                 ds;
    private final        Map<Digest, PendingBlock>    pending            = new ConcurrentSkipListMap<>();
    private final        Map<Digest, List<Validate>>  pendingValidations = new ConcurrentSkipListMap<>();
    private final        AtomicReference<HashedBlock> previousBlock      = new AtomicReference<>();
    private final        AtomicBoolean                started            = new AtomicBoolean(false);
    private final        Transitions                  transitions;
    private final        ViewContext                  view;
    private final        Digest                       nextViewId;
    private final        ExecutorService              serialize;
    private final        ViewAssembly                 assembly;
    private final        int                          maxEpoch;
    private final        AtomicBoolean                assembled          = new AtomicBoolean(false);
    private final        AtomicInteger                epoch              = new AtomicInteger(-1);
    private final        AtomicInteger                preblocks          = new AtomicInteger();
    private final        HashedCertifiedBlock         initialBlock;

    public Producer(Digest nextViewId, ViewContext view, HashedCertifiedBlock lastBlock,
                    HashedCertifiedBlock checkpoint, String label, ScheduledExecutorService scheduler) {
        assert view != null;
        this.view = view;
        this.previousBlock.set(lastBlock);
        this.checkpoint.set(checkpoint);
        this.nextViewId = nextViewId;
        initialBlock = lastBlock;

        final Parameters params = view.params();
        final var producerParams = params.producer();
        final Builder ep = producerParams.ethereal().clone();

        // Number of rounds we can provide data for
        final var blocks = ep.getEpochLength() - 2;
        maxEpoch = ep.getNumberOfEpochs();

        ds = new TxDataSource(params.member(), blocks, params.metrics(), producerParams.maxBatchByteSize(),
                              producerParams.batchInterval(), producerParams.maxBatchCount());

        log.debug("Producer max elements: {} reconfiguration epoch: {} on: {}", blocks, maxEpoch,
                  params.member().getId());

        var fsm = Fsm.construct(new DriveIn(), Transitions.class, Earner.INITIAL, true);
        fsm.setName("Producer%s on: %s".formatted(getViewId(), params.member().getId()));
        transitions = fsm.getTransitions();

        Config.Builder config = ep.setNumberOfEpochs(-1);

        // Canonical assignment of members -> pid for Ethereal
        Short pid = view.roster().get(params().member().getId());
        if (pid == null) {
            config.setPid((short) 0).setnProc((short) 1);
        } else {
            log.trace("Pid: {} for: {} on: {}", pid, getViewId(), params().member().getId());
            config.setPid(pid).setnProc((short) view.roster().size());
        }
        serialize = Executors.newScheduledThreadPool(1, Thread.ofVirtual().factory());
        config.setLabel("Producer" + getViewId() + " on: " + params().member().getId());
        var producerMetrics = params().metrics() == null ? null : params().metrics().getProducerMetrics();
        controller = new Ethereal(config.build(), params().producer().maxBatchByteSize() + (8 * 1024), ds, this::serial,
                                  this::newEpoch, label);
        coordinator = new ChRbcGossip(view.context().getId(), params().member(), view.membership(),
                                      controller.processor(), params().communications(), producerMetrics, scheduler);
        log.debug("Roster for: {} is: {} on: {}", getViewId(), view.roster(), params().member().getId());

        var onConsensus = new CompletableFuture<ViewAssembly.Vue>();
        onConsensus.whenComplete((v, throwable) -> {
            if (throwable == null) {
                produceAssemble(v);
            } else {
                log.warn("Error in view consensus on: {}", params.member().getId(), throwable);
            }
        });
        assembly = new ViewAssembly(nextViewId, view, Producer.this::addAssembly, onConsensus) {
            @Override
            public boolean complete() {
                if (super.complete()) {
                    log.debug("View reconfiguration: {} gathered: {} complete on: {}", nextViewId,
                              getSlate().keySet().stream().sorted().toList(), params().member().getId());
                    assembled.set(true);
                    return true;
                }
                return false;
            }
        };
    }

    public void join(SignedViewMember viewMember) {
        assembly.joined(viewMember);
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        try {
            Thread.ofVirtual().start(Utils.wrapped(() -> {
                final Block prev = previousBlock.get().block;
                // genesis block won't ever be 0
                if (prev.hasGenesis() || (prev.hasReconfigure() && prev.getReconfigure().getCheckpointTarget() == 0)) {
                    transitions.checkpoint();
                } else {
                    log.trace("Checkpoint target: {} for: {} on: {}", prev.getReconfigure().getCheckpointTarget(),
                              params().context().getId(), params().member().getId());
                    transitions.start();
                }
            }, log));
        } catch (RejectedExecutionException e) {
            log.trace("Reject fork on: {}", params().member().getId());
        }
    }

    public void stop() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        log.trace("Closing producer for: {} on: {}", getViewId(), params().member().getId());
        serialize.shutdown();
        controller.stop();
        coordinator.stop();
        ds.close();
    }

    public SubmitResult submit(Transaction transaction) {
        if (!started.get()) {
            return SubmitResult.newBuilder().setResult(Result.NO_COMMITTEE).build();
        }
        if (ds.offer(transaction)) {
            return SubmitResult.newBuilder().setResult(Result.PUBLISHED).build();
        } else {
            return SubmitResult.newBuilder().setResult(Result.BUFFER_FULL).build();
        }
    }

    private void addAssembly(Assemblies assemblies) {
        if (ds.offer(assemblies)) {
            log.trace("Adding {} joins, {} views on: {}", assemblies.getJoinsCount(), assemblies.getViewsCount(),
                      params().member().getId());
        } else {
            log.trace("Cannot add {} joins, {} views on: {}", assemblies.getJoinsCount(), assemblies.getViewsCount(),
                      params().member().getId());
        }
    }

    private List<UnitData> aggregate(List<ByteString> preblock) {
        var aggregate = preblock.stream().map(e -> {
            try {
                return UnitData.parseFrom(e);
            } catch (InvalidProtocolBufferException ex) {
                log.error("Error parsing unit data on: {}", params().member().getId(), ex);
                return null;
            }
        }).filter(Objects::nonNull).toList();

        aggregate.stream()
                 .flatMap(e -> e.getValidationsList().stream())
                 .map(this::validate)
                 .filter(Objects::nonNull)
                 .filter(p -> !p.published.get())
                 .filter(p -> p.witnesses.size() >= params().majority())
                 .forEach(this::publish);
        return aggregate;
    }

    private void create(List<ByteString> preblock, boolean last) {
        var count = preblocks.incrementAndGet();
        if (log.isDebugEnabled()) {
            log.debug("emit #{} epoch: {} hashes: {} last: {} on: {}", count, epoch,
                      preblock.stream().map(DigestAlgorithm.DEFAULT::digest).toList(), last, params().member().getId());
        }
        var aggregate = aggregate(preblock);
        processAssemblies(aggregate);
        processTransactions(last, aggregate);
        if (last) {
            started.set(false);
            transitions.lastBlock();
        }
    }

    private Digest getViewId() {
        return view.context().getId();
    }

    private void newEpoch(Integer e) {
        serialize.execute(Utils.wrapped(() -> {
            this.epoch.set(e);
            log.trace("new epoch: {} preblocks: {} on: {}", e, preblocks.get(), params().member().getId());
            assembly.newEpoch();
            var last = e >= maxEpoch && assembled.get();
            if (last) {
                controller.completeIt();
                Producer.this.transitions.viewComplete();
            } else {
                ds.reset();
            }
            transitions.newEpoch(e, last);
        }, log));
    }

    private Parameters params() {
        return view.params();
    }

    private void processAssemblies(List<UnitData> aggregate) {
        var aggs = aggregate.stream().flatMap(e -> e.getAssembliesList().stream()).toList();
        log.trace("Consuming: {} assemblies from: {} units on: {}", aggs.size(), aggregate.size(),
                  params().member().getId());
        assembly.assemble(aggs);
    }

    private void processPendingValidations(HashedBlock block, PendingBlock p) {
        var pending = pendingValidations.get(block.hash);
        if (pending != null) {
            pending.forEach(v -> validate(v, p, block.hash));
            if (p.witnesses.size() >= params().majority()) {
                publish(p);
                pendingValidations.remove(block.hash);
            }
        }
    }

    private void processTransactions(boolean last, List<UnitData> aggregate) {
        HashedBlock lb = previousBlock.get();
        final var txns = aggregate.stream().flatMap(e -> e.getTransactionsList().stream()).toList();

        if (txns.isEmpty()) {
            pending.values()
                   .stream()
                   .filter(pb -> pb.published.get())
                   .max(Comparator.comparing(pb -> pb.block.height()))
                   .ifPresentOrElse(pb -> publish(pb, true), () -> view.publish(initialBlock, true));
            return;
        }
        log.trace("transactions: {} combined hash: {} height: {} on: {}", txns.size(),
                  txns.stream().map(t -> CHOAM.hashOf(t, params().digestAlgorithm())).reduce(Digest::xor).orElse(null),
                  lb.height().add(1), params().member().getId());
        var builder = Executions.newBuilder();
        txns.forEach(builder::addExecutions);

        var next = new HashedBlock(params().digestAlgorithm(),
                                   view.produce(lb.height().add(1), lb.hash, builder.build(), checkpoint.get()));
        previousBlock.set(next);

        final var validation = view.generateValidation(next);
        ds.offer(validation);
        final var p = new PendingBlock(next, new HashMap<>(), new AtomicBoolean());
        pending.put(next.hash, p);
        p.witnesses.put(params().member(), validation);
        log.debug("Produced block: {} hash: {} height: {} prev: {} last: {} on: {}", next.block.getBodyCase(),
                  next.hash, next.height(), lb.hash, last, params().member().getId());
        processPendingValidations(next, p);
    }

    private void produceAssemble(ViewAssembly.Vue v) {
        final var vlb = previousBlock.get();
        var ass = Assemble.newBuilder()
                          .setView(View.newBuilder()
                                       .setDiadem(v.diadem().toDigeste())
                                       .setMajority(v.majority())
                                       .addAllCommittee(
                                       v.assembly().keySet().stream().sorted().map(Digest::toDigeste).toList()))
                          .build();
        final var assemble = new HashedBlock(params().digestAlgorithm(),
                                             view.produce(vlb.height().add(1), vlb.hash, ass, checkpoint.get()));
        previousBlock.set(assemble);
        final var validation = view.generateValidation(assemble);
        final var p = new PendingBlock(assemble, new HashMap<>(), new AtomicBoolean());
        pending.put(assemble.hash, p);
        p.witnesses.put(params().member(), validation);
        ds.offer(validation);
        log.debug("View assembly: {} block: {} height: {} body: {} from: {} on: {}", nextViewId, assemble.hash,
                  assemble.height(), assemble.block.getBodyCase(), getViewId(), params().member().getId());
        transitions.assembled();
    }

    private void publish(PendingBlock p) {
        this.publish(p, false);
    }

    private void publish(PendingBlock p, boolean beacon) {
        assert p.witnesses.size() >= params().majority() : "Attempt to publish non majority block";
        var publish = p.published.compareAndSet(false, true);
        if (!publish && !beacon) {
            log.trace("Already published: {} hash: {} height: {} witnesses: {} on: {}", p.block.block.getBodyCase(),
                      p.block.hash, p.block.height(), p.witnesses.values().size(), params().member().getId());
            return;
        }
        log.trace("Publishing {}: {} hash: {} height: {} witnesses: {} on: {}", beacon ? "(beacon) " : "(pending)",
                  p.block.block.getBodyCase(), p.block.hash, p.block.height(), p.witnesses.values().size(),
                  params().member().getId());
        final var cb = CertifiedBlock.newBuilder()
                                     .setBlock(p.block.block)
                                     .addAllCertifications(
                                     p.witnesses.values().stream().map(Validate::getWitness).toList())
                                     .build();
        view.publish(new HashedCertifiedBlock(params().digestAlgorithm(), cb), beacon);
    }

    private void reconfigure() {
        final var slate = assembly.getSlate();
        assert slate != null && !slate.isEmpty() : slate == null ? "Slate is null" : "Slate is empty";
        assert Dag.validate(slate.size()) : "Reconfigure joins: %s is not BFT".formatted(slate.size());
        var reconfiguration = new HashedBlock(params().digestAlgorithm(),
                                              view.reconfigure(slate, nextViewId, previousBlock.get(),
                                                               checkpoint.get()));
        var validation = view.generateValidation(reconfiguration);
        final var p = new PendingBlock(reconfiguration, new HashMap<>(), new AtomicBoolean());
        pending.put(reconfiguration.hash, p);
        p.witnesses.put(params().member(), validation);
        ds.offer(validation);
        log.trace("Produced: {} hash: {} height: {} slate: {} on: {}", reconfiguration.block.getBodyCase(),
                  reconfiguration.hash, reconfiguration.height(), slate.keySet().stream().sorted().toList(),
                  params().member().getId());
        processPendingValidations(reconfiguration, p);

        log.trace("Draining on: {}", params().member().getId());
        ds.drain();
        final var dropped = ds.getRemainingTransactions();
        if (dropped != 0) {
            log.warn("Dropped txns: {} on: {}", dropped, params().member().getId());
        }
    }

    private void serial(List<ByteString> preblock, Boolean last) {
        serialize.execute(Utils.wrapped(() -> create(preblock, last), log));
    }

    private PendingBlock validate(Validate v) {
        Digest hash = Digest.from(v.getHash());
        var p = pending.get(hash);
        if (p == null) {
            pendingValidations.computeIfAbsent(hash, _ -> new CopyOnWriteArrayList<>()).add(v);
            return null;
        }
        return validate(v, p, hash);
    }

    private PendingBlock validate(Validate v, PendingBlock p, Digest hash) {
        var from = Digest.from(v.getWitness().getId());
        if (!view.validate(p.block, v)) {
            log.trace("Invalid validate from: {} for: {} hash: {} on: {}", from, p.block.block.getBodyCase(), hash,
                      params().member().getId());
            return null;
        }
        p.witnesses.put(view.context().getMember(from), v);
        return p;
    }

    record PendingBlock(HashedBlock block, Map<Member, Validate> witnesses, AtomicBoolean published) {
    }

    /** Leaf action Driven coupling for the Earner FSM */
    private class DriveIn implements Driven {

        @Override
        public void assemble() {
            log.debug("Starting view diadem consensus for: {} on: {}", nextViewId, params().member().getId());
            startProduction();
            assembly.start();
        }

        @Override
        public void checkpoint() {
            Thread.ofVirtual().start(Utils.wrapped(() -> {
                log.info("Generating checkpoint block on: {}", params().member().getId());
                Block ckpt = view.checkpoint();
                if (ckpt == null) {
                    log.error("Cannot generate checkpoint block on: {}", params().member().getId());
                    transitions.failed();
                    return;
                }
                var next = new HashedBlock(params().digestAlgorithm(), ckpt);
                previousBlock.set(next);
                checkpoint.set(next);
                var validation = view.generateValidation(next);
                ds.offer(validation);
                final var p = new PendingBlock(next, new HashMap<>(), new AtomicBoolean());
                pending.put(next.hash, p);
                p.witnesses.put(params().member(), validation);
                assert next.block != null;
                log.info("Produced: {} hash: {} height: {} for: {} on: {}", next.block.getBodyCase(), next.hash,
                         next.height(), getViewId(), params().member().getId());
                processPendingValidations(next, p);
                transitions.checkpointed();
            }, log));
        }

        @Override
        public void complete() {
            stop();
        }

        @Override
        public void fail() {
            stop();
            view.onFailure();
        }

        @Override
        public void reconfigure() {
            Thread.ofVirtual().start(Utils.wrapped(Producer.this::reconfigure, log));
        }

        @Override
        public void startProduction() {
            log.debug("Starting production for: {} on: {}", getViewId(), params().member().getId());
            controller.start();
            coordinator.start(params().producer().gossipDuration());
        }
    }
}
