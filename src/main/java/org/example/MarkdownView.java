package org.example;

import javafx.animation.AnimationTimer;
import javafx.animation.PauseTransition;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Path;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.util.Duration;

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
    private final PauseTransition flash = new PauseTransition(Duration.seconds(2));
    private final List<SelectableFlow> textFlows = new ArrayList<>();
    private int anchor;
    private int selectionEnd;
    private int textLength;
    private Point2D press;
    private Point2D drag;
    private boolean dragged;
    private Path highlight;

    private final AnimationTimer selectionScroll = new AnimationTimer() {
        @Override public void handle(long now) {
            Bounds viewport = viewportOnScene();
            if (viewport == null || drag == null) return;
            double outside = drag.getY() < viewport.getMinY() ? drag.getY() - viewport.getMinY()
                    : Math.max(0, drag.getY() - viewport.getMaxY());
            double scrollable = scrollableHeight();
            if (outside == 0 || scrollable == 0) return;
            setVvalue(Math.clamp(getVvalue() + Math.clamp(outside / 4, -28, 28) / scrollable, 0, 1));
            extendSelection();
        }
    };

    boolean reveal(String fragment) {
        applyCss();
        layout();
        for (var flow : textFlows) {
            int start = flow.text.indexOf(fragment);
            if (start < 0) continue;
            clearHighlight();
            highlight = new Path(flow.rangeShape(start, start + fragment.length()));
            highlight.setManaged(false);
            highlight.setMouseTransparent(true);
            highlight.getStyleClass().add("search-highlight");
            flow.getChildren().add(highlight);
            var bounds = blocks.sceneToLocal(highlight.localToScene(highlight.getBoundsInLocal()));
            double height = getViewportBounds().getHeight();
            double scrollable = scrollableHeight();
            if (scrollable > 0) setVvalue(Math.clamp((bounds.getMinY() - height / 3) / scrollable, 0, 1));
            flash.setOnFinished(event -> clearHighlight());
            flash.playFromStart();
            return true;
        }
        return false;
    }

    private void clearHighlight() {
        flash.stop();
        if (highlight != null) {
            ((TextFlow) highlight.getParent()).getChildren().remove(highlight);
            highlight = null;
        }
    }

    private LongConsumer onSeek = time -> { };

    MarkdownView() {
        getStyleClass().add("md-view");
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        setVbarPolicy(ScrollBarPolicy.AS_NEEDED);
        setFocusTraversable(true);
        blocks.getStyleClass().add("md-body");
        blocks.setCursor(Cursor.TEXT);
        blocks.setPadding(new Insets(16, 20, 20, 20));
        setContent(blocks);
        blocks.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() != MouseButton.PRIMARY) return;
            requestFocus();
            press = new Point2D(event.getSceneX(), event.getSceneY());
            dragged = false;
            int offset = offsetAt(press);
            if (!event.isShiftDown()) anchor = offset;
            selectTo(offset);
        });
        blocks.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> {
            if (press == null || !event.isPrimaryButtonDown()) return;
            drag = new Point2D(event.getSceneX(), event.getSceneY());
            if (!dragged && drag.distance(press) < 3) return;
            dragged = true;
            extendSelection();
            selectionScroll.start();
            event.consume();
        });
        blocks.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
            selectionScroll.stop();
            press = drag = null;
        });
        blocks.addEventFilter(MouseEvent.MOUSE_CLICKED, event -> {
            if (event.getButton() == MouseButton.PRIMARY && dragged) event.consume();
        });
        setOnKeyPressed(event -> {
            if (!event.isShortcutDown()) return;
            if (event.getCode() == KeyCode.C) copySelection();
            else if (event.getCode() == KeyCode.A) {
                anchor = 0;
                if (!textFlows.isEmpty()) {
                    var last = textFlows.getLast();
                    selectTo(last.start + last.text.length());
                }
            } else return;
            event.consume();
        });
        var copy = new MenuItem("Копировать");
        copy.setOnAction(event -> copySelection());
        var menu = new ContextMenu(copy);
        menu.setOnShowing(event -> copy.setDisable(anchor == selectionEnd));
        setContextMenu(menu);
    }

    double scrollableHeight() {
        return Math.max(0, blocks.getHeight() - getViewportBounds().getHeight());
    }

    private Bounds viewportOnScene() {
        var viewport = lookup(".viewport");
        return viewport == null ? null : viewport.localToScene(viewport.getLayoutBounds());
    }

    private void extendSelection() {
        Bounds viewport = viewportOnScene();
        if (viewport == null) return;
        selectTo(offsetAt(new Point2D(drag.getX(), Math.clamp(drag.getY(), viewport.getMinY(), viewport.getMaxY()))));
    }

    private int offsetAt(Point2D point) {
        SelectableFlow nearest = null;
        double distance = Double.POSITIVE_INFINITY;
        for (var flow : textFlows) {
            if (flow.text.isEmpty()) continue;
            Bounds bounds = flow.localToScene(flow.getLayoutBounds());
            double dy = Math.max(bounds.getMinY() - point.getY(), point.getY() - bounds.getMaxY());
            double dx = Math.max(bounds.getMinX() - point.getX(), point.getX() - bounds.getMaxX());
            double current = Math.hypot(Math.max(0, dx), Math.max(0, dy));
            if (current >= distance) continue;
            nearest = flow;
            distance = current;
            if (distance == 0) break;
        }
        if (nearest == null) return 0;
        Point2D local = nearest.sceneToLocal(point);
        int offset = nearest.hitTest(local).getInsertionIndex();
        return nearest.start + Math.clamp(offset, 0, nearest.text.length());
    }

    private void selectTo(int end) {
        selectionEnd = end;
        for (var flow : textFlows) flow.paintSelection();
    }

    private void copySelection() {
        if (anchor == selectionEnd) return;
        var content = new ClipboardContent();
        content.putString(selectedText());
        Clipboard.getSystemClipboard().setContent(content);
    }

    String selectedText() {
        int start = Math.min(anchor, selectionEnd);
        int end = Math.max(anchor, selectionEnd);
        var result = new StringBuilder();
        for (var flow : textFlows) {
            int from = Math.max(start - flow.start, 0);
            int to = Math.min(end - flow.start, flow.text.length());
            if (from < to) result.append(flow.text, from, to);
            int after = flow.start + flow.text.length();
            if (start <= after && end > after) result.append(flow.separator);
        }
        return result.toString();
    }

    private SelectableFlow register(SelectableFlow flow, List<Node> nodes, String separator) {
        for (var node : nodes) flow.getChildren().add(node);
        var text = new StringBuilder();
        for (var node : nodes) if (node instanceof Text span) text.append(span.getText());
        flow.text = text.toString();
        flow.start = textLength;
        flow.separator = separator;
        textLength += flow.text.length() + separator.length();
        textFlows.add(flow);
        return flow;
    }

    private final class SelectableFlow extends TextFlow {
        private final Path selection = new Path();
        private String text;
        private String separator;
        private int start;

        SelectableFlow() {
            selection.setManaged(false);
            selection.setMouseTransparent(true);
            selection.getStyleClass().add("md-selection");
        }

        @Override protected void layoutChildren() {
            super.layoutChildren();
            paintSelection();
        }

        void paintSelection() {
            int from = Math.max(0, Math.min(anchor, selectionEnd) - start);
            int to = Math.min(text.length(), Math.max(anchor, selectionEnd) - start);
            if (from < to) {
                selection.getElements().setAll(rangeShape(from, to));
                if (selection.getParent() == null) getChildren().addFirst(selection);
            } else if (selection.getParent() != null) getChildren().remove(selection);
        }
    }

    void setOnSeek(LongConsumer handler) {
        onSeek = handler == null ? time -> { } : handler;
    }

    void setMarkdown(String source) {
        clearHighlight();
        selectionScroll.stop();
        press = drag = null;
        anchor = selectionEnd = textLength = 0;
        textFlows.clear();
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
        SelectableFlow captionFlow = null;
        if (!language.isBlank()) {
            var caption = new Text(language.strip());
            caption.getStyleClass().add("md-code-lang");
            captionFlow = register(new SelectableFlow(), List.of(caption), "\n");
        }
        var flow = register(new SelectableFlow(), List.of(code), "\n");
        flow.getStyleClass().add("md-code-block");
        target.getChildren().add(captionFlow == null ? flow : new VBox(2, captionFlow, flow));
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
        for (int column = 0; column < columns; column++)
            grid.add(cell(header.get(column), "md-th", column == columns - 1), column, 0);
        int row = 1;
        for (var values : rows) {
            for (int column = 0; column < columns; column++) {
                grid.add(cell(column < values.size() ? values.get(column) : "", "md-td", column == columns - 1),
                        column, row);
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

    private Node cell(String value, String style, boolean endOfRow) {
        var flow = paragraph(value, endOfRow ? "\n" : "\t");
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
            var markFlow = register(new SelectableFlow(), List.of(mark), " ");
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
        return paragraph(source, "\n");
    }

    private TextFlow paragraph(String source, String separator) {
        var nodes = new ArrayList<Node>();
        inline(source, nodes);
        var flow = register(new SelectableFlow(), nodes, separator);
        flow.getStyleClass().add("md-para");
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
