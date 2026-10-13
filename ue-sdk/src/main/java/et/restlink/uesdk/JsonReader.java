/*
 * Silent Auth UE SDK — Restlink (Ethiopia).
 * Device-side session-tuple collector. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.uesdk;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal JSON reader for the flat objects the SAS returns on the device-facing
 * endpoints. No third-party dependencies: the SDK ships inside a bank app and
 * every dependency is one more thing to audit.
 *
 * <p><strong>Scope is deliberately flat.</strong> The responses the SDK parses
 * ({@code devicePhoneNumberVerified}, {@code state}, {@code fallbackReason})
 * are single-level objects of scalars. Anything nested, an array, or a value
 * that is not a plain scalar makes {@link Result#get(String)} return
 * {@code null} rather than guess — a reader that invents structure is worse
 * than one that admits it did not understand the answer.</p>
 *
 * <p>Malformed input never throws: {@link Result#of(int, String)} records the
 * status and leaves every field absent, so a caller asking for
 * {@code devicePhoneNumberVerified} gets {@code null} and treats the call as
 * unverified.</p>
 */
public final class JsonReader {

    private JsonReader() {
    }

    /**
     * One parsed response.
     *
     * @param status HTTP status the response arrived with
     * @param fields flat scalars, in wire order; absent on malformed input
     */
    public record Result(int status, Map<String, String> fields) {

        public static Result of(int status, String body) {
            if (body == null || body.isBlank()) {
                return new Result(status, Map.of());
            }
            Map<String, String> fields = new LinkedHashMap<>();
            int i = skipWhitespace(body, 0);
            if (i >= body.length() || body.charAt(i) != '{') {
                return new Result(status, Map.of());
            }
            i++;
            while (true) {
                i = skipWhitespace(body, i);
                if (i >= body.length()) {
                    return new Result(status, Map.of());   // truncated: refuse
                }
                char c = body.charAt(i);
                if (c == '}') {
                    return new Result(status, fields);
                }
                if (c == ',') {
                    i++;
                    continue;
                }
                if (c != '"') {
                    return new Result(status, Map.of());   // not an object: refuse
                }
                Scan key = readString(body, i);
                if (key == null) {
                    return new Result(status, Map.of());
                }
                i = skipWhitespace(body, key.next);
                if (i >= body.length() || body.charAt(i) != ':') {
                    return new Result(status, Map.of());
                }
                i = skipWhitespace(body, i + 1);
                Scan value = readScalar(body, i);
                if (value == null) {
                    return new Result(status, Map.of());
                }
                fields.put(key.value, value.value);
                i = value.next;
            }
        }

        /** The named field, or {@code null} when absent or not understood. */
        public String get(String field) {
            return fields.get(field);
        }

        /**
         * The named field as a Boolean.
         *
         * @return {@code null} when absent, so a caller cannot confuse "false" with
         *         "the server did not say"
         */
        public Boolean bool(String field) {
            String raw = fields.get(field);
            if (raw == null) {
                return null;
            }
            if ("true".equalsIgnoreCase(raw)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(raw)) {
                return Boolean.FALSE;
            }
            return null;
        }

        /** The named field as a long, or {@code -1} when absent or unparseable. */
        public long number(String field) {
            String raw = fields.get(field);
            if (raw == null) {
                return -1L;
            }
            try {
                return Long.parseLong(raw.trim());
            } catch (NumberFormatException notANumber) {
                return -1L;
            }
        }
    }

    private static int skipWhitespace(String s, int from) {
        int i = from;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    /** One scanned token: its text and the index just past it. */
    private static final class Scan {
        final String value;
        final int next;

        Scan(String value, int next) {
            this.value = value;
            this.next = next;
        }
    }

    /** @return the unescaped value and the index past the closing quote, or null */
    private static Scan readString(String s, int from) {
        if (from >= s.length() || s.charAt(from) != '"') {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = from + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                return new Scan(sb.toString(), i + 1);
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (i + 1 >= s.length()) {
                return null;
            }
            char esc = s.charAt(++i);
            switch (esc) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> sb.append('\r');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'u' -> {
                    if (i + 4 >= s.length()) {
                        return null;
                    }
                    sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                    i += 4;
                }
                default -> sb.append(esc);
            }
        }
        return null;   // unterminated
    }

    /** Reads a scalar (string, number, boolean or null). */
    private static Scan readScalar(String s, int from) {
        if (from >= s.length()) {
            return null;
        }
        char c = s.charAt(from);
        if (c == '"') {
            return readString(s, from);
        }
        if (c == '{' || c == '[') {
            return null;   // nested: out of scope, refuse rather than truncate
        }
        int i = from;
        while (i < s.length() && ",}]\r\n\t ".indexOf(s.charAt(i)) < 0) {
            i++;
        }
        if (i <= from) {
            return null;
        }
        String token = s.substring(from, i).trim();
        // JSON null is an absent value, not the four characters "null"
        return "null".equals(token) ? new Scan(null, i) : new Scan(token, i);
    }
}
