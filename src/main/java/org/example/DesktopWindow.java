package org.example;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

final class DesktopWindow extends JFrame {
    private static final Pattern LEVEL = Pattern.compile("\\{\"event\":\"level\",\"peak\":(\\d+),\"frames\":(\\d+)\\}");
    private final Path data;
    private final Recording.Library library;
    private final ExecutorService workers = Executors.newSingleThreadExecutor();
    private final JTextField subject = new JTextField();
    private final JTextField title = new JTextField();
    private final JTextField invitation = new JTextField();
    private final JCheckBox permission = new JCheckBox("Разрешение на запись получено");
    private final JButton open = new JButton("Подключиться");
    private final JButton start = new JButton("Я на встрече · начать запись");
    private final JButton stop = new JButton("Остановить запись");
    private final JButton leave = new JButton("Завершить встречу");
    private final JLabel status = new JLabel("Готово к подключению");
    private final JProgressBar level = new JProgressBar(0, 32768);
    private final DefaultListModel<Recording> recordings = new DefaultListModel<>();
    private final JList<Recording> list = new JList<>(recordings);
    private final JTextField query = new JTextField();
    private final JLabel summary = new JLabel("Выберите запись");
    private final JTextArea gaps = new JTextArea(3, 35);
    private final RecordingPlayer player = new RecordingPlayer(this::notice);
    private final LectureDocuments documents = new LectureDocuments(this::notice, player);

    private List<Recording> all = List.of();
    private MeetingBrowser browser;
    private Recording recording;
    private Process capture;
    private boolean busy;
    private boolean stopped;
    private boolean finishing;
    private boolean closing;
    private long selectionVersion;
    private boolean filtering;
    private Recording shown;

