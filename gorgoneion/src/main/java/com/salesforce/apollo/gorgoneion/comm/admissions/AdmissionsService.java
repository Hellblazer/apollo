/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.salesforce.apollo.gorgoneion.comm.admissions;

import com.codahale.metrics.Timer.Context;
import com.salesforce.apollo.cryptography.Digest;
import com.salesforce.apollo.gorgoneion.proto.Credentials;
import com.salesforce.apollo.gorgoneion.proto.Establishment;
import com.salesforce.apollo.gorgoneion.proto.SignedNonce;
import com.salesforce.apollo.stereotomy.event.proto.KERL_;
import io.grpc.stub.StreamObserver;

/**
 * @author hal.hildebrand
 */
public interface AdmissionsService {

    void apply(KERL_ application, Digest from, StreamObserver<SignedNonce> responseObserver, Context timer);

    void register(Credentials request, Digest from, StreamObserver<Establishment> responseObserver, Context timer);

}
