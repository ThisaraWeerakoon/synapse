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

/**
 * Shared helpers for mediators that assign transport (HTTP) headers from message-derived values.
 */
public final class TransportHeaderUtils {

    private TransportHeaderUtils() {
    }

    /**
     * Reports whether a string contains a character that cannot appear in a well-formed HTTP
     * header name or value: a carriage return, a line feed, or a NUL. An HTTP header occupies a
     * single line terminated by CRLF, so a CR or LF embedded in a name or value would be written
     * to the wire as a line break and split one configured header into several; a NUL is likewise
     * not a valid header character. Callers assigning a transport header from a computed value use
     * this to keep one configured header assignment producing exactly one header.
     *
     * @param s the header name or value to check (may be {@code null})
     * @return {@code true} if {@code s} contains CR, LF or NUL
     */
    public static boolean containsCRLFOrNul(String s) {
        return s != null
                && (s.indexOf('\r') != -1 || s.indexOf('\n') != -1 || s.indexOf('\u0000') != -1);
    }
}
