package org.example;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.io.IOException;
import java.nio.file.Path;

public class Main {
    public static void main(String[] args) throws IOException {
        Path data = Path.of(System.getProperty("lecturenotes.data",
                Path.of(System.getenv().getOrDefault("LOCALAPPDATA", System.getProperty("user.home")),
                        "LectureNotes").toString()));
        if (args.length == 1 && args[0].equals("--check")) {
            System.out.println("JDK " + Runtime.version().feature() + ", audio helper: " + Capture.helper());
            return;
        }
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                new DesktopWindow(data).setVisible(true);
            } catch (Exception error) {
                JOptionPane.showMessageDialog(null, error.getMessage(), "Конспекты лекций", JOptionPane.ERROR_MESSAGE);
            }
        });
    }
}
