package org.example;

import javafx.scene.Node;
import javafx.scene.layout.Pane;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Ellipse;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;

/**
 * Векторные значки ленты форматирования и переключателя просмотра.
 * Фигуры рисуются напрямую, поэтому значки не зависят от иконочных шрифтов системы.
 * Цвет фигур задаётся стилями «md-icon-solid», «md-icon-line» и «md-icon-glyph».
 */
final class EditorIcons {
    private static final double SIZE = 18;
    private static final String SOLID = "md-icon-solid";
    private static final String LINE = "md-icon-line";
    private static final String GLYPH = "md-icon-glyph";

    private EditorIcons() { }

    static Node bold() {
        return canvas(glyph("B", Font.font("Segoe UI", FontWeight.BOLD, 15)));
    }

    static Node italic() {
        return canvas(glyph("I", Font.font("Georgia", FontPosture.ITALIC, 15)));
    }

    static Node strike() {
        var letter = glyph("S", Font.font("Segoe UI", FontWeight.NORMAL, 14));
        letter.setStrikethrough(true);
        return canvas(letter);
    }

    static Node inlineCode() {
        return canvas(glyph("</>", Font.font("Consolas", FontWeight.BOLD, 11)));
    }

    static Node link() {
        return canvas(pill(6.4, 11.6), pill(11.6, 6.4), new Line(7.6, 10.4, 10.4, 7.6));
    }

    static Node bulletList() {
        return canvas(dot(2.8, 3.6, 1.5), bar(6.4, 2.75, 9.6),
                dot(2.8, 9, 1.5), bar(6.4, 8.15, 9.6),
                dot(2.8, 14.4, 1.5), bar(6.4, 13.55, 9.6));
    }

    static Node numberList() {
        return canvas(digit("1", 0.6, 6.2), bar(6.6, 2.6, 10),
                digit("2", 0.6, 11.75), bar(6.6, 8.15, 10),
                digit("3", 0.6, 17.3), bar(6.6, 13.7, 10));
    }

    static Node taskList() {
        return canvas(box(1.4, 1.8, 5.8, 5.8, 1.6), new Line(2.6, 4.7, 4.1, 6.2), new Line(4.1, 6.2, 6.4, 3.1),
                bar(9.4, 3.85, 7.2), box(1.4, 10.4, 5.8, 5.8, 1.6), bar(9.4, 12.45, 7.2));
    }

    static Node quote() {
        return canvas(glyph("«", Font.font("Segoe UI", FontWeight.BOLD, 21)));
    }

    static Node codeBlock() {
        return canvas(box(1.4, 2.8, 15.2, 12.4, 3), glyph("</>", Font.font("Consolas", FontWeight.BOLD, 9)));
    }

    static Node table() {
        return canvas(box(1.4, 3.2, 15.2, 11.6, 1.6), filled(1.4, 3.2, 15.2, 3.4, 1.6),
                new Line(9, 3.2, 9, 14.8), new Line(1.4, 10, 16.6, 10));
    }

    static Node rule() {
        var line = new Rectangle(1.4, 7.8, 15.2, 2.4);
        line.setArcWidth(2.2);
        line.setArcHeight(2.2);
        line.getStyleClass().add(SOLID);
        return canvas(line);
    }

    static Node clock() {
        return canvas(circle(9, 9, 7), new Line(9, 9, 9, 5.2), new Line(9, 9, 12.1, 10.3));
    }

    static Node undo() {
        return canvas(arc(0, 180), shape(0.4, 7.4, 5.2, 7.4, 2.8, 12.6));
    }

    static Node redo() {
        return canvas(arc(0, 180), shape(12.8, 7.4, 17.6, 7.4, 15.2, 12.6));
    }

    static Node pencil() {
        return canvas(shape(12.5, 2.1, 15.9, 5.5, 7.7, 13.7, 4.3, 10.3),
                shape(4.3, 10.3, 7.7, 13.7, 3.9, 14.1));
    }

    static Node splitPanes() {
        return canvas(box(1.8, 3.6, 6.2, 10.8, 1.6), box(10, 3.6, 6.2, 10.8, 1.6));
    }

    static Node eye() {
        var outline = new Ellipse(9, 9, 7.4, 4.8);
        outline.getStyleClass().add(LINE);
        return canvas(outline, dot(9, 9, 2.1));
    }

    private static Pane canvas(Node... nodes) {
        for (var node : nodes) if (node instanceof Line line) line.getStyleClass().add(LINE);
        var pane = new Pane(nodes);
        pane.setMinSize(SIZE, SIZE);
        pane.setPrefSize(SIZE, SIZE);
        pane.setMaxSize(SIZE, SIZE);
        return pane;
    }

    private static Text glyph(String value, Font font) {
        var text = new Text(value);
        text.setFont(font);
        text.getStyleClass().add(GLYPH);
        var bounds = text.getLayoutBounds();
        text.setX((SIZE - bounds.getWidth()) / 2 - bounds.getMinX());
        text.setY((SIZE - bounds.getHeight()) / 2 - bounds.getMinY());
        return text;
    }

    private static Text digit(String value, double x, double baseline) {
        var text = new Text(value);
        text.setFont(Font.font("Segoe UI", FontWeight.NORMAL, 7.6));
        text.getStyleClass().add(GLYPH);
        text.setX(x);
        text.setY(baseline);
        return text;
    }

    private static Rectangle bar(double x, double y, double width) {
        var shape = new Rectangle(x, y, width, 1.7);
        shape.setArcWidth(1.7);
        shape.setArcHeight(1.7);
        shape.getStyleClass().add(SOLID);
        return shape;
    }

    private static Rectangle filled(double x, double y, double width, double height, double arc) {
        var shape = new Rectangle(x, y, width, height);
        shape.setArcWidth(arc);
        shape.setArcHeight(arc);
        shape.getStyleClass().add(SOLID);
        return shape;
    }

    private static Rectangle box(double x, double y, double width, double height, double arc) {
        var shape = new Rectangle(x, y, width, height);
        shape.setArcWidth(arc);
        shape.setArcHeight(arc);
        shape.getStyleClass().add(LINE);
        return shape;
    }

    private static Rectangle pill(double centerX, double centerY) {
        var shape = new Rectangle(7.6, 4.8);
        shape.setArcWidth(4.8);
        shape.setArcHeight(4.8);
        shape.setX(centerX - 3.8);
        shape.setY(centerY - 2.4);
        shape.setRotate(-45);
        shape.getStyleClass().add(LINE);
        return shape;
    }

    private static Circle dot(double centerX, double centerY, double radius) {
        var shape = new Circle(centerX, centerY, radius);
        shape.getStyleClass().add(SOLID);
        return shape;
    }

    private static Circle circle(double centerX, double centerY, double radius) {
        var shape = new Circle(centerX, centerY, radius);
        shape.getStyleClass().add(LINE);
        return shape;
    }

    private static Arc arc(double startAngle, double length) {
        var shape = new Arc(9, 9.6, 6.2, 6.2, startAngle, length);
        shape.setType(ArcType.OPEN);
        shape.getStyleClass().add(LINE);
        return shape;
    }

    private static Polygon shape(double... points) {
        var shape = new Polygon();
        for (double point : points) shape.getPoints().add(point);
        shape.getStyleClass().add(SOLID);
        return shape;
    }
}
