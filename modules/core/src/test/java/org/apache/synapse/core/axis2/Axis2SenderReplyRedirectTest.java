/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *   * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.apache.synapse.core.axis2;

import junit.framework.TestCase;

/**
 * Unit tests for the WS-Addressing reply / fault redirection policy that
 * {@link Axis2Sender} applies before honoring a client-supplied wsa:ReplyTo /
 * wsa:FaultTo as the destination of a response. The policy is off by default, can be
 * turned on by an operator, and can be scoped to an allow-list of hosts once on.
 *
 * These assertions exercise the pure {@code isRedirectTargetAllowed} decision function.
 * The end-to-end behaviour (a refused redirect returning over the transport back-channel,
 * and the controls still returning over the back-channel) is covered by the booted
 * distribution test battery, since it needs a live transport.
 */
public class Axis2SenderReplyRedirectTest extends TestCase {

    /** Off by default: every target is refused, whatever it is. */
    public void testRefusedWhenDisabled() {
        assertFalse(Axis2Sender.isRedirectTargetAllowed(
                "http://replies.example.com/collect", false, ""));
        assertFalse(Axis2Sender.isRedirectTargetAllowed(
                "http://169.254.169.254/latest/meta-data/", false, ""));
        // even a host that an allow-list would permit is refused while disabled
        assertFalse(Axis2Sender.isRedirectTargetAllowed(
                "https://internal.example.com/", false, "internal.example.com"));
    }

    /** Null, malformed and schemeless / hostless targets are refused even when enabled. */
    public void testNullAndMalformedTargetsRefused() {
        assertFalse(Axis2Sender.isRedirectTargetAllowed(null, true, ""));
        assertFalse(Axis2Sender.isRedirectTargetAllowed("://not a uri", true, ""));
        assertFalse(Axis2Sender.isRedirectTargetAllowed("not-a-url", true, ""));
        // relative reference: no scheme / host to evaluate
        assertFalse(Axis2Sender.isRedirectTargetAllowed("/local/path", true, ""));
    }

    /** Only http / https destinations are ever eligible. */
    public void testOnlyHttpSchemesEligible() {
        assertFalse(Axis2Sender.isRedirectTargetAllowed("file:///etc/passwd", true, ""));
        assertFalse(Axis2Sender.isRedirectTargetAllowed("ftp://host.example.com/x", true, ""));
        assertTrue(Axis2Sender.isRedirectTargetAllowed(
                "http://replies.example.com/sink", true, ""));
        assertTrue(Axis2Sender.isRedirectTargetAllowed(
                "https://replies.example.com/sink", true, ""));
    }

    /** With a list set, only listed hosts (exact, case-insensitive, wildcard, host:port) pass. */
    public void testHostAllowListEnforced() {
        String allowed = "replies.example.com, *.partner.example.org, 10.1.2.3:8280";

        assertTrue(Axis2Sender.isRedirectTargetAllowed(
                "http://replies.example.com/sink", true, allowed));
        assertTrue(Axis2Sender.isRedirectTargetAllowed(
                "https://Replies.Example.Com/sink", true, allowed));
        assertTrue(Axis2Sender.isRedirectTargetAllowed(
                "http://svc.partner.example.org/notify", true, allowed));
        assertTrue(Axis2Sender.isRedirectTargetAllowed(
                "http://10.1.2.3:8280/reply", true, allowed));

        // host not on the list: refused even though redirection is enabled
        assertFalse(Axis2Sender.isRedirectTargetAllowed(
                "http://other.example.com/collect", true, allowed));
        // same host, different port than the host:port entry
        assertFalse(Axis2Sender.isRedirectTargetAllowed(
                "http://10.1.2.3:9999/reply", true, allowed));
        // the wildcard entry must not match a sibling of the suffix domain
        assertFalse(Axis2Sender.isRedirectTargetAllowed(
                "http://notpartner.example.org/", true, allowed));
        // the cloud metadata endpoint is not on the list
        assertFalse(Axis2Sender.isRedirectTargetAllowed(
                "http://169.254.169.254/latest/meta-data/", true, allowed));
    }

    /** An empty / unset list once enabled is the explicit legacy opt-in for any http(s) host. */
    public void testEmptyAllowListPermitsAnyHttpHostWhenEnabled() {
        assertTrue(Axis2Sender.isRedirectTargetAllowed(
                "http://anywhere.example.net/", true, null));
        assertTrue(Axis2Sender.isRedirectTargetAllowed(
                "http://anywhere.example.net/", true, "  "));
    }
}
