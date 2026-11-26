package org.example;

import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

final class LectureDocuments {
    private static final Pattern NOTE_TIME = Pattern.compile("\\(#t=(\\d+)\\)");
    private final Consumer<String> notice;
    private final RecordingPlayer player;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final DefaultListModel<LocalProcessing.Segment> lines = new DefaultListModel<>();
    private final JList<LocalProcessing.Segment> transcript = new JList<>(lines);
    private final JTextArea note = new JTextArea();
    private final JLabel status = new JLabel(" ");
    private final JButton recognize = new JButton("Распознать");
    private final JButton playLine = new JButton("Прослушать реплику");
    private final JButton exportTranscript = new JButton("Экспорт Markdown");
    private final JButton generate = new JButton("Сформировать конспект");
    private final JButton save = new JButton("Сохранить редакцию");
    private final JButton exportNote = new JButton("Экспорт Markdown");
    private final JButton playReference = new JButton("Прослушать по ссылке");
    private Recording selected;
    private List<LocalProcessing.Segment> segments = List.of();
    private String noteOnDisk = "";
    private long selection;
    private boolean busy;
    private boolean closed;
    private String whisper = System.getenv().getOrDefault("LECTURENOTES_WHISPER", "");
    private String weights = System.getenv().getOrDefault("LECTURENOTES_WHISPER_MODEL", "");
    private String endpoint = "http://127.0.0.1:47831/v1";
    private String model = "iq3_s";
    private String focus = "";
    private boolean cpu;

    LectureDocuments(Consumer<String> notice, RecordingPlayer player) {
        this.notice = notice;
        this.player = player;
    }

