/*
 * Copyright 2012, 2014 the original author or authors.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.neoscoinj.uri;

import org.neoscoinj.core.Address;
import org.neoscoinj.params.MainNetParams;
import org.neoscoinj.params.TestNet3Params;
import com.google.common.collect.ImmutableList;
import org.junit.Test;

import java.io.UnsupportedEncodingException;

import static org.neoscoinj.core.Coin.*;
import static org.junit.Assert.*;

public class NeoscoinURITest {
    private NeoscoinURI testObject = null;

    private static final String MAINNET_GOOD_ADDRESS = "1KzTSfqjF2iKCduwz59nv2uqh1W2JsTxZH";

    @Test
    public void testConvertToNeoscoinURI() throws Exception {
        Address goodAddress = new Address(MainNetParams.get(), MAINNET_GOOD_ADDRESS);
        
        // simple example
        assertEquals("neoscoin:" + MAINNET_GOOD_ADDRESS + "?amount=12.34&label=Hello&message=AMessage", NeoscoinURI.convertToNeoscoinURI(goodAddress, parseCoin("12.34"), "Hello", "AMessage"));
        
        // example with spaces, ampersand and plus
        assertEquals("neoscoin:" + MAINNET_GOOD_ADDRESS + "?amount=12.34&label=Hello%20World&message=Mess%20%26%20age%20%2B%20hope", NeoscoinURI.convertToNeoscoinURI(goodAddress, parseCoin("12.34"), "Hello World", "Mess & age + hope"));

        // no amount, label present, message present
        assertEquals("neoscoin:" + MAINNET_GOOD_ADDRESS + "?label=Hello&message=glory", NeoscoinURI.convertToNeoscoinURI(goodAddress, null, "Hello", "glory"));
        
        // amount present, no label, message present
        assertEquals("neoscoin:" + MAINNET_GOOD_ADDRESS + "?amount=0.1&message=glory", NeoscoinURI.convertToNeoscoinURI(goodAddress, parseCoin("0.1"), null, "glory"));
        assertEquals("neoscoin:" + MAINNET_GOOD_ADDRESS + "?amount=0.1&message=glory", NeoscoinURI.convertToNeoscoinURI(goodAddress, parseCoin("0.1"), "", "glory"));

        // amount present, label present, no message
        assertEquals("neoscoin:" + MAINNET_GOOD_ADDRESS + "?amount=12.34&label=Hello", NeoscoinURI.convertToNeoscoinURI(goodAddress, parseCoin("12.34"), "Hello", null));
        assertEquals("neoscoin:" + MAINNET_GOOD_ADDRESS + "?amount=12.34&label=Hello", NeoscoinURI.convertToNeoscoinURI(goodAddress, parseCoin("12.34"), "Hello", ""));
              
        // amount present, no label, no message
        assertEquals("neoscoin:" + MAINNET_GOOD_ADDRESS + "?amount=1000", NeoscoinURI.convertToNeoscoinURI(goodAddress, parseCoin("1000"), null, null));
        assertEquals("neoscoin:" + MAINNET_GOOD_ADDRESS + "?amount=1000", NeoscoinURI.convertToNeoscoinURI(goodAddress, parseCoin("1000"), "", ""));
        
        // no amount, label present, no message
        assertEquals("neoscoin:" + MAINNET_GOOD_ADDRESS + "?label=Hello", NeoscoinURI.convertToNeoscoinURI(goodAddress, null, "Hello", null));
        
        // no amount, no label, message present
        assertEquals("neoscoin:" + MAINNET_GOOD_ADDRESS + "?message=Agatha", NeoscoinURI.convertToNeoscoinURI(goodAddress, null, null, "Agatha"));
        assertEquals("neoscoin:" + MAINNET_GOOD_ADDRESS + "?message=Agatha", NeoscoinURI.convertToNeoscoinURI(goodAddress, null, "", "Agatha"));
      
        // no amount, no label, no message
        assertEquals("neoscoin:" + MAINNET_GOOD_ADDRESS, NeoscoinURI.convertToNeoscoinURI(goodAddress, null, null, null));
        assertEquals("neoscoin:" + MAINNET_GOOD_ADDRESS, NeoscoinURI.convertToNeoscoinURI(goodAddress, null, "", ""));
    }

    @Test
    public void testGood_Simple() throws NeoscoinURIParseException {
        testObject = new NeoscoinURI(MainNetParams.get(), NeoscoinURI.BITCOIN_SCHEME + ":" + MAINNET_GOOD_ADDRESS);
        assertNotNull(testObject);
        assertNull("Unexpected amount", testObject.getAmount());
        assertNull("Unexpected label", testObject.getLabel());
        assertEquals("Unexpected label", 20, testObject.getAddress().getHash160().length);
    }

    /**
     * Test a broken URI (bad scheme)
     */
    @Test
    public void testBad_Scheme() {
        try {
            testObject = new NeoscoinURI(MainNetParams.get(), "blimpcoin:" + MAINNET_GOOD_ADDRESS);
            fail("Expecting NeoscoinURIParseException");
        } catch (NeoscoinURIParseException e) {
        }
    }

    /**
     * Test a broken URI (bad syntax)
     */
    @Test
    public void testBad_BadSyntax() {
        // Various illegal characters
        try {
            testObject = new NeoscoinURI(MainNetParams.get(), NeoscoinURI.BITCOIN_SCHEME + "|" + MAINNET_GOOD_ADDRESS);
            fail("Expecting NeoscoinURIParseException");
        } catch (NeoscoinURIParseException e) {
            assertTrue(e.getMessage().contains("Bad URI syntax"));
        }

        try {
            testObject = new NeoscoinURI(MainNetParams.get(), NeoscoinURI.BITCOIN_SCHEME + ":" + MAINNET_GOOD_ADDRESS + "\\");
            fail("Expecting NeoscoinURIParseException");
        } catch (NeoscoinURIParseException e) {
            assertTrue(e.getMessage().contains("Bad URI syntax"));
        }

        // Separator without field
        try {
            testObject = new NeoscoinURI(MainNetParams.get(), NeoscoinURI.BITCOIN_SCHEME + ":");
            fail("Expecting NeoscoinURIParseException");
        } catch (NeoscoinURIParseException e) {
            assertTrue(e.getMessage().contains("Bad URI syntax"));
        }
    }

    /**
     * Test a broken URI (missing address)
     */
    @Test
    public void testBad_Address() {
        try {
            testObject = new NeoscoinURI(MainNetParams.get(), NeoscoinURI.BITCOIN_SCHEME);
            fail("Expecting NeoscoinURIParseException");
        } catch (NeoscoinURIParseException e) {
        }
    }

    /**
     * Test a broken URI (bad address type)
     */
    @Test
    public void testBad_IncorrectAddressType() {
        try {
            testObject = new NeoscoinURI(TestNet3Params.get(), NeoscoinURI.BITCOIN_SCHEME + ":" + MAINNET_GOOD_ADDRESS);
            fail("Expecting NeoscoinURIParseException");
        } catch (NeoscoinURIParseException e) {
            assertTrue(e.getMessage().contains("Bad address"));
        }
    }

    /**
     * Handles a simple amount
     * 
     * @throws NeoscoinURIParseException
     *             If something goes wrong
     */
    @Test
    public void testGood_Amount() throws NeoscoinURIParseException {
        // Test the decimal parsing
        testObject = new NeoscoinURI(MainNetParams.get(), NeoscoinURI.BITCOIN_SCHEME + ":" + MAINNET_GOOD_ADDRESS
                + "?amount=6543210.12345678");
        assertEquals("654321012345678", testObject.getAmount().toString());

        // Test the decimal parsing
        testObject = new NeoscoinURI(MainNetParams.get(), NeoscoinURI.BITCOIN_SCHEME + ":" + MAINNET_GOOD_ADDRESS
                + "?amount=.12345678");
        assertEquals("12345678", testObject.getAmount().toString());

        // Test the integer parsing
        testObject = new NeoscoinURI(MainNetParams.get(), NeoscoinURI.BITCOIN_SCHEME + ":" + MAINNET_GOOD_ADDRESS
                + "?amount=6543210");
        assertEquals("654321000000000", testObject.getAmount().toString());
    }

    /**
     * Handles a simple label
     * 
     * @throws NeoscoinURIParseException
     *             If something goes wrong
     */
    @Test
    public void testGood_Label() throws NeoscoinURIParseException {
        testObject = new NeoscoinURI(MainNetParams.get(), NeoscoinURI.BITCOIN_SCHEME + ":" + MAINNET_GOOD_ADDRESS
                + "?label=Hello%20World");
        assertEquals("Hello World", testObject.getLabel());
    }

    /**
     * Handles a simple label with an embedded ampersand and plus
     * 
     * @throws NeoscoinURIParseException
     *             If something goes wrong
     * @throws UnsupportedEncodingException 
     */
    @Test
    public void testGood_LabelWithAmpersandAndPlus() throws Exception {
        String testString = "Hello Earth & Mars + Venus";
        String encodedLabel = NeoscoinURI.encodeURLString(testString);
        testObject = new NeoscoinURI(MainNetParams.get(), NeoscoinURI.BITCOIN_SCHEME + ":" + MAINNET_GOOD_ADDRESS + "?label="
                + encodedLabel);
        assertEquals(testString, testObject.getLabel());
    }

    /**
     * Handles a Russian label (Unicode test)
     * 
     * @throws NeoscoinURIParseException
     *             If something goes wrong
     * @throws UnsupportedEncodingException 
     */
    @Test
    public void testGood_LabelWithRussian() throws Exception {
        // Moscow in Russian in Cyrillic
        String moscowString = "\u041c\u043e\u0441\u043a\u0432\u0430";
        String encodedLabel = NeoscoinURI.encodeURLString(moscowString); 
        testObject = new NeoscoinURI(MainNetParams.get(), NeoscoinURI.BITCOIN_SCHEME + ":" + MAINNET_GOOD_ADDRESS + "?label="
                + encodedLabel);
        assertEquals(moscowString, testObject.getLabel());
    }

    /**
     * Handles a simple message
     * 
     * @throws NeoscoinURIParseException
     *             If something goes wrong
     */
    @Test
    public void testGood_Message() throws NeoscoinURIParseException {
        testObject = new NeoscoinURI(MainNetParams.get(), NeoscoinURI.BITCOIN_SCHEME + ":" + MAINNET_GOOD_ADDRESS
                + "?message=Hello%20World");
        assertEquals("Hello World", testObject.getMessage());
    }

    /**
     * Handles various well-formed combinations
     * 
     * @throws NeoscoinURIParseException
     *             If something goes wrong
     */
    @Test
    public void testGood_Combinations() throws NeoscoinURIParseException {
        testObject = new NeoscoinURI(MainNetParams.get(), NeoscoinURI.BITCOIN_SCHEME + ":" + MAINNET_GOOD_ADDRESS
                + "?amount=6543210&label=Hello%20World&message=Be%20well");
        assertEquals(
                "NeoscoinURI['amount'='654321000000000','label'='Hello World','message'='Be well','address'='1KzTSfqjF2iKCduwz59nv2uqh1W2JsTxZH']",
                testObject.toString());
    }

    /**
     * Handles a badly formatted amount field
     * 
     * @throws NeoscoinURIParseException
     *             If something goes wrong
     */
    @Test
    public void testBad_Amount() throws NeoscoinURIParseException {
        // Missing
        try {
            testObject = new NeoscoinURI(MainNetParams.get(), NeoscoinURI.BITCOIN_SCHEME + ":" + MAINNET_GOOD_ADDRESS
                    + "?amount=");
            fail("Expecting NeoscoinURIParseException");
        } catch (NeoscoinURIParseException e) {
            assertTrue(e.getMessage().contains("amount"));
        }

        // Non-decimal (BIP 21)
        try {
            testObject = new NeoscoinURI(MainNetParams.get(), NeoscoinURI.BITCOIN_SCHEME + ":" + MAINNET_GOOD_ADDRESS
                    + "?amount=12X4");
            fail("Expecting NeoscoinURIParseException");
        } catch (NeoscoinURIParseException e) {
            assertTrue(e.getMessage().contains("amount"));
        }
    }

    @Test
    public void testEmpty_Label() throws NeoscoinURIParseException {
        assertNull(new NeoscoinURI(MainNetParams.get(), NeoscoinURI.BITCOIN_SCHEME + ":" + MAINNET_GOOD_ADDRESS
                + "?label=").getLabel());
    }

    @Test
    public void testEmpty_Message() throws NeoscoinURIParseException {
        assertNull(new NeoscoinURI(MainNetParams.get(), NeoscoinURI.BITCOIN_SCHEME + ":" + MAINNET_GOOD_ADDRESS
                + "?message=").getMessage());
    }

    /**
     * Handles duplicated fields (sneaky address overwrite attack)
     * 
     * @throws NeoscoinURIParseException
     *             If something goes wrong
     */
    @Test
    public void testBad_Duplicated() throws NeoscoinURIParseException {
        try {
            testObject = new NeoscoinURI(MainNetParams.get(), NeoscoinURI.BITCOIN_SCHEME + ":" + MAINNET_GOOD_ADDRESS
                    + "?address=aardvark");
            fail("Expecting NeoscoinURIParseException");
        } catch (NeoscoinURIParseException e) {
            assertTrue(e.getMessage().contains("address"));
        }
    }

    @Test
    public void testGood_ManyEquals() throws NeoscoinURIParseException {
        assertEquals("aardvark=zebra", new NeoscoinURI(MainNetParams.get(), NeoscoinURI.BITCOIN_SCHEME + ":"
                + MAINNET_GOOD_ADDRESS + "?label=aardvark=zebra").getLabel());
    }
    
    /**
     * Handles unknown fields (required and not required)
     * 
     * @throws NeoscoinURIParseException
     *             If something goes wrong
     */
    @Test
    public void testUnknown() throws NeoscoinURIParseException {
        // Unknown not required field
        testObject = new NeoscoinURI(MainNetParams.get(), NeoscoinURI.BITCOIN_SCHEME + ":" + MAINNET_GOOD_ADDRESS
                + "?aardvark=true");
        assertEquals("NeoscoinURI['aardvark'='true','address'='1KzTSfqjF2iKCduwz59nv2uqh1W2JsTxZH']", testObject.toString());

        assertEquals("true", (String) testObject.getParameterByName("aardvark"));

        // Unknown not required field (isolated)
        try {
            testObject = new NeoscoinURI(MainNetParams.get(), NeoscoinURI.BITCOIN_SCHEME + ":" + MAINNET_GOOD_ADDRESS
                    + "?aardvark");
            fail("Expecting NeoscoinURIParseException");
        } catch (NeoscoinURIParseException e) {
            assertTrue(e.getMessage().contains("no separator"));
        }

        // Unknown and required field
        try {
            testObject = new NeoscoinURI(MainNetParams.get(), NeoscoinURI.BITCOIN_SCHEME + ":" + MAINNET_GOOD_ADDRESS
                    + "?req-aardvark=true");
            fail("Expecting NeoscoinURIParseException");
        } catch (NeoscoinURIParseException e) {
            assertTrue(e.getMessage().contains("req-aardvark"));
        }
    }

    @Test
    public void brokenURIs() throws NeoscoinURIParseException {
        // Check we can parse the incorrectly formatted URIs produced by blockchain.info and its iPhone app.
        String str = "neoscoin://1KzTSfqjF2iKCduwz59nv2uqh1W2JsTxZH?amount=0.01000000";
        NeoscoinURI uri = new NeoscoinURI(str);
        assertEquals("1KzTSfqjF2iKCduwz59nv2uqh1W2JsTxZH", uri.getAddress().toString());
        assertEquals(CENT, uri.getAmount());
    }

    @Test(expected = NeoscoinURIParseException.class)
    public void testBad_AmountTooPrecise() throws NeoscoinURIParseException {
        new NeoscoinURI(MainNetParams.get(), NeoscoinURI.BITCOIN_SCHEME + ":" + MAINNET_GOOD_ADDRESS
                + "?amount=0.123456789");
    }

    @Test(expected = NeoscoinURIParseException.class)
    public void testBad_NegativeAmount() throws NeoscoinURIParseException {
        new NeoscoinURI(MainNetParams.get(), NeoscoinURI.BITCOIN_SCHEME + ":" + MAINNET_GOOD_ADDRESS
                + "?amount=-1");
    }

    @Test(expected = NeoscoinURIParseException.class)
    public void testBad_TooLargeAmount() throws NeoscoinURIParseException {
        new NeoscoinURI(MainNetParams.get(), NeoscoinURI.BITCOIN_SCHEME + ":" + MAINNET_GOOD_ADDRESS
                + "?amount=100000000");
    }

    @Test
    public void testPaymentProtocolReq() throws Exception {
        // Non-backwards compatible form ...
        NeoscoinURI uri = new NeoscoinURI(TestNet3Params.get(), "neoscoin:?r=https%3A%2F%2Fneoscoincore.org%2F%7Egavin%2Ff.php%3Fh%3Db0f02e7cea67f168e25ec9b9f9d584f9");
        assertEquals("https://neoscoincore.org/~gavin/f.php?h=b0f02e7cea67f168e25ec9b9f9d584f9", uri.getPaymentRequestUrl());
        assertEquals(ImmutableList.of("https://neoscoincore.org/~gavin/f.php?h=b0f02e7cea67f168e25ec9b9f9d584f9"),
                uri.getPaymentRequestUrls());
        assertNull(uri.getAddress());
    }

    @Test
    public void testMultiplePaymentProtocolReq() throws Exception {
        NeoscoinURI uri = new NeoscoinURI(MainNetParams.get(),
                "neoscoin:?r=https%3A%2F%2Fneoscoincore.org%2F%7Egavin&r1=bt:112233445566");
        assertEquals(ImmutableList.of("bt:112233445566", "https://neoscoincore.org/~gavin"), uri.getPaymentRequestUrls());
        assertEquals("https://neoscoincore.org/~gavin", uri.getPaymentRequestUrl());
    }

    @Test
    public void testNoPaymentProtocolReq() throws Exception {
        NeoscoinURI uri = new NeoscoinURI(MainNetParams.get(), "neoscoin:" + MAINNET_GOOD_ADDRESS);
        assertNull(uri.getPaymentRequestUrl());
        assertEquals(ImmutableList.of(), uri.getPaymentRequestUrls());
        assertNotNull(uri.getAddress());
    }

    @Test
    public void testUnescapedPaymentProtocolReq() throws Exception {
        NeoscoinURI uri = new NeoscoinURI(TestNet3Params.get(),
                "neoscoin:?r=https://merchant.com/pay.php?h%3D2a8628fc2fbe");
        assertEquals("https://merchant.com/pay.php?h=2a8628fc2fbe", uri.getPaymentRequestUrl());
        assertEquals(ImmutableList.of("https://merchant.com/pay.php?h=2a8628fc2fbe"), uri.getPaymentRequestUrls());
        assertNull(uri.getAddress());
    }
}
