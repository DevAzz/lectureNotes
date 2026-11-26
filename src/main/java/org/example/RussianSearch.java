package org.example;

import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.ru.RussianAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class RussianSearch implements AutoCloseable {
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{M}\\p{N}]+");
    private static final Pattern TOKEN = Pattern.compile(
            "[\\p{L}\\p{M}\\p{N}]+(?:[._/+:#-]+[\\p{L}\\p{M}\\p{N}]+)*(?:\\+\\+|#)?");
    private final RussianAnalyzer analyzer = new RussianAnalyzer(CharArraySet.EMPTY_SET);

    record Tokens(String stemmed, String exact) { }

    static String normalize(String text) {
        return Normalizer.normalize(text.strip().toLowerCase(Locale.ROOT), Normalizer.Form.NFC).replace('ё', 'е');
    }

    Tokens index(String text) throws IOException {
        String normalized = normalize(text);
        var exact = new ArrayList<String>();
        TOKEN.matcher(normalized).results().forEach(token -> {
            String value = token.group();
            exact.add(encoded(value));
            if (!WORD.matcher(value).matches()) {
                WORD.matcher(value).results().forEach(part -> exact.add(encoded(part.group())));
            }
        });
        return new Tokens(String.join(" ", stems(normalized)), String.join(" ", exact));
    }

    String query(String text) throws IOException {
        var terms = new ArrayList<String>();
        var matcher = TOKEN.matcher(normalize(text));
        while (matcher.find() && terms.size() < 32) {
            String value = matcher.group();
            String exact = "exact:\"" + encoded(value) + "\"";
            List<String> stems = WORD.matcher(value).matches() ? stems(value) : List.of();
            terms.add(stems.size() == 1 ? "(stemmed:\"" + stems.getFirst() + "\" OR " + exact + ")" : exact);
        }
        return String.join(" AND ", terms);
    }

    private List<String> stems(String text) throws IOException {
        var result = new ArrayList<String>();
        try (var stream = analyzer.tokenStream("text", text)) {
            var term = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) result.add(term.toString());
            stream.end();
        }
        return result;
    }

    private static String encoded(String token) {
        return "x" + HexFormat.of().formatHex(token.getBytes(StandardCharsets.UTF_8));
    }

    @Override public void close() {
        analyzer.close();
    }
}