    DesktopWindow(Path data) throws IOException {
        super("Конспекты лекций");
        this.data = data.toAbsolutePath();
        library = new Recording.Library(this.data);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) { closeWindow(); }
        });
        var sections = new JTabbedPane();
        sections.addTab("Новая лекция", meetingPanel());
        sections.addTab("Библиотека", libraryPanel());
        add(sections);
        setMinimumSize(new Dimension(700, 460));
        setSize(880, 540);
        setLocationByPlatform(true);
        buttons();
        refresh(true);
    }

    private JPanel meetingPanel() {
        var form = new JPanel(new BorderLayout(0, 14));
        form.setBorder(new EmptyBorder(24, 24, 24, 24));
        var inputs = new JPanel(new GridLayout(0, 1, 0, 5));
        field(inputs, "Предмет", subject);
        field(inputs, "Название", title);
        field(inputs, "Ссылка SaluteJazz", invitation);
        inputs.add(permission);
        form.add(inputs, BorderLayout.NORTH);
        var controls = new JPanel(new BorderLayout(0, 10));
        var buttons = new JPanel(new GridLayout(2, 2, 8, 8));
        buttons.add(open);
        buttons.add(start);
        buttons.add(stop);
        buttons.add(leave);
        controls.add(buttons, BorderLayout.NORTH);
        var meter = new JPanel(new BorderLayout(8, 8));
        meter.add(status, BorderLayout.NORTH);
        meter.add(new JLabel("Уровень звука"), BorderLayout.WEST);
        meter.add(level);
        controls.add(meter, BorderLayout.SOUTH);
        form.add(controls, BorderLayout.SOUTH);
        open.addActionListener(event -> openMeeting());
        start.addActionListener(event -> startCapture());
        stop.addActionListener(event -> stopCapture());
        leave.addActionListener(event -> leaveMeeting());
        return form;
    }

    private static void field(JPanel panel, String text, JTextField input) {
        var label = new JLabel(text);
        label.setLabelFor(input);
        panel.add(label);
        panel.add(input);
    }

    private JPanel libraryPanel() {
        var panel = new JPanel(new BorderLayout(8, 12));
        panel.setBorder(new EmptyBorder(16, 16, 16, 16));
        var search = new JPanel(new BorderLayout(8, 0));
        search.add(new JLabel("Предмет или название"), BorderLayout.WEST);
        search.add(query);
        panel.add(search, BorderLayout.NORTH);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer((source, item, index, selected, focused) ->
                new DefaultListCellRenderer().getListCellRendererComponent(source, item.label(), index, selected, focused));
        var playback = new JPanel(new BorderLayout(8, 8));
        playback.add(summary, BorderLayout.NORTH);
        gaps.setEditable(false);
        gaps.setLineWrap(true);
        gaps.setWrapStyleWord(true);
        playback.add(new JScrollPane(gaps), BorderLayout.CENTER);
        playback.add(player, BorderLayout.SOUTH);
        var tabs = new JTabbedPane();
        tabs.addTab("Запись", playback);
        tabs.addTab("Расшифровка", documents.transcriptPanel());
        tabs.addTab("Конспект", documents.notePanel());
        var split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(list), tabs);
        split.setDividerLocation(220);
        panel.add(split);
        list.addListSelectionListener(event -> {
            if (event.getValueIsAdjusting() || filtering) return;
            if (!documents.canLeaveSelection()) {
                filtering = true;
                list.setSelectedValue(shown, true);
                filtering = false;
                return;
            }
            showRecording(list.getSelectedValue());
        });
        query.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { filter(); }
            @Override public void removeUpdate(DocumentEvent event) { filter(); }
            @Override public void changedUpdate(DocumentEvent event) { filter(); }
        });
        return panel;
    }

    private void openMeeting() {
        if (title.getText().isBlank()) { notice("Введите название лекции."); return; }
        final URI link;
        try { link = MeetingBrowser.invitation(invitation.getText()); }
        catch (IllegalArgumentException error) { notice("Введите HTTPS-ссылку на встречу в SaluteJazz."); return; }
        busy = true;
        stopped = false;
        finishing = false;
        status.setText("Открываем отдельное окно Chrome");
        buttons();
        workers.execute(() -> {
            try {
                Capture.helper();
                MeetingBrowser launched = MeetingBrowser.open(data, link);
                onUi(() -> {
                    browser = launched;
                    busy = false;
                    invitation.setText("");
                    status.setText("Войдите в браузере и подтвердите допуск к встрече");
                    launched.process().onExit().thenRun(() -> onUi(() -> {
                        if (browser == launched && !busy && !finishing) leaveMeeting();
                    }));
                    if (closing) leaveMeeting();
                    buttons();
                });
            } catch (Exception error) {
                onUi(() -> {
                    busy = false;
                    status.setText("Не удалось открыть встречу");
                    if (closing) completeClose();
                    else notice(error.getMessage());
                    buttons();
                });
            }
        });
    }

    private void startCapture() {
        if (!permission.isSelected()) { notice("Подтвердите, что получили разрешение на запись."); return; }
        if (!browser.process().isAlive()) { leaveMeeting(); return; }
        busy = true;
        String lectureSubject = subject.getText().strip();
        String lectureTitle = title.getText().strip();
        MeetingBrowser session = browser;
        status.setText("Запускаем захват звука");
        buttons();
        workers.execute(() -> {
            Recording created = null;
            try {
                created = library.create(lectureSubject, lectureTitle);
                Process process = Capture.start(session.process(), created.directory());
                Recording item = created;
                onUi(() -> {
                    capture = process;
                    recording = item;
                    busy = false;
                    buttons();
                    if (closing || !session.process().isAlive()) leaveMeeting();
                });
                Thread.startVirtualThread(() -> monitorCapture(item, process));
            } catch (Exception failure) {
                if (created != null) try { created.storeState("EMPTY"); } catch (IOException ignored) { }
                onUi(() -> {
                    busy = false;
                    status.setText("Захват не запустился");
                    if (closing || !session.process().isAlive()) leaveMeeting();
                    else notice("Не удалось запустить модуль записи.");
                    buttons();
                });
            }
        });
    }

    private void monitorCapture(Recording item, Process process) {
        int exit = 1;
        try (var events = process.inputReader(StandardCharsets.UTF_8)) {
            String line;
            while ((line = events.readLine()) != null) {
                if (line.startsWith("{\"event\":\"started\"")) {
                    onUi(() -> { if (capture == process && !busy) status.setText("Идёт запись"); });
                }
                var match = LEVEL.matcher(line);
                if (match.matches()) {
                    int peak = Integer.parseInt(match.group(1));
                    long millis = Long.parseLong(match.group(2)) * 1000 / Recording.SAMPLE_RATE;
                    onUi(() -> {
                        if (capture != process || busy) return;
                        level.setValue(peak);
                        status.setText("Идёт запись · " + RecordingPlayer.timestamp(millis));
                    });
                }
            }
            exit = process.waitFor();
        } catch (IOException | InterruptedException error) {
            process.destroy();
        }
        int result = exit;
        onUi(() -> captureEnded(item, process, result));
    }

    private void stopCapture() {
        stopped = true;
        recordStop();
    }

    private void recordStop() {
        busy = true;
        status.setText("Сохраняем запись");
        Process process = capture;
        Recording item = recording;
        // Closing stdin also stops the helper if the JVM exits unexpectedly.
        try { process.getOutputStream().close(); }
        catch (IOException error) { process.destroy(); }
        if (stopped) workers.execute(() -> {
            try { item.storeState("STOPPING"); }
            catch (IOException error) { onUi(() -> notice("Не удалось сохранить отметку об остановке.")); }
        });
        Thread.startVirtualThread(() -> {
            try {
                if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly();
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        });
        buttons();
    }

    private void captureEnded(Recording item, Process process, int exit) {
        if (capture != process) return;
        capture = null;
        recording = null;
        level.setValue(0);
        busy = true;
        boolean partial = stopped || !finishing || exit != 0;
        stopped = true;
        workers.execute(() -> {
            String stored = "PARTIAL";
            try {
                if (exit != 0) Capture.recover(item.directory());
                stored = item.chunks().isEmpty() ? "EMPTY" : partial ? "PARTIAL" : "COMPLETE";
                item.storeState(stored);
            } catch (Exception error) { onUi(() -> notice("Не удалось завершить сохранение записи.")); }
            String state = stored;
            onUi(() -> {
                busy = false;
                status.setText(exit != 0 ? "Захват прервался" :
                        state.equals("EMPTY") ? "Аудио не получено" : "Запись сохранена в библиотеке");
                if (finishing || closing || !browser.process().isAlive()) leaveMeeting();
                refresh(false);
                buttons();
            });
        });
    }

    private void leaveMeeting() {
        finishing = true;
        if (capture != null) { recordStop(); return; }
        MeetingBrowser session = browser;
        browser = null;
        busy = true;
        buttons();
        workers.execute(() -> {
            try { if (session != null) session.close(); }
            catch (Exception error) { onUi(() -> notice("Окно Chrome или временный профиль не удалось полностью закрыть.")); }
            finally {
                onUi(() -> {
                    busy = false;
                    finishing = false;
                    permission.setSelected(false);
                    if (status.getText().startsWith("Войдите")) status.setText("Встреча завершена");
                    if (closing) completeClose();
                    buttons();
                });
            }
        });
    }

    private void buttons() {
        boolean idle = !busy && !closing;
        open.setEnabled(idle && browser == null);
        start.setEnabled(idle && browser != null && !stopped && capture == null);
        stop.setEnabled(idle && capture != null);
        leave.setEnabled(idle && browser != null);
        title.setEnabled(idle && browser == null);
        subject.setEnabled(idle && browser == null);
        invitation.setEnabled(idle && browser == null);
        permission.setEnabled(idle && capture == null);
    }

    private void closeWindow() {
        if (closing || !documents.canLeaveSelection()) return;
        closing = true;
        buttons();
        if (busy) return;
        if (browser != null) leaveMeeting();
        else completeClose();
    }

    private void completeClose() {
        documents.close();
        player.close();
        workers.execute(() -> {
            try { documents.awaitClose(); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            finally { try { library.close(); } catch (IOException ignored) { } }
            onUi(this::dispose);
        });
        workers.shutdown();
    }

    private void refresh(boolean recover) {
        workers.execute(() -> {
            try {
                var items = library.load(recover);
                onUi(() -> { if (!closing) { all = items; filter(); } });
            } catch (Exception error) {
                onUi(() -> { if (!closing) notice("Не удалось загрузить библиотеку."); });
            }
        });
    }

    private void filter() {
        Recording selected = shown;
        String search = normalize(query.getText());
        filtering = true;
        recordings.clear();
        for (Recording entry : all) if (normalize(entry.label()).contains(search)) recordings.addElement(entry);
        if (selected != null) list.setSelectedValue(selected, true);
        filtering = false;
    }

    private static String normalize(String value) {
        return value.strip().toLowerCase(Locale.ROOT).replace('ё', 'е');
    }

    private void showRecording(Recording item) {
        if (java.util.Objects.equals(item, shown)) return;
        shown = item;
        long request = ++selectionVersion;
        player.setChunks(List.of());
        documents.select(item);
        gaps.setText("");
        summary.setText(item == null ? "Выберите запись" : switch (item.state()) {
            case "COMPLETE" -> "Запись готова";
            case "PARTIAL" -> "Частичная запись";
            case "EMPTY" -> "Аудио не получено";
            default -> "Запись в процессе";
        });
        if (item == null) return;
        workers.execute(() -> {
            try {
                var chunks = item.chunks();
                StringBuilder missing = new StringBuilder();
                long end = 0;
                for (Recording.Chunk chunk : chunks) {
                    if (chunk.startMillis() - end >= 100) {
                        missing.append(RecordingPlayer.timestamp(end)).append(" — ")
                                .append(RecordingPlayer.timestamp(chunk.startMillis())).append('\n');
                    }
                    end = chunk.endMillis();
                }
                onUi(() -> {
                    if (request != selectionVersion || closing) return;
                    player.setChunks(chunks);
                    if (!missing.isEmpty()) gaps.setText("Пропуски в записи\n" + missing);
                });
            } catch (IOException error) { onUi(() -> notice("Не удалось открыть WAV.")); }
        });
    }

    private void notice(String text) {
        if (!closing) JOptionPane.showMessageDialog(this, text, "Конспекты лекций", JOptionPane.INFORMATION_MESSAGE);
    }

    private static void onUi(Runnable update) { SwingUtilities.invokeLater(update); }
}
