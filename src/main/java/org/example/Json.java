package org.example;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Json {
    static final class CompletionLimit extends IOException {
        CompletionLimit() {
            super("Локальная модель исчерпала лимит ответа. Конспект обрезан.");
        }
    }

    private final String input;
    private int position;

    private Json(String input) { this.input = input; }

    static String quote(String text) {
        var result = new StringBuilder("\"");
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            switch (ch) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (ch < 32) result.append("\\u%04x".formatted((int) ch));
                    else result.append(ch);
                }
            }
        }
        return result.append('"').toString();
    }

    static String completion(String input) throws IOException {
        var parser = new Json(input);
        Object parsed = parser.value(0);
        parser.skipSpace();
        if (parser.position == input.length() && parsed instanceof Map<?, ?> root
                && root.get("choices") instanceof List<?> choices && !choices.isEmpty()
                && choices.getFirst() instanceof Map<?, ?> choice) {
            if ("length".equals(choice.get("finish_reason"))) throw new CompletionLimit();
            if ("stop".equals(choice.get("finish_reason"))
                    && choice.get("message") instanceof Map<?, ?> message
                    && message.get("content") instanceof String content && !content.isBlank()) {
                return content;
            }
        }
        throw invalid();
    }

    private Object value(int depth) throws IOException {
        if (depth > 40) throw invalid();
        skipSpace();
        if (position == input.length()) throw invalid();
        return switch (input.charAt(position)) {
            case '"' -> string();
            case '{' -> object(depth + 1);
            case '[' -> array(depth + 1);
            default -> scalar();
        };
    }

    private Map<String, Object> object(int depth) throws IOException {
        var members = new LinkedHashMap<String, Object>();
        expect('{');
        if (take('}')) return members;
        do {
            String key = string();
            expect(':');
            members.put(key, value(depth));
        } while (take(','));
        expect('}');
        return members;
    }

    private List<Object> array(int depth) throws IOException {
        var items = new ArrayList<>();
        expect('[');
        if (take(']')) return items;
        do items.add(value(depth)); while (take(','));
        expect(']');
        return items;
    }

    private Object scalar() throws IOException {
        int start = position;
        while (position < input.length() && "0123456789-+.eEtnrufsla".indexOf(input.charAt(position)) >= 0) position++;
        String value = input.substring(start, position);
        if (value.equals("null") || value.equals("true") || value.equals("false")) return null;
        try { return Double.valueOf(value); }
        catch (NumberFormatException failure) { throw invalid(); }
    }

    private String string() throws IOException {
        expect('"');
        var result = new StringBuilder();
        while (position < input.length()) {
            char ch = input.charAt(position++);
            if (ch == '"') return result.toString();
            if (ch < 32) throw invalid();
            if (ch != '\\') { result.append(ch); continue; }
            if (position == input.length()) throw invalid();
            result.append(switch (input.charAt(position++)) {
                case '"', '\\', '/' -> input.charAt(position - 1);
                case 'b' -> '\b';
                case 'f' -> '\f';
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                case 'u' -> unicode();
                default -> throw invalid();
            });
        }
        throw invalid();
    }

    private char unicode() throws IOException {
        if (position + 4 > input.length()) throw invalid();
        try {
            char value = (char) Integer.parseInt(input, position, position + 4, 16);
            position += 4;
            return value;
        } catch (NumberFormatException error) { throw invalid(); }
    }

    private boolean take(char expected) {
        skipSpace();
        if (position == input.length() || input.charAt(position) != expected) return false;
        position++;
        return true;
    }

    private void expect(char expected) throws IOException {
        if (!take(expected)) throw invalid();
    }

    private void skipSpace() {
        while (position < input.length() && " \n\r\t".indexOf(input.charAt(position)) >= 0) position++;
    }

    private static IOException invalid() {
        return new IOException("Локальная модель вернула пустой, неполный или некорректный ответ.");
    }
}
