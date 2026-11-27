package org.example;

import javafx.animation.PauseTransition;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Редактор конспекта: лента форматирования в стиле Word, поле Markdown и живой просмотр.
 * Группы значков подписаны, а режимы просмотра вынесены в нижнюю строку отдельно от правки.
 * Полем по-прежнему управляет LectureDocuments, поэтому сохранение редакций не меняется.
 */
final class MarkdownEditor extends BorderPane {
    private enum Mode { EDIT, SPLIT, PREVIEW }

    private record Layout(List<Integer> starts, List<Integer> ends, List<String> lines) {
        int paragraphAt(int offset) {
            int index = 0;
            while (index + 1 < starts.size() && starts.get(index + 1) <= offset) index++;
            return index;
        }
    }

    private static final String TABLE = "| Термин | Определение |\n| --- | --- |\n|  |  |\n|  |  |";
    private static final String PARAGRAPH = "Абзац";
    private static final String BULLET = "- ";
    private static final String TASK = "- [ ] ";
    private static final List<String> STYLES = List.of(PARAGRAPH, "Заголовок 1", "Заголовок 2", "Заголовок 3",
            "Цитата", "Список", "Нумерованный список", "Чек-лист", "Код");
    private static final Pattern HEADING_PREFIX = Pattern.compile("^\\s{0,3}#{1,6}\\s+");
    private static final Pattern QUOTE_PREFIX = Pattern.compile("^\\s{0,3}>\\s?");
    private static final Pattern LIST_PREFIX = Pattern.compile("^\\s*([-+*]|\\d{1,9}[.)])\\s+(\\[[ xX]\\]\\s+)?");
    private static final Pattern ORDERED_PREFIX = Pattern.compile("^\\s*\\d{1,9}[.)]\\s+");
    private static final Pattern LIST_LINE = Pattern.compile("^(\\s*)([-+*]|\\d{1,9}[.)])\\s+(\\[[ xX]\\]\\s+)?(.*)$");
    private static final Pattern QUOTE_LINE = Pattern.compile("^(\\s*)>\\s?(.*)$");
    private static final Pattern URL = Pattern.compile("(?:https?://|www\\.)\\S+");

    private final TextArea editor;
    private final MarkdownView preview = new MarkdownView();
    private final SplitPane split = new SplitPane();
    private final Label status = new Label();
    private final Label hint = new Label("Ctrl+B · Ctrl+I · Ctrl+E · Ctrl+K · Ctrl+S · Enter продолжает список");
    private final ComboBox<String> style = new ComboBox<>();
    private final Button timecode;
    private final Button undoTool;
    private final Button redoTool;
    private final ToggleGroup modes = new ToggleGroup();
    private final PauseTransition debounce = new PauseTransition(Duration.millis(280));
    private final ReadOnlyBooleanWrapper timecodeEnabled = new ReadOnlyBooleanWrapper(true);
    private final ReadOnlyBooleanWrapper editingVisible = new ReadOnlyBooleanWrapper(true);
    private final List<Button> tools = new ArrayList<>();
    private final LongSupplier playback;
    private final BooleanBinding toolsDisabled;
    private Runnable saveAction = () -> { };
    private Mode mode = Mode.SPLIT;
    private boolean dirty;

    MarkdownEditor(TextArea editor, LongSupplier playback) {
        this.editor = editor;
        this.playback = playback;
        toolsDisabled = editor.editableProperty().not().or(editingVisible.getReadOnlyProperty().not());
        getStyleClass().add("note-editor");

        editor.getStyleClass().add("note-source");
        editor.textProperty().addListener((obs, prior, value) -> {
            updateStatus();
            schedulePreview();
        });
        editor.addEventFilter(KeyEvent.KEY_PRESSED, this::onKeyPressed);

        split.getStyleClass().add("md-split");
        split.setDividerPositions(0.52);
        BorderPane.setMargin(split, new Insets(6, 0, 4, 0));

        style.getItems().setAll(STYLES);
        style.setPromptText("Абзац");
        style.getStyleClass().add("md-combo");
        style.setOnAction(event -> {
            String choice = style.getValue();
            style.getSelectionModel().clearSelection();
            if (choice != null) applyStyle(choice);
        });

        timecode = tool("timecode", EditorIcons.clock(), "Таймкод", "Вставить ссылку на текущий момент воспроизведения",
                this::insertTimecode);
        undoTool = iconButton("undo", EditorIcons.undo(), "Отменить", "Отменить (Ctrl+Z)", this::undo);
        redoTool = iconButton("redo", EditorIcons.redo(), "Повторить", "Повторить (Ctrl+Y)", this::redo);

        debounce.setOnFinished(event -> preview.setMarkdown(editor.getText()));
        setTop(toolbar());
        setCenter(split);
        setBottom(footer());
        applyMode(Mode.SPLIT);
        updateStatus();
    }