    JPanel transcriptPanel() {
        transcript.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        transcript.addListSelectionListener(event -> updateButtons());
        transcript.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) playSegment();
            }
        });
        recognize.addActionListener(event -> startRecognition());
        playLine.addActionListener(event -> playSegment());
        exportTranscript.addActionListener(event -> {
            StringBuilder markdown = new StringBuilder("# ").append(selected.title()).append("\n\n");
            for (var item : segments) markdown.append("- ").append(item).append('\n');
            export("transcript.md", markdown.toString());
        });
        var panel = new JPanel(new BorderLayout());
        panel.add(new JScrollPane(transcript));
        panel.add(toolbar(recognize, playLine, exportTranscript), BorderLayout.SOUTH);
        panel.add(status, BorderLayout.NORTH);
        updateButtons();
        return panel;
    }

    JPanel notePanel() {
        note.setLineWrap(true);
        note.setWrapStyleWord(true);
        generate.addActionListener(event -> startGeneration());
        save.addActionListener(event -> saveNote());
        exportNote.addActionListener(event -> export("note.md", note.getText()));
        playReference.addActionListener(event -> playReference());
        var panel = new JPanel(new BorderLayout());
        panel.add(new JScrollPane(note));
        panel.add(toolbar(generate, save, playReference, exportNote), BorderLayout.SOUTH);
        updateButtons();
        return panel;
    }

    private JPanel toolbar(JButton... buttons) {
        var bar = new JPanel(new FlowLayout(FlowLayout.LEADING));
        for (JButton button : buttons) bar.add(button);
        return bar;
    }

    void select(Recording item) {
        if (item != null && item.equals(selected)) return;
        selected = item;
        long version = ++selection;
        setSegments(List.of());
        setNote("");
        status.setText(" ");
        updateButtons();
        if (item == null) return;
        worker.execute(() -> {
            try {
                Path transcriptPath = item.directory().resolve("transcript.srt");
                var saved = Files.isRegularFile(transcriptPath)
                        ? LocalProcessing.parseSrt(Files.readString(transcriptPath), 0, Long.MAX_VALUE)
                        : List.<LocalProcessing.Segment>of();
                Path notePath = item.directory().resolve("note.md");
                String markdown = Files.isRegularFile(notePath) ? Files.readString(notePath) : "";
                finishOnUi(version, () -> {
                    setSegments(saved);
                    if (note.getText().isEmpty()) setNote(markdown);
                    updateButtons();
                });
            } catch (IOException error) {
                finishOnUi(version, () -> notice.accept("Не удалось открыть документы лекции."));
            }
        });
    }

    private void setSegments(List<LocalProcessing.Segment> value) {
        segments = value;
        lines.clear();
        for (var segment : value) lines.addElement(segment);
    }

    private void setNote(String text) {
        noteOnDisk = text;
        note.setText(text);
        note.setCaretPosition(0);
    }

    private void playSegment() {
        var segment = transcript.getSelectedValue();
        if (segment != null) player.seek(segment.start(), true);
    }

    private void playReference() {
        try {
            int line = note.getLineOfOffset(note.getCaretPosition());
            var matcher = NOTE_TIME.matcher(note.getText(note.getLineStartOffset(line),
                    note.getLineEndOffset(line) - note.getLineStartOffset(line)));
            if (matcher.find()) player.seek(Long.parseLong(matcher.group(1)), true);
            else notice.accept("Поставьте курсор на строку со ссылкой на таймкод.");
        } catch (Exception error) { notice.accept("Не удалось прочитать таймкод."); }
    }

    private void startRecognition() {
        var executable = new JTextField(whisper, 34);
        var modelFile = new JTextField(weights, 34);
        var cpuOnly = new JCheckBox("Использовать CPU", cpu);
        var settings = fields("whisper-cli.exe", executable, "Файл модели", modelFile);
        settings.add(cpuOnly);
        if (JOptionPane.showConfirmDialog(transcript, settings, "Распознавание речи",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        whisper = executable.getText().strip();
        weights = modelFile.getText().strip();
        cpu = cpuOnly.isSelected();
        if (whisper.isEmpty() || weights.isEmpty()) { notice.accept("Укажите пути к whisper-cli.exe и модели."); return; }
        final Path exe;
        final Path bin;
        try { exe = Path.of(whisper).toAbsolutePath(); bin = Path.of(weights).toAbsolutePath(); }
        catch (IllegalArgumentException error) { notice.accept("Проверьте пути к файлам."); return; }
        var item = selected;
        boolean useCpu = cpu;
        run("Распознаём речь", () -> {
            var result = LocalProcessing.transcribe(item, exe, bin, useCpu);
            return () -> setSegments(result);
        });
    }

    private void startGeneration() {
        if (!note.getText().equals(noteOnDisk)) {
            notice.accept("Сохраните текущую редакцию перед генерацией.");
            return;
        }
        var address = new JTextField(endpoint, 28);
        var name = new JTextField(model, 28);
        var topic = new JTextField(focus, 28);
        if (JOptionPane.showConfirmDialog(note, fields("Локальный API", address, "ID модели", name, "Фокус", topic),
                "Сформировать конспект", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
                != JOptionPane.OK_OPTION) return;
        endpoint = address.getText().strip();
        model = name.getText().strip();
        focus = topic.getText().strip();
        final URI base;
        try { base = URI.create(endpoint); }
        catch (IllegalArgumentException error) { notice.accept("Проверьте адрес локального API."); return; }
        var item = selected;
        var source = segments;
        String modelId = model;
        String requestedFocus = focus;
        run("Формируем конспект", () -> {
            String markdown = LocalProcessing.generate(item, source, base, modelId, requestedFocus);
            return () -> setNote(markdown);
        });
    }

    private void saveNote() {
        var item = selected;
        String body = note.getText();
        run("Сохраняем редакцию", () -> {
            LocalProcessing.saveNote(item, body);
            return () -> noteOnDisk = body;
        });
    }

    private void export(String name, String body) {
        var dialog = new JFileChooser();
        dialog.setSelectedFile(new java.io.File(name));
        if (dialog.showSaveDialog(note) != JFileChooser.APPROVE_OPTION) return;
        Path destination = dialog.getSelectedFile().toPath();
        if (Files.exists(destination) && JOptionPane.showConfirmDialog(note, "Заменить существующий файл?",
                "Экспорт", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        run("Экспортируем Markdown", () -> {
            LocalProcessing.writeAtomic(destination, body);
            return () -> { };
        });
    }

    private JPanel fields(Object... pairs) {
        var form = new JPanel(new GridLayout(0, 1, 0, 4));
        for (int index = 0; index < pairs.length; index += 2) {
            var label = new JLabel((String) pairs[index]);
            var field = (JTextField) pairs[index + 1];
            label.setLabelFor(field);
            form.add(label);
            form.add(field);
        }
        return form;
    }

    @FunctionalInterface
    private interface Background {
        Runnable call() throws Exception;
    }

    private void run(String message, Background task) {
        long version = selection;
        busy = true;
        status.setText(message);
        updateButtons();
        worker.execute(() -> {
            try {
                Runnable completed = task.call();
                finishOnUi(version, () -> { completed.run(); status.setText("Готово"); });
            } catch (Exception error) {
                finishOnUi(version, () -> {
                    status.setText("Операция завершилась ошибкой");
                    String detail = error instanceof IOException && error.getMessage() != null
                            && error.getMessage().codePoints().anyMatch(cp -> cp >= 0x410 && cp <= 0x44f)
                            ? error.getMessage() : "Не удалось выполнить операцию.";
                    notice.accept(detail);
                });
            } finally {
                SwingUtilities.invokeLater(() -> {
                    if (closed) return;
                    busy = false;
                    updateButtons();
                });
            }
        });
    }

    private void finishOnUi(long version, Runnable action) {
        SwingUtilities.invokeLater(() -> { if (!closed && version == selection) action.run(); });
    }

    private void updateButtons() {
        boolean available = selected != null && !busy;
        note.setEditable(available);
        recognize.setEnabled(available && List.of("COMPLETE", "PARTIAL").contains(selected.state()));
        playLine.setEnabled(transcript.getSelectedValue() != null);
        exportTranscript.setEnabled(available && !segments.isEmpty());
        generate.setEnabled(available && !segments.isEmpty());
        save.setEnabled(available);
        exportNote.setEnabled(available);
        playReference.setEnabled(selected != null);
    }

    boolean canLeaveSelection() {
        return note.getText().equals(noteOnDisk) || JOptionPane.showConfirmDialog(note,
                "В конспекте есть несохранённые правки. Продолжить?", "Несохранённые правки",
                JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
    }

    void close() {
        closed = true;
        worker.shutdownNow();
    }

    boolean awaitClose() throws InterruptedException {
        return worker.awaitTermination(12, TimeUnit.SECONDS);
    }
}
