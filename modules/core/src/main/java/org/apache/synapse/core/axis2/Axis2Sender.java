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

import org.apache.axiom.util.UIDGenerator;
import org.apache.axis2.AxisFault;
import org.apache.axis2.Constants;
import org.apache.axis2.addressing.AddressingConstants;
import org.apache.axis2.addressing.AddressingHelper;
import org.apache.axis2.addressing.EndpointReference;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.engine.AxisEngine;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.SynapseConstants;
import org.apache.synapse.SynapseException;
import org.apache.synapse.aspects.statistics.StatisticsReporter;
import org.apache.synapse.config.SynapsePropertiesLoader;
import org.apache.synapse.endpoints.EndpointDefinition;
import org.apache.synapse.transport.nhttp.NhttpConstants;
import org.apache.synapse.util.MessageHelper;
import org.apache.synapse.util.POXUtils;

import java.net.MalformedURLException;
import java.net.URL;

/**
 * This class helps the Axis2SynapseEnvironment implement the send method
 */
public class Axis2Sender {

    private static final Log log = LogFactory.getLog(Axis2Sender.class);

    /**
     * synapse.properties flag controlling whether a client-supplied non-anonymous
     * wsa:ReplyTo / wsa:FaultTo is honored as the destination of the response. When a
     * client chooses that destination, Synapse delivers the (largely client-shaped)
     * response there using its own network identity. That is only appropriate for
     * deployments that deliberately use WS-Addressing dual-channel reply routing, so it
     * is disabled by default: replies go back over the transport back-channel unless an
     * operator explicitly opts in.
     */
    static final String REPLY_REDIRECT_ENABLED_PROP =
            "synapse.addressing.reply.redirect.enabled";

    /**
     * synapse.properties comma-separated list of hosts (host or host:port entries;
     * "*.example.com" suffix wildcards supported) that a client-supplied wsa:ReplyTo /
     * wsa:FaultTo may point at, once {@link #REPLY_REDIRECT_ENABLED_PROP} is enabled. An
     * empty or unset list permits any http/https destination (the legacy behavior, opted
     * into explicitly by enabling redirection without scoping it).
     */
    static final String REPLY_REDIRECT_ALLOWED_HOSTS_PROP =
            "synapse.addressing.reply.redirect.allowed.hosts";

    /**
     * Send a message out from the Synapse engine to an external service
     * @param endpoint the endpoint definition where the message should be sent
     * @param synapseInMessageContext the Synapse message context
     */
    public static void sendOn(EndpointDefinition endpoint,
        org.apache.synapse.MessageContext synapseInMessageContext) {

        try {
            Axis2FlexibleMEPClient.send(
                // The endpoint where we are sending to
                endpoint,
                // The Axis2 Message context of the Synapse MC
                synapseInMessageContext);

            if (synapseInMessageContext.isResponse()) {
                // report stats for any component at response sending check point
                StatisticsReporter.reportForAllOnResponseSent(synapseInMessageContext);
            }

        } catch (Exception e) {
            handleException("Unexpected error during sending message out", e);
        }
    }

