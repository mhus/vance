package de.mhus.vance.brain.toolpack.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tiny Jackson-free JSON writer/reader. Vance's existing Jackson lives
 * in vance-brain; the toolpack/* layer aims to stay dependency-light
 * for the future module extract. For the body shapes that both REST
 * tool packs (request body, response parsing) and MCP transports
 * (JSON-RPC frames) deal with — objects, arrays, strings, numbers,
 * booleans, null — hand-rolled is fine.
 */
public final class PackJson {

    private PackJson() { /* static only */ }

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeAny(sb, value);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeAny(StringBuilder sb, Object value) {
        if (value == null) { sb.append("null"); return; }
        if (value instanceof Boolean || value instanceof Number) {
            sb.append(value); return;
        }
        if (value instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeAny(sb, e.getValue());
            }
            sb.append('}'); return;
        }
        if (value instanceof java.util.Collection<?> c) {
            sb.append('[');
            boolean first = true;
            for (Object item : c) {
                if (!first) sb.append(',');
                first = false;
                writeAny(sb, item);
            }
            sb.append(']'); return;
        }
        writeString(sb, String.valueOf(value));
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    /**
     * Parses JSON into a tree of {@code Map<String,Object>} /
     * {@code List<Object>} / strings / numbers / booleans / null.
     * Throws {@link IllegalArgumentException} on malformed input.
     */
    public static Object read(String json) {
        Cursor c = new Cursor(json);
        c.skipWs();
        Object v = readValue(c);
        c.skipWs();
        if (c.hasMore()) {
            throw new IllegalArgumentException("Trailing input after JSON value at pos " + c.pos());
        }
        return v;
    }

    private static Object readValue(Cursor c) {
        c.skipWs();
        char ch = c.peek();
        if (ch == '"') return readJsonString(c);
        if (ch == '{') return readObject(c);
        if (ch == '[') return readArray(c);
        if (ch == 't' || ch == 'f') return readBool(c);
        if (ch == 'n') { c.expectLiteral("null"); return null; }
        return readNumber(c);
    }

    private static String readJsonString(Cursor c) {
        c.expect('"');
        StringBuilder sb = new StringBuilder();
        while (c.hasMore()) {
            char ch = c.next();
            if (ch == '"') return sb.toString();
            if (ch == '\\' && c.hasMore()) {
                char esc = c.next();
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        String hex = c.takeChars(4);
                        sb.append((char) Integer.parseInt(hex, 16));
                    }
                    default -> sb.append(esc);
                }
                continue;
            }
            sb.append(ch);
        }
        throw new IllegalArgumentException("Unterminated string at pos " + c.pos());
    }

    private static Map<String, Object> readObject(Cursor c) {
        c.expect('{');
        Map<String, Object> out = new LinkedHashMap<>();
        c.skipWs();
        if (c.peek() == '}') { c.next(); return out; }
        while (true) {
            c.skipWs();
            String key = readJsonString(c);
            c.skipWs();
            c.expect(':');
            out.put(key, readValue(c));
            c.skipWs();
            char sep = c.next();
            if (sep == ',') continue;
            if (sep == '}') return out;
            throw new IllegalArgumentException("Expected ',' or '}' at pos " + c.pos());
        }
    }

    private static java.util.List<Object> readArray(Cursor c) {
        c.expect('[');
        java.util.List<Object> out = new java.util.ArrayList<>();
        c.skipWs();
        if (c.peek() == ']') { c.next(); return out; }
        while (true) {
            out.add(readValue(c));
            c.skipWs();
            char sep = c.next();
            if (sep == ',') continue;
            if (sep == ']') return out;
            throw new IllegalArgumentException("Expected ',' or ']' at pos " + c.pos());
        }
    }

    private static Boolean readBool(Cursor c) {
        char ch = c.peek();
        if (ch == 't') { c.expectLiteral("true"); return Boolean.TRUE; }
        c.expectLiteral("false");
        return Boolean.FALSE;
    }

    private static Number readNumber(Cursor c) {
        StringBuilder sb = new StringBuilder();
        while (c.hasMore()) {
            char ch = c.peek();
            if ("-+0123456789eE.".indexOf(ch) < 0) break;
            sb.append(c.next());
        }
        String s = sb.toString();
        if (s.isEmpty()) throw new IllegalArgumentException("Expected number at pos " + c.pos());
        if (s.contains(".") || s.contains("e") || s.contains("E")) {
            return Double.parseDouble(s);
        }
        try { return Long.parseLong(s); }
        catch (NumberFormatException e) { return Double.parseDouble(s); }
    }

    private static final class Cursor {
        final String src; int p;
        Cursor(String src) { this.src = src; }
        char peek() { return src.charAt(p); }
        char next() { return src.charAt(p++); }
        boolean hasMore() { return p < src.length(); }
        int pos() { return p; }
        void skipWs() { while (p < src.length() && Character.isWhitespace(src.charAt(p))) p++; }
        void expect(char c) {
            if (!hasMore() || src.charAt(p) != c)
                throw new IllegalArgumentException("Expected '" + c + "' at pos " + p);
            p++;
        }
        void expectLiteral(String lit) {
            if (p + lit.length() > src.length() || !src.startsWith(lit, p))
                throw new IllegalArgumentException("Expected '" + lit + "' at pos " + p);
            p += lit.length();
        }
        String takeChars(int n) {
            if (p + n > src.length())
                throw new IllegalArgumentException("Expected " + n + " more chars at pos " + p);
            String s = src.substring(p, p + n);
            p += n;
            return s;
        }
    }
}
