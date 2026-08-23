/*
 * Simulated home HLR for the Silent Auth SAS lab (Restlink, Ethiopia).
 * Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.hlrsim.web;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal hand-rolled JSON: string escaping for output plus a flat-object
 * parser (string / number / boolean / null values) — enough for the control
 * API without pulling in a JSON dependency.
 */
public final class Json {

    private Json() {
    }

    /** Escape a string per RFC 8259 §7. */
    public static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /** Quote-and-escape helper. */
    public static String str(String value) {
        return "\"" + escape(value) + "\"";
    }

    /** Serialize a flat map (String / Number / Boolean / null values) as a JSON object. */
    public static String objectJson(Map<String, Object> fields) {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append(str(e.getKey())).append(':');
            Object value = e.getValue();
            if (value == null) {
                out.append("null");
            } else if (value instanceof Number || value instanceof Boolean) {
                out.append(value);
            } else {
                out.append(str(value.toString()));
            }
        }
        return out.append('}').toString();
    }

    /**
     * Parse a flat JSON object into an ordered map. Values map to String,
     * Double, Boolean or null. Nested objects/arrays are rejected.
     */
    public static Map<String, Object> parseFlatObject(String json) {
        Map<String, Object> out = new LinkedHashMap<>();
        Parser parser = new Parser(json == null ? "" : json);
        parser.skipWs();
        parser.expect('{');
        parser.skipWs();
        if (parser.peek() == '}') {
            return out;
        }
        while (true) {
            parser.skipWs();
            String key = parser.parseString();
            parser.skipWs();
            parser.expect(':');
            parser.skipWs();
            out.put(key, parser.parseValue());
            parser.skipWs();
            char c = parser.next();
            if (c == '}') {
                return out;
            }
            if (c != ',') {
                throw new IllegalArgumentException("expected ',' or '}' at offset " + parser.pos);
            }
        }
    }

    private static final class Parser {

        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        char peek() {
            requireMore();
            return text.charAt(pos);
        }

        char next() {
            requireMore();
            return text.charAt(pos++);
        }

        void skipWs() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        void expect(char c) {
            if (next() != c) {
                throw new IllegalArgumentException("expected '" + c + "' at offset " + pos);
            }
        }

        void requireMore() {
            if (pos >= text.length()) {
                throw new IllegalArgumentException("unexpected end of input");
            }
        }

        String parseString() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') {
                    return out.toString();
                }
                if (c == '\\') {
                    char esc = next();
                    switch (esc) {
                        case '"' -> out.append('"');
                        case '\\' -> out.append('\\');
                        case '/' -> out.append('/');
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r');
                        case 't' -> out.append('\t');
                        case 'u' -> out.append((char) Integer.parseInt(
                                text.substring(pos, pos + 4), 16));
                        default -> throw new IllegalArgumentException("bad escape \\" + esc);
                    }
                    if (esc == 'u') {
                        pos += 4;
                    }
                } else {
                    out.append(c);
                }
            }
        }

        Object parseValue() {
            if (peek() == '"') {
                return parseString();
            }
            int start = pos;
            while (pos < text.length() && ",}] \t\r\n".indexOf(text.charAt(pos)) < 0) {
                pos++;
            }
            String token = text.substring(start, pos).trim();
            if (token.isEmpty()) {
                throw new IllegalArgumentException("missing value at offset " + start);
            }
            if ("true".equals(token)) {
                return Boolean.TRUE;
            }
            if ("false".equals(token)) {
                return Boolean.FALSE;
            }
            if ("null".equals(token)) {
                return null;
            }
            try {
                return Double.parseDouble(token);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("bad value '" + token + "'");
            }
        }
    }
}
