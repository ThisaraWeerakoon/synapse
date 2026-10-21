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

package org.apache.synapse.mediators;

import junit.framework.TestCase;
import org.apache.synapse.MessageContext;
import org.apache.synapse.config.xml.XMLConfigConstants;
import org.apache.synapse.core.axis2.Axis2MessageContext;

import java.util.Map;

public class MediatorPropertyTest extends TestCase {

    /**
     * A transport scoped mediator property becomes an outgoing HTTP header, which occupies a single
     * CRLF-terminated line on the wire. A value carrying CR/LF (which a computed value can, e.g.
     * when &#13;&#10; character references in message content are decoded) cannot be one header.
     * Evaluating such a property must fail rather than emit a header that splits into several lines,
     * and no partial header must be left behind.
     */
    public void testTransportHeaderCRLFRejected() throws Exception {

        MediatorProperty property = new MediatorProperty();
        property.setName("X-Order-Ref");
        property.setValue("123\r\nX-Admin: true");
        property.setScope(XMLConfigConstants.SCOPE_TRANSPORT);

        MessageContext synCtx = TestUtils.createLightweightSynapseMessageContext("<empty/>");
        try {
            property.evaluate(synCtx);
            fail("MediatorProperty should reject a transport header value containing CR/LF");
        } catch (Exception expected) {
            // expected - a value that would split the header line must be rejected
        }

        org.apache.axis2.context.MessageContext axisCtx =
                ((Axis2MessageContext) synCtx).getAxis2MessageContext();
        Object transportHeaders = axisCtx.getProperty(
                org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS);
        assertTrue("No transport header should have been set",
                transportHeaders == null || !(transportHeaders instanceof Map)
                        || ((Map) transportHeaders).get("X-Order-Ref") == null);
    }

    /**
     * A clean transport scoped property value still becomes a transport header as before - the
     * rejection must not disturb the legitimate single-line case.
     */
    public void testTransportHeaderCleanValueAccepted() throws Exception {

        MediatorProperty property = new MediatorProperty();
        property.setName("X-Order-Ref");
        property.setValue("order-123");
        property.setScope(XMLConfigConstants.SCOPE_TRANSPORT);

        MessageContext synCtx = TestUtils.createLightweightSynapseMessageContext("<empty/>");
        property.evaluate(synCtx);

        org.apache.axis2.context.MessageContext axisCtx =
                ((Axis2MessageContext) synCtx).getAxis2MessageContext();
        Object transportHeaders = axisCtx.getProperty(
                org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS);
        if (transportHeaders == null || !(transportHeaders instanceof Map)) {
            fail("MediatorProperty transport headers not found");
        } else {
            assertEquals("order-123", ((Map) transportHeaders).get("X-Order-Ref"));
        }
    }
}
