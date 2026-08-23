/*
 * HSS / 3GPP AAA simulator for the Silent Auth SAS lab (Restlink, Ethiopia).
 * Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.testapp.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

    /**
     * Parse a flat JSON object into an ordered map. Values map to String,
     * Double, Boolean or null. Nested objects/arrays are rejected — the
     * control API only exchanges flat documents.
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
                        default -> throw new IllegalArgumentException(
                                "bad escape \\" + esc);
                    }
                    if (esc == 'u') {
                        pos += 4;
                    }
                    continue;
                }
                out.append(c);
            }
        }

        Object parseValue() {
            skipWs();
            char c = peek();
            if (c == '"') {
                return parseString();
            }
            if (c == 't') {
                expectWord("true");
                return Boolean.TRUE;
            }
            if (c == 'f') {
                expectWord("false");
                return Boolean.FALSE;
            }
            if (c == 'n') {
                expectWord("null");
                return null;
            }
            int start = pos;
            while (pos < text.length() && "-+.eE0123456789".indexOf(text.charAt(pos)) >= 0) {
                pos++;
            }
            if (start == pos) {
                throw new IllegalArgumentException("unexpected value at offset " + pos);
            }
            return Double.parseDouble(text.substring(start, pos));
        }

        private void expectWord(String word) {
            if (!text.regionMatches(pos, word, 0, word.length())) {
                throw new IllegalArgumentException("expected " + word + " at offset " + pos);
            }
            pos += word.length();
        }

        private void requireMore() {
            if (pos >= text.length()) {
                throw new IllegalArgumentException("unexpected end of JSON");
            }
        }
    }

    /** Render a list of maps as a JSON array (values pre-converted). */
    public static String arrayOf(List<Map<String, Object>> items) {
        List<String> rendered = new ArrayList<>(items.size());
        for (Map<String, Object> item : items) {
            rendered.add(objectOf(item));
        }
        return "[" + String.join(",", rendered) + "]";
    }

    /** Render one flat map as a JSON object (keys/values escaped). */
    public static String objectOf(Map<String, Object> fields) {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append(str(entry.getKey())).append(':').append(valueOf(entry.getValue()));
        }
        return out.append('}').toString();
    }

    private static String valueOf(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Boolean || value instanceof Number) {
            return value.toString();
        }
        return str(value.toString());
    }
}
