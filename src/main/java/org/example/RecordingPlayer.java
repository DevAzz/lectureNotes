package org.example;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineEvent;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.util.List;
import java.util.function.Consumer;

final class RecordingPlayer extends JPanel {
    private final JSlider position = new JSlider(0, 0);
    private final JSlider volume = new JSlider(0, 100, 100);
    private final JLabel time = new JLabel("—");
    private final JButton play = new JButton("Воспроизвести");
    private final Consumer<String> error;
    private final Timer clock;
    private List<Recording.Chunk> chunks = List.of();
    private Clip clip;
    private int current;
    private boolean playing;
    private boolean updating;
    private long loadVersion;

    RecordingPlayer(Consumer<String> error) {
        super(new BorderLayout(8, 8));
        this.error = error;
        var controls = new JPanel(new GridLayout(1, 3, 8, 0));
        controls.add(play);
        controls.add(time);
        var audio = new JPanel(new BorderLayout());
        audio.add(new JLabel("Громкость"), BorderLayout.WEST);
        audio.add(volume);
        controls.add(audio);
        add(position, BorderLayout.CENTER);
        add(controls, BorderLayout.SOUTH);
        play.setEnabled(false);
        position.setEnabled(false);
        play.addActionListener(event -> {
            if (playing) {
                loadVersion++;
                playing = false;
                if (clip != null) clip.stop();
                play.setText("Воспроизвести");
                return;
            }
            int start = position.getValue() < position.getMaximum() ? position.getValue() : 0;
            seek(start, true);
        });
        position.addChangeListener(event -> {
            if (!updating) {
                showTime();
                if (!position.getValueIsAdjusting()) seek(position.getValue(), playing);
            }
        });
        volume.addChangeListener(event -> applyVolume());
        clock = new Timer(150, event -> {
            if (clip != null && playing && !position.getValueIsAdjusting()) {
                setPosition(chunks.get(current).startMillis() + clip.getMicrosecondPosition() / 1000);
            }
        });
        clock.start();
    }

    void setChunks(List<Recording.Chunk> value) {
        release();
        chunks = value;
        long end = chunks.isEmpty() ? 0 : chunks.getLast().endMillis();
        updating = true;
        position.setMaximum((int) Math.min(Integer.MAX_VALUE, end));
        updating = false;
        setPosition(0);
        position.setEnabled(end > 0);
        play.setEnabled(end > 0);
    }

    void seek(long millis, boolean resume) {
        if (chunks.isEmpty()) return;
        int index = 0;
        while (index < chunks.size() - 1 && chunks.get(index).endMillis() <= millis) index++;
        Recording.Chunk chunk = chunks.get(index);
        long actual = Math.clamp(millis, chunk.startMillis(), chunk.endMillis());
        setPosition(actual);
        if (!resume) return;
        release();
        playing = true;
        play.setText("Пауза");
        long request = loadVersion;
        int selected = index;
        Thread.startVirtualThread(() -> {
            Clip opened = null;
            try (var audio = AudioSystem.getAudioInputStream(chunk.path().toFile())) {
                opened = AudioSystem.getClip();
                opened.open(audio);
                Clip ready = opened;
                SwingUtilities.invokeLater(() -> attach(ready, request, selected, actual - chunk.startMillis()));
            } catch (Exception failure) {
                if (opened != null) opened.close();
                SwingUtilities.invokeLater(() -> {
                    if (request != loadVersion) return;
                    release();
                    error.accept("Не удалось воспроизвести WAV. Проверьте устройство вывода звука.");
                });
            }
        });
    }

    private void attach(Clip ready, long request, int index, long offset) {
        if (request != loadVersion) { ready.close(); return; }
        clip = ready;
        current = index;
        ready.setMicrosecondPosition(Math.min(offset * 1000, ready.getMicrosecondLength()));
        ready.addLineListener(event -> {
            if (event.getType() != LineEvent.Type.STOP) return;
            SwingUtilities.invokeLater(() -> {
                if (ready != clip || !playing || ready.getFramePosition() < ready.getFrameLength()) return;
                if (current + 1 < chunks.size()) seek(chunks.get(current + 1).startMillis(), true);
                else {
                    setPosition(position.getMaximum());
                    release();
                }
            });
        });
        applyVolume();
        ready.start();
    }

    private void applyVolume() {
        if (clip != null && clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            var gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
            float value = (float) (20 * Math.log10(Math.max(0.00001, volume.getValue() / 100.0)));
            gain.setValue(Math.clamp(value, gain.getMinimum(), gain.getMaximum()));
        }
    }

    private void setPosition(long millis) {
        updating = true;
        position.setValue((int) Math.min(millis, Integer.MAX_VALUE));
        updating = false;
        showTime();
    }

    private void showTime() {
        time.setText(timestamp(position.getValue()) + " / " + timestamp(position.getMaximum()));
    }

    static String timestamp(long millis) {
        long seconds = millis / 1000;
        return "%02d:%02d:%02d".formatted(seconds / 3600, seconds / 60 % 60, seconds % 60);
    }

    private void release() {
        loadVersion++;
        playing = false;
        if (clip != null) {
            Clip previous = clip;
            clip = null;
            previous.close();
        }
        play.setText("Воспроизвести");
    }

    void close() {
        clock.stop();
        release();
    }
}