    /**
     * Send a response back to a client of Synapse
     * @param smc the Synapse message context sent as the response
     */
    public static void sendBack(org.apache.synapse.MessageContext smc) {

        MessageContext messageContext = ((Axis2MessageContext) smc).getAxis2MessageContext();

        // if this is a dummy 202 Accepted message meant only for the http/s transports
        // prevent it from going into any other transport sender
        if (messageContext.isPropertyTrue(NhttpConstants.SC_ACCEPTED) &&
            messageContext.getTransportOut() != null &&
            !messageContext.getTransportOut().getName().startsWith(Constants.TRANSPORT_HTTP)) {
                return;
        }

        // fault processing code
        if (messageContext.isDoingREST() && messageContext.isFault()) {
            POXUtils.convertSOAPFaultToPOX(messageContext);
        }

        try {
            messageContext.setProperty(SynapseConstants.ISRESPONSE_PROPERTY, Boolean.TRUE);
            // check if addressing is already engaged for this message.
            // if engaged we should use the addressing enabled Configuration context.            

            if (AddressingHelper.isReplyRedirected(messageContext) &&
                    !messageContext.getReplyTo().hasNoneAddress()) {

                if (isRedirectionPermitted(messageContext.getReplyTo(), "wsa:ReplyTo")) {
                    messageContext.setTo(messageContext.getReplyTo());
                    messageContext.setReplyTo(null);
                    messageContext.setWSAAction("");
                    messageContext.setSoapAction("");
                    messageContext.setProperty(
                            NhttpConstants.IGNORE_SC_ACCEPTED, Constants.VALUE_TRUE);
                    messageContext.setProperty(
                            AddressingConstants.DISABLE_ADDRESSING_FOR_OUT_MESSAGES, Boolean.FALSE);
                } else {
                    // redirection not permitted: deliver over the transport back-channel.
                    clearReplyRedirectTarget(messageContext);
                }
            }

            if (messageContext.getEnvelope().hasFault()
                    && AddressingHelper.isFaultRedirected(messageContext)
                    && !messageContext.getFaultTo().hasNoneAddress()) {

                if (isRedirectionPermitted(messageContext.getFaultTo(), "wsa:FaultTo")) {
                    messageContext.setTo(messageContext.getFaultTo());
                    messageContext.setFaultTo(null);
                    messageContext.setWSAAction("");
                    messageContext.setSoapAction("");
                    messageContext.setProperty(
                            NhttpConstants.IGNORE_SC_ACCEPTED, Constants.VALUE_TRUE);
                    messageContext.setProperty(
                            AddressingConstants.DISABLE_ADDRESSING_FOR_OUT_MESSAGES, Boolean.FALSE);
                } else {
                    // redirection not permitted: deliver the fault over the transport back-channel.
                    clearFaultRedirectTarget(messageContext);
                }
            }

            String preserveAddressingProperty = (String) smc.getProperty(
                    SynapseConstants.PRESERVE_WS_ADDRESSING);
            if (preserveAddressingProperty != null &&
                    Boolean.parseBoolean(preserveAddressingProperty)) {
                 messageContext.setMessageID(smc.getMessageID());
             } else {
                 MessageHelper.removeAddressingHeaders(messageContext);
                 messageContext.setMessageID(UIDGenerator.generateURNString());
             }

            // determine weather we need to preserve the processed headers
            String preserveHeaderProperty = (String) smc.getProperty(
                            SynapseConstants.PRESERVE_PROCESSED_HEADERS);
            if (preserveHeaderProperty == null || !Boolean.parseBoolean(preserveHeaderProperty)) {
                // remove the processed headers
                MessageHelper.removeProcessedHeaders(messageContext,
                        (preserveAddressingProperty != null &&
                                Boolean.parseBoolean(preserveAddressingProperty)));
            }

            // temporary workaround for https://issues.apache.org/jira/browse/WSCOMMONS-197
            if (messageContext.isEngaged(SynapseConstants.SECURITY_MODULE_NAME)) {
                messageContext.getEnvelope().getOrCreateHeader();
            }

            Axis2FlexibleMEPClient.clearSecurityProperties(messageContext.getOptions());

           // report stats for any component at response sending check point
            StatisticsReporter.reportForAllOnResponseSent(smc);

            AxisEngine.send(messageContext);

        } catch (AxisFault e) {
            handleException("Unexpected error sending message back", e);
        }
    }

    /**
     * Clears a client-supplied wsa:ReplyTo that is not permitted as a response
     * destination, so the response is returned over the transport back-channel. Both the
     * ReplyTo and the To are reset: the To may already have been pointed at the
     * ReplyTo address by an earlier mediator (e.g. a fault sequence), and leaving it set
     * would still drive the outbound connection there.
     *
     * @param messageContext the response message context
     */
    private static void clearReplyRedirectTarget(MessageContext messageContext) {
        messageContext.setReplyTo(null);
        messageContext.setTo(null);
    }