    /** Лента форматирования: группы значков с подписями, как в ленте текстового редактора. */
    private FlowPane toolbar() {
        var box = new FlowPane(0, 3);
        box.getStyleClass().add("note-toolbar");
        box.setAlignment(Pos.CENTER_LEFT);
        var history = group("Правка", undoTool, redoTool);
        history.getStyleClass().add("md-group-first");
        box.getChildren().setAll(history,
                group("Стили", style),
                group("Формат",
                        tool("bold", EditorIcons.bold(), "Жирный", "Полужирный (Ctrl+B)", () -> wrap("**", "**")),
                        tool("italic", EditorIcons.italic(), "Курсив", "Курсив (Ctrl+I)", () -> wrap("*", "*")),
                        tool("strike", EditorIcons.strike(), "Зачёркнутый", "Зачёркнутый текст",
                                () -> wrap("~~", "~~")),
                        tool("inline-code", EditorIcons.inlineCode(), "Код", "Строчный код (Ctrl+E)",
                                () -> wrap("`", "`")),
                        tool("link", EditorIcons.link(), "Ссылка", "Ссылка (Ctrl+K)", this::insertLink)),
                group("Абзац",
                        tool("bullet", EditorIcons.bulletList(), "Список", "Маркированный список",
                                () -> prefixLines(BULLET)),
                        tool("numbered", EditorIcons.numberList(), "Нумерация", "Нумерованный список",
                                this::numberLines),
                        tool("task", EditorIcons.taskList(), "Задача", "Строка-задача", () -> prefixLines(TASK)),
                        tool("quote", EditorIcons.quote(), "Цитата", "Цитата", () -> prefixLines("> "))),
                group("Вставка",
                        tool("code-block", EditorIcons.codeBlock(), "Блок кода", "Закреплённый блок кода", this::fence),
                        tool("table", EditorIcons.table(), "Таблица", "Таблица Markdown", () -> insertBlock(TABLE)),
                        tool("rule", EditorIcons.rule(), "Разделитель", "Разделитель", () -> insertBlock("---")),
                        timecode));
        for (var control : tools) control.disableProperty().bind(toolsDisabled);
        style.disableProperty().bind(toolsDisabled);
        timecode.disableProperty().bind(toolsDisabled.or(timecodeEnabled.getReadOnlyProperty().not()));
        undoTool.disableProperty().bind(toolsDisabled.or(editor.undoableProperty().not()));
        redoTool.disableProperty().bind(toolsDisabled.or(editor.redoableProperty().not()));
        return box;
    }

    /** Группа ленты: ряд значков и подпись под ним, отделённая тонкой чертой от соседней группы. */
    private static VBox group(String caption, Node... controls) {
        var row = new HBox(2, controls);
        row.getStyleClass().add("md-group-tools");
        var label = new Label(caption);
        label.getStyleClass().add("md-group-caption");
        label.setMaxWidth(Double.MAX_VALUE);
        label.setAlignment(Pos.CENTER);
        var box = new VBox(1, row, label);
        box.getStyleClass().add("md-group");
        return box;
    }

