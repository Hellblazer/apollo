/*
 * Copyright 2019, salesforce.com
 * All Rights Reserved
 * Company Confidential
 */
package com.salesforce.apollo.ghost.communications;

import java.io.IOException;
import java.util.List;

import org.apache.avro.AvroRemoteException;
import org.apache.avro.ipc.Transceiver;
import org.apache.avro.ipc.specific.SpecificRequestor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.salesforce.apollo.avro.Apollo;
import com.salesforce.apollo.avro.Entry;
import com.salesforce.apollo.avro.GhostUpdate;
import com.salesforce.apollo.avro.Interval;
import com.salesforce.apollo.avro.HASH;
import com.salesforce.apollo.fireflies.Member;
import com.salesforce.apollo.fireflies.communications.CommonClientCommunications;
import com.salesforce.apollo.protocols.SpaceGhost;

/**
 * @author hal.hildebrand
 * @since 220
 */
public class GhostClientCommunications extends CommonClientCommunications implements SpaceGhost {
    private static final Logger log = LoggerFactory.getLogger(GhostClientCommunications.class);
    private final Apollo client;
    private final Transceiver transceiver;

    public GhostClientCommunications(Transceiver transceiver, Member member) throws AvroRemoteException {
        super(member);
        this.transceiver = transceiver;
        try {
            client = SpecificRequestor.getClient(Apollo.class, transceiver);
        } catch (IOException e) {
            throw new AvroRemoteException("Cannot create proxy rpc client to: " + member + " : " + transceiver, e);
        }
    }

    @Override
    public void close() {
        try {
            transceiver.close();
        } catch (IOException e) {
            log.trace("error closing communications with " + member, e);
        }
    }

    @Override
    public Entry get(HASH entry) throws AvroRemoteException {
        return client.get(entry);
    }

    @Override
    public GhostUpdate ghostGossip(List<Interval> intervals, List<HASH> digests) throws AvroRemoteException {
        return client.ghostGossip(intervals, digests);
    }

    @Override
    public void gUpdate(List<Entry> updates) {
        client.gUpdate(updates);
    }

    @Override
    public void put(Entry value) {
        client.put(value);
    }

}