    /**
     * Clears a client-supplied wsa:FaultTo that is not permitted as a response
     * destination, so the fault is returned over the transport back-channel. See
     * {@link #clearReplyRedirectTarget(MessageContext)} for why the To is also reset.
     *
     * @param messageContext the response message context
     */
    private static void clearFaultRedirectTarget(MessageContext messageContext) {
        messageContext.setFaultTo(null);
        messageContext.setTo(null);
    }

    /**
     * Decides whether a client-supplied redirection address (wsa:ReplyTo or wsa:FaultTo)
     * may be honored as the response destination, according to the operator policy in
     * synapse.properties. When it is not permitted a warning is logged and the caller
     * delivers the response over the transport back-channel instead.
     *
     * @param epr        the client-supplied endpoint reference
     * @param headerName the WS-Addressing header being evaluated (for logging)
     * @return true if the redirection is explicitly permitted by the operator
     */
    private static boolean isRedirectionPermitted(EndpointReference epr, String headerName) {
        String address = epr != null ? epr.getAddress() : null;
        boolean enabled = SynapsePropertiesLoader.getBooleanProperty(
                REPLY_REDIRECT_ENABLED_PROP, Boolean.FALSE);
        String allowedHosts = SynapsePropertiesLoader.getPropertyValue(
                REPLY_REDIRECT_ALLOWED_HOSTS_PROP, "");
        if (isRedirectTargetAllowed(address, enabled, allowedHosts)) {
            return true;
        }
        if (log.isDebugEnabled()) {
            log.debug("Not honoring the client supplied " + headerName + " address : " + address +
                    "; returning the response over the transport back-channel. To honor it, set '" +
                    REPLY_REDIRECT_ENABLED_PROP + "=true' in synapse.properties, and optionally " +
                    "scope the permitted destinations with '" + REPLY_REDIRECT_ALLOWED_HOSTS_PROP + "'");
        }
        return false;
    }

    /**
     * Pure policy check for a redirection target. Only http and https destinations with a
     * host component are ever eligible; the optional allow-list further restricts the
     * destination host (and port, for host:port entries). The target is parsed with the
     * same {@link URL} parser the pass-through sender uses to open the connection, so the
     * host evaluated here is the host the connection would use.
     *
     * @param address      the redirection target address
     * @param enabled      whether the operator enabled reply redirection
     * @param allowedHosts comma-separated host allow-list (empty permits any http/https host)
     * @return true if the address is an acceptable redirection target
     */
    static boolean isRedirectTargetAllowed(String address, boolean enabled, String allowedHosts) {
        if (!enabled || address == null) {
            return false;
        }
        URL url;
        try {
            url = new URL(address.trim());
        } catch (MalformedURLException e) {
            return false;
        }
        String protocol = url.getProtocol();
        String host = url.getHost();
        if (protocol == null || host == null || host.length() == 0) {
            return false;
        }
        if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
            return false;
        }
        if (allowedHosts == null || allowedHosts.trim().length() == 0) {
            return true;
        }
        String hostPort = url.getPort() != -1 ? host + ":" + url.getPort() : host;
        for (String entry : allowedHosts.split(",")) {
            entry = entry.trim();
            if (entry.length() == 0) {
                continue;
            }
            if (entry.equalsIgnoreCase(host) || entry.equalsIgnoreCase(hostPort)) {
                return true;
            }
            if (entry.startsWith("*.") && host.length() > entry.length() - 2 &&
                    host.toLowerCase().endsWith(entry.substring(1).toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private static void handleException(String msg, Exception e) {
        log.error(msg, e);
        throw new SynapseException(msg, e);
    }
}
