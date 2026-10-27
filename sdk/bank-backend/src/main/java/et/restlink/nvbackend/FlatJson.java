/*
 * CAMARA NV v2 bank backend SDK — Restlink (Ethiopia).
 * Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.nvbackend;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads the flat JSON objects the operator answers with (token response, CIBA
 * response, NV answers, CAMARA and OAuth errors): one level of string, number,
 * boolean or null members. Scalars come back as their text; a nested value is
 * skipped, not guessed. Anything that is not an object throws.
 */
final class FlatJson {

    private final String s;
    private int i;

    private FlatJson(String s) {
        this.s = s;
    }

    static Map<String, String> parse(String json) {
        if (json == null) {
            throw new IllegalArgumentException("no JSON");
        }
        FlatJson p = new FlatJson(json);
        Map<String, String> out = new LinkedHashMap<>();
        p.ws();
        p.expect('{');
        p.ws();
        if (p.peek() == '}') {
            return out;
        }
        while (true) {
            p.ws();
            String key = p.string();
            p.ws();
            p.expect(':');
            p.ws();
            char c = p.peek();
            if (c == '"') {
                out.put(key, p.string());
            } else if (c == '{' || c == '[') {
                p.skipNested();
            } else {
                String literal = p.literal();
                out.put(key, "null".equals(literal) ? null : literal);
            }
            p.ws();
            char next = p.next();
            if (next == '}') {
                return out;
            }
            if (next != ',') {
                throw new IllegalArgumentException("malformed JSON object");
            }
        }
    }

    static String escape(String v) {
        StringBuilder b = new StringBuilder(v.length() + 8);
        for (char c : v.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.toString();
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
    }

    private char peek() {
        if (i >= s.length()) {
            throw new IllegalArgumentException("truncated JSON");
        }
        return s.charAt(i);
    }

    private char next() {
        char c = peek();
        i++;
        return c;
    }

    private void expect(char c) {
        if (next() != c) {
            throw new IllegalArgumentException("malformed JSON: expected " + c);
        }
    }

    private String string() {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') {
                return b.toString();
            }
            if (c == '\\') {
                char e = next();
                switch (e) {
                    case '"', '\\', '/' -> b.append(e);
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'n' -> b.append('\n');
                    case 'r' -> b.append('\r');
                    case 't' -> b.append('\t');
                    case 'u' -> {
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> throw new IllegalArgumentException("bad escape");
                }
            } else {
                b.append(c);
            }
        }
    }

    private String literal() {
        int start = i;
        while (i < s.length() && ",}] \t\r\n".indexOf(s.charAt(i)) < 0) {
            i++;
        }
        return s.substring(start, i);
    }

    private void skipNested() {
        int depth = 0;
        boolean inString = false;
        while (true) {
            char c = next();
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                if (--depth == 0) {
                    return;
                }
            }
        }
    }
}
