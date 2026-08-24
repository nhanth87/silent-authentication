/*
 * Silent Auth UE SDK (Android) — Restlink (Ethiopia).
 * Tests: RFC 8259 string escaping.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.uesdk.android;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonTest {

    @Test
    void plainStringPassesThrough() {
        assertEquals("\"+251911111111\"", Json.quote("+251911111111"));
    }

    @Test
    void escapesQuoteAndBackslash() {
        assertEquals("\"a\\\"b\"", Json.quote("a\"b"));
        assertEquals("\"a\\\\b\"", Json.quote("a\\b"));
    }

    @Test
    void escapesNamedControlChars() {
        assertEquals("\"\\n\"", Json.quote("\n"));
        assertEquals("\"\\r\"", Json.quote("\r"));
        assertEquals("\"\\t\"", Json.quote("\t"));
        assertEquals("\"\\b\"", Json.quote("\b"));
        assertEquals("\"\\f\"", Json.quote("\f"));
    }

    @Test
    void escapesOtherControlCharsAsUnicode() {
        assertEquals("\"\\u0000\"", Json.quote("\u0000"));
        assertEquals("\"\\u001f\"", Json.quote("\u001F"));
        assertEquals("\"\\u0001\"", Json.quote("\u0001"));
    }

    @Test
    void leavesPrintableUnicodeRaw() {
        assertEquals("\"ትራንስ\"", Json.quote("ትራንስ"));
    }

    @Test
    void escapeWithoutQuotesHandlesNull() {
        assertEquals("", Json.escape(null));
    }
}
