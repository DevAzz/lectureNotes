package org.example;
import java.util.regex.MatchResult;
import java.util.HashSet;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;

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

    record Match(int start, String text) { }

    Match locate(String source, String input) throws IOException {
        var terms = TOKEN.matcher(normalize(input)).results().limit(32)
                .map(MatchResult::group).toList();
        if (terms.isEmpty()) return null;
        var literal = Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(input.strip())
                + "(?![\\p{L}\\p{N}])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(source);
        if (literal.find()) return new Match(literal.start(), literal.group());
        var tokens = TOKEN.matcher(source);
        while (tokens.find()) {
            if (terms.contains(normalize(tokens.group()))) return new Match(tokens.start(), tokens.group());
            var parts = WORD.matcher(tokens.group());
            while (parts.find()) if (terms.contains(normalize(parts.group())))
                return new Match(tokens.start() + parts.start(), parts.group());
        }
        var wanted = new HashSet<String>();
        for (var term : terms) if (WORD.matcher(term).matches()) wanted.addAll(stems(term));
        try (var stream = analyzer.tokenStream("text", source.replace('ё', 'е').replace('Ё', 'Е'))) {
            var term = stream.addAttribute(CharTermAttribute.class);
            var offsets = stream.addAttribute(OffsetAttribute.class);
            stream.reset();
            Match match = null;
            while (stream.incrementToken()) {
                if (wanted.contains(term.toString())) {
                    match = new Match(offsets.startOffset(), source.substring(offsets.startOffset(), offsets.endOffset()));
                    break;
                }
            }
            stream.end();
            return match;
        }
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
