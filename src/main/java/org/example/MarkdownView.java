package org.example;

import javafx.geometry.Insets;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.awt.Desktop;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.LongConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Просматривает Markdown конспекта средствами JavaFX без внешних зависимостей.
 * Поддержаны заголовки, абзацы, списки, цитаты, код, таблицы, ссылки и ссылки на таймкод «(#t=мс)».
 */
final class MarkdownView extends ScrollPane {
    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}(#{1,6})\\s+(.*?)\\s*#*\\s*$");
    private static final Pattern BREAK = Pattern.compile("^\\s{0,3}([-*_])(?:\\s*\\1){2,}\\s*$");
    private static final Pattern FENCE = Pattern.compile("^\\s{0,3}(`{3,}|~{3,})\\s*([^\\s`]*)\\s*$");
    private static final Pattern QUOTE = Pattern.compile("^\\s{0,3}>\\s?(.*)$");
    private static final Pattern ITEM = Pattern.compile("^(\\s*)([-+*]|\\d{1,9}[.)])\\s+(.*)$");
    private static final Pattern TASK = Pattern.compile("^\\[([ xX])\\]\\s+(.*)$");
    private static final Pattern DIVIDER = Pattern.compile("^\\s*\\|?\\s*:?-{2,}:?\\s*(?:\\|\\s*:?-{2,}:?\\s*)+\\|?\\s*$");
    private static final Pattern TIME = Pattern.compile("^#t=(\\d+)$");
    private static final Pattern AUTOLINK = Pattern.compile("^<((?:https?://|mailto:)[^<>\\s]+)>");
    private static final Pattern BARE_URL = Pattern.compile("(?:https?://|www\\.)[^\\s)\\]}<>\"'`]*[^\\s)\\]}<>\"'`.,;:!?]");
    private static final String[] BULLETS = {"•", "◦", "▪"};

    private final VBox blocks = new VBox(10);
    private LongConsumer onSeek = time -> { };

    MarkdownView() {
        getStyleClass().add("md-view");
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        setVbarPolicy(ScrollBarPolicy.AS_NEEDED);
        blocks.getStyleClass().add("md-body");
        blocks.setPadding(new Insets(16, 20, 20, 20));
        setContent(blocks);
    }

    void setOnSeek(LongConsumer handler) {
        onSeek = handler == null ? time -> { } : handler;
    }

    void setMarkdown(String source) {
        blocks.getChildren().clear();
        String text = source == null ? "" : source.replace("\r\n", "\n").replace('\r', '\n');
        if (text.isBlank()) {
            var empty = new Label("Конспект пока пуст. Напишите текст слева или сформируйте его по расшифровке.");
            empty.getStyleClass().add("md-empty");
            empty.setWrapText(true);
            blocks.getChildren().add(empty);
            return;
        }
        renderBlocks(blocks, text);
    }

    private void renderBlocks(VBox target, String text) {
        var lines = text.split("\n", -1);
        int index = 0;
        while (index < lines.length) {
            String line = lines[index];
            if (line.isBlank()) {
                index++;
                continue;
            }
            Matcher fence = FENCE.matcher(line);
            if (fence.matches()) {
                index = codeBlock(target, lines, index, fence.group(1), fence.group(2));
                continue;
            }
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                var flow = paragraph(heading.group(2).strip());
                String style = "md-h" + heading.group(1).length();
                decorate(flow.getChildren(), style);
                flow.getStyleClass().add(style);
                target.getChildren().add(flow);
                index++;
                continue;
            }
            if (BREAK.matcher(line).matches()) {
                var rule = new Region();
                rule.getStyleClass().add("md-rule");
                target.getChildren().add(rule);
                index++;
                continue;
            }
            if (QUOTE.matcher(line).matches()) {
                var inner = new StringBuilder();
                while (index < lines.length) {
                    Matcher quote = QUOTE.matcher(lines[index]);
                    if (!quote.matches()) break;
                    inner.append(quote.group(1)).append('\n');
                    index++;
                }
                var quote = new VBox(6);
                quote.getStyleClass().add("md-quote");
                renderBlocks(quote, inner.toString());
                target.getChildren().add(quote);
                continue;
            }
            if (line.indexOf('|') >= 0 && index + 1 < lines.length && DIVIDER.matcher(lines[index + 1]).matches()) {
                index = table(target, lines, index);
                continue;
            }
            if (ITEM.matcher(line).matches()) {
                index = list(target, lines, index);
                continue;
            }
            var body = new StringBuilder(line.strip());
            index++;
            while (index < lines.length && !lines[index].isBlank() && !startsBlock(lines[index])) {
                body.append(' ').append(lines[index].strip());
                index++;
            }
            target.getChildren().add(paragraph(body.toString()));
        }
    }

    private int codeBlock(VBox target, String[] lines, int index, String marker, String language) {
        var body = new StringBuilder();
        index++;
        while (index < lines.length) {
            Matcher close = FENCE.matcher(lines[index]);
            boolean closes = close.matches() && close.group(1).charAt(0) == marker.charAt(0)
                    && close.group(1).length() >= marker.length() && close.group(2).isEmpty();
            if (closes) break;
            body.append(lines[index].stripTrailing()).append('\n');
            index++;
        }
        var code = new Text(body.toString().stripTrailing());
        code.getStyleClass().add("md-code-text");
        var flow = new TextFlow(code);
        flow.getStyleClass().add("md-code-block");
        if (language.isBlank()) target.getChildren().add(flow);
        else {
            var caption = new Text(language.strip());
            caption.getStyleClass().add("md-code-lang");
            target.getChildren().add(new VBox(2, caption, flow));
        }
        return index + 1;
    }

    private int table(VBox target, String[] lines, int index) {
        var header = cells(lines[index]);
        index += 2;
        var rows = new ArrayList<List<String>>();
        while (index < lines.length && !lines[index].isBlank() && lines[index].indexOf('|') >= 0) {
            rows.add(cells(lines[index]));
            index++;
        }
        int columns = header.size();
        var grid = new GridPane();
        grid.getStyleClass().add("md-table");
        grid.setHgap(0);
        grid.setVgap(0);
        for (int column = 0; column < columns; column++) {
            var constraint = new ColumnConstraints();
            constraint.setPercentWidth(100.0 / columns);
            constraint.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().add(constraint);
        }
        for (int column = 0; column < columns; column++) grid.add(cell(header.get(column), "md-th"), column, 0);
        int row = 1;
        for (var values : rows) {
            for (int column = 0; column < columns; column++) {
                grid.add(cell(column < values.size() ? values.get(column) : "", "md-td"), column, row);
            }
            row++;
        }
        var box = new VBox(grid);
        box.getStyleClass().add("md-table-wrap");
        target.getChildren().add(box);
        return index;
    }

    private static List<String> cells(String line) {
        String trimmed = line.strip();
        if (trimmed.startsWith("|")) trimmed = trimmed.substring(1);
        if (trimmed.endsWith("|")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        var values = new ArrayList<String>();
        var current = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < trimmed.length(); i++) {
            char symbol = trimmed.charAt(i);
            if (escaped) {
                current.append(symbol);
                escaped = false;
            } else if (symbol == '\\') escaped = true;
            else if (symbol == '|') {
                values.add(current.toString().strip());
                current.setLength(0);
            } else current.append(symbol);
        }
        values.add(current.toString().strip());
        return values;
    }

    private Node cell(String value, String style) {
        var flow = paragraph(value);
        flow.getStyleClass().add("md-cell");
        flow.getStyleClass().add(style);
        return flow;
    }

    private int list(VBox target, String[] lines, int index) {
        Matcher first = ITEM.matcher(lines[index]);
        first.matches();
        int base = indent(first.group(1));
        var items = new ArrayList<Item>();
        while (index < lines.length) {
            Matcher item = ITEM.matcher(lines[index]);
            if (item.matches()) {
                int indent = indent(item.group(1));
                if (indent < base) break;
                items.add(new Item(indent, item.group(2), new StringBuilder(item.group(3))));
                index++;
                continue;
            }
            boolean continues = !lines[index].isBlank() && !startsBlock(lines[index]) && !items.isEmpty()
                    && indent(lines[index]) > base;
            if (!continues) break;
            items.getLast().text.append(' ').append(lines[index].strip());
            index++;
        }
        var root = new Branch(-1, null);
        var stack = new ArrayList<Branch>();
        stack.add(root);
        for (var item : items) {
            while (stack.size() > 1 && item.indent <= stack.getLast().indent) stack.removeLast();
            var branch = new Branch(item.indent, item);
            stack.getLast().children.add(branch);
            stack.add(branch);
        }
        var box = new VBox(4);
        box.getStyleClass().add("md-list");
        renderItems(box, root.children, 0);
        target.getChildren().add(box);
        return index;
    }

    private void renderItems(VBox target, List<Branch> branches, int depth) {
        int number = 0;
        for (var branch : branches) {
            var item = branch.item;
            String text = item.text.toString();
            String marker;
            String style = "md-bullet";
            Matcher task = TASK.matcher(text);
            if (task.matches()) {
                boolean done = !" ".equals(task.group(1));
                marker = done ? "☑" : "☐";
                style = done ? "md-task md-task-done" : "md-task";
                text = task.group(2);
            } else if (item.marker.matches("\\d{1,9}[.)]")) {
                int value = Integer.parseInt(item.marker.substring(0, item.marker.length() - 1));
                number = number == 0 ? value : number + 1;
                marker = number + item.marker.substring(item.marker.length() - 1);
                style = "md-number";
            } else marker = BULLETS[Math.min(depth, BULLETS.length - 1)];

            var mark = new Text(marker);
            mark.getStyleClass().add("md-text");
            decorate(List.of(mark), style);
            var markFlow = new TextFlow(mark);
            markFlow.getStyleClass().add("md-marker");
            var content = new VBox(4);
            content.getChildren().add(paragraph(text));
            if (!branch.children.isEmpty()) {
                var nested = new VBox(4);
                nested.getStyleClass().add("md-list");
                renderItems(nested, branch.children, depth + 1);
                content.getChildren().add(nested);
            }
            var row = new HBox(8, markFlow, content);
            row.getStyleClass().add("md-item");
            target.getChildren().add(row);
        }
    }

    private TextFlow paragraph(String source) {
        var flow = new TextFlow();
        flow.getStyleClass().add("md-para");
        inline(source, flow.getChildren());
        return flow;
    }

    private void inline(String source, List<Node> out) {
        var plain = new StringBuilder();
        int index = 0;
        while (index < source.length()) {
            char symbol = source.charAt(index);
            if (symbol == '\\') {
                if (index + 1 < source.length()) {
                    plain.append(source.charAt(index + 1));
                    index += 2;
                } else {
                    plain.append(symbol);
                    index++;
                }
                continue;
            }
            if (symbol == '`') {
                int run = run(source, index, '`');
                int close = findRun(source, index + run, '`', run);
                if (close < 0) {
                    plain.append(source, index, index + run);
                    index += run;
                    continue;
                }
                flush(plain, out);
                out.add(text(source.substring(index + run, close).replace('\n', ' ').strip(), "md-code"));
                index = close + run;
                continue;
            }
            if (symbol == '[' || (symbol == '!' && index + 1 < source.length() && source.charAt(index + 1) == '[')) {
                boolean image = symbol == '!';
                int open = image ? index + 1 : index;
                int labelEnd = matching(source, open, '[', ']');
                int target = labelEnd > 0 && labelEnd + 1 < source.length() && source.charAt(labelEnd + 1) == '('
                        ? labelEnd + 1 : -1;
                int targetEnd = target > 0 ? matching(source, target, '(', ')') : -1;
                if (targetEnd > 0) {
                    String label = source.substring(open + 1, labelEnd);
                    String url = source.substring(target + 1, targetEnd).strip();
                    int space = url.indexOf(' ');
                    if (space > 0) url = url.substring(0, space);
                    flush(plain, out);
                    if (image) out.add(text("🖼 " + (label.isBlank() ? url : label), "md-image"));
                    else emitLink(label, url, out);
                    index = targetEnd + 1;
                    continue;
                }
            }
            if (symbol == '<') {
                Matcher autolink = AUTOLINK.matcher(source.substring(index));
                if (autolink.find()) {
                    flush(plain, out);
                    out.add(link(autolink.group(1), autolink.group(1), "md-link"));
                    index += autolink.end();
                    continue;
                }
            }
            if (symbol == '~' && index + 1 < source.length() && source.charAt(index + 1) == '~') {
                int close = source.indexOf("~~", index + 2);
                if (close > index + 2) {
                    flush(plain, out);
                    var nodes = new ArrayList<Node>();
                    inline(source.substring(index + 2, close), nodes);
                    decorate(nodes, "md-del");
                    out.addAll(nodes);
                    index = close + 2;
                    continue;
                }
            }
            if (symbol == '*' || symbol == '_') {
                int run = run(source, index, symbol);
                int close = findEmphasis(source, index + run, symbol, run);
                if (close < 0) {
                    plain.append(source, index, index + run);
                    index += run;
                    continue;
                }
                flush(plain, out);
                var nodes = new ArrayList<Node>();
                inline(source.substring(index + run, close), nodes);
                decorate(nodes, run >= 3 ? "md-strong md-em" : run == 2 ? "md-strong" : "md-em");
                out.addAll(nodes);
                index = close + run;
                continue;
            }
            plain.append(symbol);
            index++;
        }
        flush(plain, out);
    }

    private void emitLink(String label, String url, List<Node> out) {
        Matcher time = TIME.matcher(url);
        if (time.matches()) {
            out.add(timecode(Long.parseLong(time.group(1))));
            return;
        }
        var nodes = new ArrayList<Node>();
        if (label.isBlank()) nodes.add(text(url));
        else inline(label, nodes);
        boolean internal = url.startsWith("#");
        for (var node : nodes) {
            node.getStyleClass().add(internal ? "md-anchor" : "md-link");
            if (node instanceof Text value) {
                value.setUnderline(!internal);
                if (!internal) {
                    value.setCursor(Cursor.HAND);
                    value.setOnMouseClicked(event -> open(url));
                }
            }
        }
        out.addAll(nodes);
    }

    private Text link(String label, String url, String style) {
        var node = text(label, style);
        node.setUnderline(true);
        node.setCursor(Cursor.HAND);
        node.setOnMouseClicked(event -> open(url));
        return node;
    }

    private Text timecode(String label, long millis) {
        var node = text(label, "md-time");
        node.setCursor(Cursor.HAND);
        node.setOnMouseClicked(event -> onSeek.accept(millis));
        return node;
    }

    private Text timecode(long millis) {
        return timecode("▶ " + RecordingPlayer.timestamp(millis), millis);
    }

    private void open(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (scheme == null || !List.of("http", "https").contains(scheme.toLowerCase(Locale.ROOT))) return;
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(uri);
            }
        } catch (RuntimeException | java.io.IOException ignored) {
            // Ссылка остаётся текстом, если системный браузер недоступен.
        }
    }

    private void flush(StringBuilder plain, List<Node> out) {
        if (plain.isEmpty()) return;
        String value = plain.toString();
        plain.setLength(0);
        Matcher urls = BARE_URL.matcher(value);
        int last = 0;
        while (urls.find()) {
            if (urls.start() > last) out.add(text(value.substring(last, urls.start())));
            String url = urls.group();
            out.add(link(url, url.startsWith("www.") ? "http://" + url : url, "md-link"));
            last = urls.end();
        }
        if (last < value.length()) out.add(text(value.substring(last)));
    }

    private Text text(String value, String... styles) {
        var node = new Text(value);
        node.getStyleClass().add("md-text");
        for (String style : styles) node.getStyleClass().add(style);
        return node;
    }

    private static void decorate(List<Node> nodes, String styles) {
        for (String style : styles.split(" ")) {
            for (var node : nodes) if (node instanceof Text text) text.getStyleClass().add(style);
        }
    }

    private static boolean startsBlock(String line) {
        return HEADING.matcher(line).matches() || BREAK.matcher(line).matches() || FENCE.matcher(line).matches()
                || QUOTE.matcher(line).matches() || ITEM.matcher(line).matches();
    }

    private static int indent(String value) {
        return value.replace("\t", "    ").length();
    }

    /** Возвращает позицию парной скобки или -1, если скобка не закрыта. */
    private static int matching(String source, int from, char open, char close) {
        int depth = 0;
        for (int i = from; i < source.length(); i++) {
            char symbol = source.charAt(i);
            if (symbol == '\\') i++;
            else if (symbol == open) depth++;
            else if (symbol == close) {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static int run(String source, int from, char marker) {
        int end = from;
        while (end < source.length() && source.charAt(end) == marker) end++;
        return end - from;
    }

    private static int findRun(String source, int from, char marker, int size) {
        int index = from;
        while (index < source.length()) {
            index = source.indexOf(marker, index);
            if (index < 0) return -1;
            int count = run(source, index, marker);
            if (count == size) return index;
            index += count;
        }
        return -1;
    }

    private static int findEmphasis(String source, int from, char marker, int size) {
        int index = from;
        while (index < source.length()) {
            index = source.indexOf(marker, index);
            if (index < 0) return -1;
            int count = run(source, index, marker);
            if (count >= size && index > from && !Character.isWhitespace(source.charAt(index - 1))) return index;
            index += Math.max(1, count);
        }
        return -1;
    }

    private static final class Item {
        final int indent;
        final String marker;
        final StringBuilder text;

        Item(int indent, String marker, StringBuilder text) {
            this.indent = indent;
            this.marker = marker;
            this.text = text;
        }
    }

    private static final class Branch {
        final int indent;
        final Item item;
        final List<Branch> children = new ArrayList<>();

        Branch(int indent, Item item) {
            this.indent = indent;
            this.item = item;
        }
    }
}
