package com.example;

import java.util.ArrayList;
import java.util.List;

/**
 * Just enough JSON to read the array Google Forms embeds in its page.
 *
 * <p>Only arrays, strings, numbers and {@code null} appear in {@code FB_PUBLIC_LOAD_DATA_}, and
 * picking the pieces apart with regular expressions breaks as soon as a question title contains a
 * bracket or a comma — which real forms do.
 */
final class Json {

    private final String text;
    private int at;

    private Json(String text) {
        this.text = text;
    }

    /** Returns nested {@code List<Object>}, {@code String}, {@code Double}, {@code Boolean} or null. */
    static Object parse(String text) {
        Json parser = new Json(text);
        parser.skipSpace();
        return parser.value();
    }

    /** Reads {@code list.get(index)} when it exists and is a list, otherwise an empty list. */
    @SuppressWarnings("unchecked")
    static List<Object> list(Object value) {
        return value instanceof List<?> list ? (List<Object>) list : List.of();
    }

    static Object at(Object value, int index) {
        List<Object> list = list(value);
        return index >= 0 && index < list.size() ? list.get(index) : null;
    }

    static String text(Object value) {
        return value instanceof String string ? string : null;
    }

    static Integer number(Object value) {
        return value instanceof Double d ? d.intValue() : null;
    }

    private Object value() {
        char c = text.charAt(at);
        return switch (c) {
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private List<Object> array() {
        List<Object> values = new ArrayList<>();
        at++; // [
        skipSpace();
        if (text.charAt(at) == ']') {
            at++;
            return values;
        }
        while (true) {
            skipSpace();
            values.add(value());
            skipSpace();
            char c = text.charAt(at++);
            if (c == ']') {
                return values;
            }
            if (c != ',') {
                throw new IllegalStateException("อ่าน JSON ไม่ออกที่ตำแหน่ง " + at);
            }
        }
    }

    private String string() {
        StringBuilder out = new StringBuilder();
        at++; // opening quote
        while (true) {
            char c = text.charAt(at++);
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            char escaped = text.charAt(at++);
            switch (escaped) {
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'u' -> {
                    out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                    at += 4;
                }
                default -> out.append(escaped); // \" \\ \/
            }
        }
    }

    private Object literal(String word, Object result) {
        at += word.length();
        return result;
    }

    private Double number() {
        int start = at;
        while (at < text.length() && "-+.eE0123456789".indexOf(text.charAt(at)) >= 0) {
            at++;
        }
        return Double.valueOf(text.substring(start, at));
    }

    private void skipSpace() {
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
            at++;
        }
    }
}
