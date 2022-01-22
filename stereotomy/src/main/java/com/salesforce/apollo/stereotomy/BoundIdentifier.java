/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.salesforce.apollo.stereotomy;

import java.util.Optional;

import com.salesforce.apollo.crypto.Verifier;
import com.salesforce.apollo.stereotomy.identifier.Identifier;

/**
 * Identifier bound at a particular key state;
 * 
 * @author hal.hildebrand
 *
 */
public interface BoundIdentifier<D extends Identifier> extends KeyState {

    D getIdentifier();

    /**
     * @return the Verifier for the key state binding
     */
    Optional<Verifier> getVerifier();
}