    /** Нижняя строка: счётчик текста слева и отдельный переключатель режимов просмотра справа. */
    private HBox footer() {
        status.getStyleClass().add("note-status");
        hint.getStyleClass().add("note-hint");
        var caption = new Label("Просмотр");
        caption.getStyleClass().add("note-view-caption");
        var box = new HBox(9, status, spacer(), hint, separator(), caption, viewSwitch());
        box.getStyleClass().add("note-footer");
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private static Node separator() {
        var separator = new Separator(Orientation.VERTICAL);
        separator.getStyleClass().add("note-tool-separator");
        separator.setMinHeight(18);
        separator.setPrefHeight(18);
        separator.setMaxHeight(18);
        return separator;
    }

    private static Node spacer() {
        var region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    /** Переключатель режимов вынесен в нижнюю строку и не смешивается с инструментами правки. */
    private HBox viewSwitch() {
        var box = new HBox(2, viewButton("edit", EditorIcons.pencil(), "Правка", "Только поле разметки", Mode.EDIT),
                viewButton("split", EditorIcons.splitPanes(), "Два окна", "Разметка и просмотр рядом", Mode.SPLIT),
                viewButton("preview", EditorIcons.eye(), "Просмотр", "Только отформатированный конспект",
                        Mode.PREVIEW));
        box.getStyleClass().add("note-view");
        return box;
    }

    private Button tool(String id, Node icon, String label, String tooltip, Runnable action) {
        var button = iconButton(id, icon, label, tooltip, action);
        tools.add(button);
        return button;
    }

    private Button iconButton(String id, Node icon, String label, String tooltip, Runnable action) {
        var button = new Button();
        button.setId("md-" + id);
        button.getStyleClass().add("md-tool");
        button.setGraphic(icon);
        button.setAccessibleText(label);
        button.setTooltip(new Tooltip(tooltip));
        button.setOnAction(event -> {
            action.run();
            editor.requestFocus();
        });
        return button;
    }

    private ToggleButton viewButton(String id, Node icon, String label, String tooltip, Mode value) {
        var button = new ToggleButton();
        button.setId("md-view-" + id);
        button.getStyleClass().add("md-mode");
        button.setGraphic(icon);
        button.setAccessibleText(label);
        button.setTooltip(new Tooltip(tooltip));
        button.setToggleGroup(modes);
        button.setSelected(mode == value);
        button.setOnAction(event -> applyMode(value));
        return button;
    }

    private void undo() {
        if (editor.isUndoable()) editor.undo();
        editor.requestFocus();
    }

    private void redo() {
        if (editor.isRedoable()) editor.redo();
        editor.requestFocus();
    }

    private void applyMode(Mode value) {
        mode = value;
        editingVisible.set(value != Mode.PREVIEW);
        split.getItems().setAll(switch (value) {
            case EDIT -> List.of(editor);
            case SPLIT -> List.of(editor, preview);
            case PREVIEW -> List.of(preview);
        });
        if (value != Mode.EDIT) preview.setMarkdown(editor.getText());
    }

    private void schedulePreview() {
        if (mode == Mode.EDIT) return;
        debounce.playFromStart();
    }

    private void onKeyPressed(KeyEvent event) {
        if (!editor.isEditable() || editor.isDisabled()) return;
        boolean control = event.isControlDown() || event.isShortcutDown();
        if (control && !event.isAltDown()) {
            switch (event.getCode()) {
                case B -> { wrap("**", "**"); event.consume(); }
                case I -> { wrap("*", "*"); event.consume(); }
                case E -> { wrap("`", "`"); event.consume(); }
                case K -> { insertLink(); event.consume(); }
                case S -> { event.consume(); saveAction.run(); }
                default -> { }
            }
            return;
        }
        if (event.getCode() == KeyCode.ENTER && !control && !event.isAltDown() && !event.isShiftDown()) {
            continueBlock(event);
        }
    }

    private void continueBlock(KeyEvent event) {
        int caret = editor.getCaretPosition();
        var layout = textLayout();
        int paragraph = layout.paragraphAt(caret);
        String line = layout.lines().get(paragraph);
        if (caret != layout.ends().get(paragraph)) return;
        String prefix = null;
        Matcher item = LIST_LINE.matcher(line);
        if (item.matches()) {
            if (item.group(4).isBlank()) {
                editor.replaceText(layout.starts().get(paragraph), caret, "");
                event.consume();
                return;
            }
            String marker = item.group(2);
            if (marker.matches("\\d{1,9}[.)]")) {
                String tail = marker.substring(marker.length() - 1);
                marker = Integer.parseInt(marker.substring(0, marker.length() - 1)) + 1 + tail;
            }
            prefix = item.group(1) + marker + " " + (item.group(3) == null ? "" : "[ ] ");
        } else {
            Matcher quote = QUOTE_LINE.matcher(line);
            if (quote.matches() && !quote.group(2).isBlank()) prefix = quote.group(1) + "> ";
        }
        if (prefix == null) return;
        editor.replaceText(caret, caret, "\n" + prefix);
        editor.positionCaret(caret + 1 + prefix.length());
        event.consume();
    }

    private void applyStyle(String choice) {
        switch (choice) {
            case "Заголовок 1" -> prefixLines("# ");
            case "Заголовок 2" -> prefixLines("## ");
            case "Заголовок 3" -> prefixLines("### ");
            case "Цитата" -> prefixLines("> ");
            case "Список" -> prefixLines(BULLET);
            case "Чек-лист" -> prefixLines(TASK);
            case "Нумерованный список" -> numberLines();
            case "Код" -> fence();
            default -> transform(MarkdownEditor::strip);
        }
    }

    private void prefixLines(String prefix) {
        transform(line -> matches(line, prefix) ? remove(line, prefix) : prefix + strip(line));
    }

    private void numberLines() {
        var layout = textLayout();
        int first = layout.paragraphAt(editor.getSelection().getStart());
        int last = layout.paragraphAt(editor.getSelection().getEnd());
        boolean all = true;
        for (int p = first; p <= last; p++) if (!ORDERED_PREFIX.matcher(layout.lines().get(p)).matches()) all = false;
        var updates = new ArrayList<Integer>();
        var values = new ArrayList<String>();
        int number = 1;
        for (int p = first; p <= last; p++) {
            String line = layout.lines().get(p);
            String updated = all ? line.replaceFirst("^(\\s*)\\d{1,9}[.)]\\s+", "$1") : number++ + ". " + strip(line);
            if (!updated.equals(line)) {
                updates.add(p);
                values.add(updated);
            }
        }
        apply(updates, values, layout, first, last);
    }

    private void transform(UnaryOperator<String> action) {
        var layout = textLayout();
        int first = layout.paragraphAt(editor.getSelection().getStart());
        int last = layout.paragraphAt(editor.getSelection().getEnd());
        var updates = new ArrayList<Integer>();
        var values = new ArrayList<String>();
        for (int p = first; p <= last; p++) {
            String line = layout.lines().get(p);
            String updated = action.apply(line);
            if (!updated.equals(line)) {
                updates.add(p);
                values.add(updated);
            }
        }
        apply(updates, values, layout, first, last);
    }

    /** Строки заменяются снизу вверх, чтобы изменение длины не сдвигало ещё не обработанные строки. */
    private void apply(List<Integer> updates, List<String> values, Layout layout, int first, int last) {
        for (int i = updates.size() - 1; i >= 0; i--) {
            int paragraph = updates.get(i);
            editor.replaceText(layout.starts().get(paragraph), layout.ends().get(paragraph), values.get(i));
        }
        var updated = textLayout();
        editor.selectRange(updated.starts().get(first), updated.ends().get(Math.min(last, updated.lines().size() - 1)));
    }

    private static String strip(String line) {
        String value = HEADING_PREFIX.matcher(line).replaceFirst("");
        value = QUOTE_PREFIX.matcher(value).replaceFirst("");
        return LIST_PREFIX.matcher(value).replaceFirst("");
    }

    private static boolean matches(String line, String prefix) {
        if (prefix.equals(TASK)) return line.matches("^\\s*[-+*]\\s+\\[[ xX]\\]\\s+.*");
        if (prefix.equals(BULLET)) return line.matches("^\\s*[-+*]\\s+.*");
        if (prefix.equals("> ")) return QUOTE_PREFIX.matcher(line).find();
        return line.startsWith(prefix);
    }

    private static String remove(String line, String prefix) {
        if (prefix.equals(TASK)) return line.replaceFirst("^(\\s*)[-+*]\\s+\\[[ xX]\\]\\s+", "$1");
        if (prefix.equals(BULLET)) return line.replaceFirst("^(\\s*)[-+*]\\s+", "$1");
        if (prefix.equals("> ")) return line.replaceFirst("^(\\s*)>\\s?", "$1");
        return line.replaceFirst("^\\s{0,3}#{1,6}\\s+", "");
    }

    private void fence() {
        String selected = editor.getSelectedText();
        insertBlock(selected.isEmpty() ? "```\nкод\n```" : "```\n" + selected + "\n```");
    }

    private void insertLink() {
        String selected = editor.getSelectedText();
        if (selected.isBlank()) {
            int caret = editor.getCaretPosition();
            editor.replaceText(caret, caret, "[ссылка](https://)");
            editor.selectRange(caret + 1, caret + 7);
            return;
        }
        if (URL.matcher(selected).matches()) {
            String url = selected.startsWith("www.") ? "http://" + selected : selected;
            insertInline("[" + selected + "](" + url + ")");
            return;
        }
        insertInline("[" + selected + "](https://)");
    }

    private void insertInline(String value) {
        int start = editor.getSelection().getStart();
        int end = editor.getSelection().getEnd();
        editor.replaceText(start, end, value);
        editor.positionCaret(start + value.length());
    }

    private void insertBlock(String value) {
        int start = editor.getSelection().getStart();
        int end = editor.getSelection().getEnd();
        var layout = textLayout();
        boolean atLineStart = start == layout.starts().get(layout.paragraphAt(start));
        String block = (start == 0 || atLineStart ? "" : "\n") + value + "\n";
        editor.replaceText(start, end, block);
        editor.positionCaret(start + block.length());
    }

    private void insertTimecode() {
        long time = playback.getAsLong();
        if (time < 0) return;
        String label = RecordingPlayer.timestamp(time);
        String selected = editor.getSelectedText();
        insertInline(selected.isBlank() ? "[" + label + "](#t=" + time + ")"
                : "[" + selected + " · " + label + "](#t=" + time + ")");
    }

    private void wrap(String before, String after) {
        int start = editor.getSelection().getStart();
        int end = editor.getSelection().getEnd();
        String selected = editor.getSelectedText();
        if (selected.isEmpty()) {
            editor.replaceText(start, end, before + after);
            editor.positionCaret(start + before.length());
            return;
        }
        if (selected.length() >= before.length() + after.length() && selected.startsWith(before)
                && selected.endsWith(after)) {
            String inner = selected.substring(before.length(), selected.length() - after.length());
            editor.replaceText(start, end, inner);
            editor.selectRange(start, start + inner.length());
            return;
        }
        editor.replaceText(start, end, before + selected + after);
        editor.selectRange(start, start + before.length() + selected.length() + after.length());
    }

    private Layout textLayout() {
        String text = editor.getText();
        var starts = new ArrayList<Integer>();
        var ends = new ArrayList<Integer>();
        var lines = new ArrayList<String>();
        int start = 0;
        while (true) {
            int newline = text.indexOf('\n', start);
            int end = newline < 0 ? text.length() : newline;
            starts.add(start);
            ends.add(end);
            lines.add(text.substring(start, end));
            if (newline < 0) return new Layout(starts, ends, lines);
            start = newline + 1;
        }
    }

    private void updateStatus() {
        String text = editor.getText();
        int words = 0;
        boolean inside = false;
        for (int i = 0; i < text.length(); i++) {
            boolean letter = Character.isLetterOrDigit(text.charAt(i));
            if (letter && !inside) words++;
            inside = letter;
        }
        var value = new StringBuilder("%d символов · %d слов · %d строк".formatted(text.length(), words,
                textLayout().lines().size()));
        if (dirty) {
            value.append(" · есть несохранённые правки");
            if (!status.getStyleClass().contains("note-status-dirty")) status.getStyleClass().add("note-status-dirty");
        } else status.getStyleClass().remove("note-status-dirty");
        status.setText(value.toString());
    }

    void setDirty(boolean value) {
        if (dirty == value) return;
        dirty = value;
        updateStatus();
    }

    void setTimecodeAvailable(boolean value) {
        timecodeEnabled.set(value);
    }

    void setSaveAction(Runnable action) {
        saveAction = action == null ? () -> { } : action;
    }

    void setOnSeek(LongConsumer handler) {
        preview.setOnSeek(handler);
    }

    MarkdownView preview() {
        return preview;
    }

    void refreshPreview() {
        preview.setMarkdown(editor.getText());
    }
}
