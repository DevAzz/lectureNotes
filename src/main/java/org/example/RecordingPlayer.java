package org.example;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.util.Duration;

import java.util.List;
import java.util.function.Consumer;

final class RecordingPlayer extends VBox {
    private final Slider position = new Slider(0, 0, 0);
    private final Slider volume = new Slider(0, 1, 1);
    private final Label time = new Label();
    private final Button play = new Button("Воспроизвести");
    private final Consumer<String> error;
    private List<Recording.Chunk> chunks = List.of();
    private MediaPlayer player;
    private boolean playing;
    private boolean updating;

    RecordingPlayer(Consumer<String> error) {
        super(8);
        this.error = error;
        var controls = new HBox(12, play, time, new Label("Громкость"), volume);
        controls.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(volume, Priority.ALWAYS);
        getChildren().addAll(position, controls);
        visibleProperty().bind(position.maxProperty().greaterThan(0));
        managedProperty().bind(visibleProperty());
        setChunks(List.of());
        play.setOnAction(event -> {
            if (playing) {
                release();
            } else {
                double value = position.getValue();
                seek(value < position.getMax() ? (long) value : 0, true);
            }
        });
        position.valueProperty().addListener((obs, prior, value) -> {
            if (updating) return;
            showTime();
            if (!position.isValueChanging()) seek(value.longValue(), playing);
        });
        position.valueChangingProperty().addListener((obs, prior, changing) -> {
            if (!changing) seek((long) position.getValue(), playing);
        });
        volume.valueProperty().addListener((obs, prior, value) -> {
            if (player != null) player.setVolume(value.doubleValue());
        });
    }

    void setChunks(List<Recording.Chunk> value) {
        release();
        chunks = value;
        long end = chunks.isEmpty() ? 0 : chunks.getLast().endMillis();
        updating = true;
        position.setMax(end);
        position.setValue(0);
        updating = false;
        position.setDisable(end == 0);
        play.setDisable(end == 0);
        showTime();
    }

    void seek(long millis, boolean resume) {
        if (chunks.isEmpty()) return;
        int index = 0;
        while (index < chunks.size() - 1 && chunks.get(index).endMillis() <= millis) index++;
        Recording.Chunk chunk = chunks.get(index);
        long actual = Math.clamp(millis, chunk.startMillis(), chunk.endMillis());
        release();
        setPosition(actual);
        if (!resume || actual >= position.getMax()) return;
        int next = index + 1;
        try {
            var media = new Media(chunk.path().toUri().toASCIIString());
            var opened = new MediaPlayer(media);
            player = opened;
            playing = true;
            play.setText("Пауза");
            opened.setVolume(volume.getValue());
            opened.setStartTime(Duration.millis(actual - chunk.startMillis()));
            opened.setOnReady(() -> { if (player == opened) opened.play(); });
            opened.currentTimeProperty().addListener((obs, prior, elapsed) -> {
                if (player == opened && !position.isValueChanging()) {
                    setPosition(Math.min(chunk.endMillis(), chunk.startMillis() + (long) elapsed.toMillis()));
                }
            });
            opened.setOnEndOfMedia(() -> {
                if (player != opened) return;
                if (next < chunks.size()) seek(chunks.get(next).startMillis(), true);
                else {
                    setPosition((long) position.getMax());
                    release();
                }
            });
            Runnable failed = () -> { if (player == opened) playbackFailed(); };
            media.setOnError(failed);
            opened.setOnError(failed);
        } catch (RuntimeException failure) {
            playbackFailed();
        }
    }

    private void playbackFailed() {
        release();
        error.accept("Не удалось воспроизвести WAV. Проверьте устройство вывода звука.");
    }

    private void setPosition(long millis) {
        updating = true;
        position.setValue(millis);
        updating = false;
        showTime();
    }

    private void showTime() {
        time.setText(timestamp((long) position.getValue()) + " / " + timestamp((long) position.getMax()));
    }

    /** Текущая позиция общей шкалы записи в миллисекундах или -1, если аудио нет. */
    long currentTimeMillis() {
        return chunks.isEmpty() ? -1 : (long) position.getValue();
    }

    static String timestamp(long millis) {
        long seconds = millis / 1000;
        return "%02d:%02d:%02d".formatted(seconds / 3600, seconds / 60 % 60, seconds % 60);
    }

    void close() {
        release();
    }

    private void release() {
        playing = false;
        if (player != null) {
            MediaPlayer previous = player;
            player = null;
            previous.dispose();
        }
        play.setText("Воспроизвести");
    }
}
