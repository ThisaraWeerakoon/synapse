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

public class TransportHeaderUtilsTest extends TestCase {

    public void testNullAndCleanValuesAreValid() {
        assertFalse(TransportHeaderUtils.containsCRLFOrNul(null));
        assertFalse(TransportHeaderUtils.containsCRLFOrNul(""));
        assertFalse(TransportHeaderUtils.containsCRLFOrNul("order-123"));
        assertFalse(TransportHeaderUtils.containsCRLFOrNul("text/xml; charset=UTF-8"));
        // tab and surrounding spaces are legal inside a header value
        assertFalse(TransportHeaderUtils.containsCRLFOrNul("a b\tc"));
    }

    public void testControlCharactersAreDetected() {
        assertTrue(TransportHeaderUtils.containsCRLFOrNul("123\r"));
        assertTrue(TransportHeaderUtils.containsCRLFOrNul("123\n"));
        assertTrue(TransportHeaderUtils.containsCRLFOrNul("123\u0000"));
        assertTrue(TransportHeaderUtils.containsCRLFOrNul("123\r\nX-Admin: true"));
        assertTrue(TransportHeaderUtils.containsCRLFOrNul("\n"));
    }
}
