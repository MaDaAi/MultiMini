package app.multimin.ui;

import javax.swing.Icon;
import java.awt.*;

public final class VectorIcon implements Icon {
    public enum Type { ADD, REMOVE, COPY, EYE, EYE_OFF, UP, DOWN, DISPLAY, WINDOW, IMAGE, VIDEO, GAME, CAMERA, PLAY, BOLT, POINTER, MOUSE, GEAR }
    private final Type type; private final int size; private final java.util.function.Supplier<Color> color;
    public VectorIcon(Type type, int size, Color color) { this(type, size, () -> color); }
    public VectorIcon(Type type, int size, java.util.function.Supplier<Color> color) { this.type = type; this.size = size; this.color = color; }
    @Override public int getIconWidth() { return size; }
    @Override public int getIconHeight() { return size; }

    @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
        Graphics2D g = (Graphics2D) graphics.create(); g.translate(x, y); g.setColor(color.get()); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON); g.setStroke(new BasicStroke(Math.max(1.4f, size / 9f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        int m = 2, c = size / 2;
        switch (type) {
            case ADD: g.drawLine(c, m, c, size - m); g.drawLine(m, c, size - m, c); break;
            case REMOVE: g.drawLine(m, c, size - m, c); break;
            case COPY: g.drawRoundRect(m + 3, m, size - 6, size - 6, 2, 2); g.drawRoundRect(m, m + 3, size - 6, size - 6, 2, 2); break;
            case EYE: g.drawOval(m, c - 4, size - m * 2, 8); g.fillOval(c - 2, c - 2, 4, 4); break;
            case EYE_OFF: g.drawOval(m, c - 4, size - m * 2, 8); g.fillOval(c - 2, c - 2, 4, 4); g.drawLine(3, size - 3, size - 3, 3); break;
            case UP: g.drawLine(m, c + 3, c, c - 3); g.drawLine(c, c - 3, size - m, c + 3); break;
            case DOWN: g.drawLine(m, c - 3, c, c + 3); g.drawLine(c, c + 3, size - m, c - 3); break;
            case DISPLAY: g.drawRoundRect(m, m + 1, size - m * 2, size - 6, 2, 2); g.drawLine(c, size - 5, c, size - 2); g.drawLine(c - 3, size - 2, c + 3, size - 2); break;
            case WINDOW: g.drawRoundRect(m, m + 1, size - m * 2, size - 4, 2, 2); g.drawLine(m, m + 5, size - m, m + 5); break;
            case IMAGE: g.drawRoundRect(m, m, size - m * 2, size - m * 2, 2, 2); g.fillOval(size - 6, 3, 2, 2); g.drawLine(4, size - 4, 8, size - 8); g.drawLine(8, size - 8, size - 4, size - 4); break;
            case VIDEO: case PLAY: Polygon p = new Polygon(new int[]{4, 4, size - 3}, new int[]{3, size - 3, c}, 3); g.drawPolygon(p); break;
            case GAME: g.drawOval(m, c - 3, size - m * 2, 7); g.drawLine(5, c, 9, c); g.drawLine(7, c - 2, 7, c + 2); g.fillOval(size - 7, c - 1, 2, 2); break;
            case CAMERA: g.drawRoundRect(m, 4, size - m * 2, size - 7, 3, 3); g.drawOval(c - 3, c - 2, 6, 6); break;
            case BOLT:
                Polygon bolt = new Polygon(new int[]{c + 1, size - 3, c + 2, c + 2, 3, c - 1}, new int[]{2, 2, c - 1, c - 1, size - 2, c + 1}, 6);
                g.setColor(Color.BLACK); g.setStroke(new BasicStroke(Math.max(2.6f, size / 5.5f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); g.drawPolygon(bolt);
                g.setColor(color.get()); g.fillPolygon(bolt); break;
            case POINTER: Polygon pointer = new Polygon(new int[]{3, 3, size - 3, size - 7, size - 5, size - 8, size - 10, size - 9}, new int[]{2, size - 3, size - 8, size - 10, size - 13, size - 15, size - 12, size - 9}, 8); g.drawPolygon(pointer); break;
            case MOUSE:
                g.setStroke(new BasicStroke(Math.max(2.8f, size / 5f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(Color.BLACK);
                g.drawRoundRect(size / 4, 2, size / 2, size - 4, size / 3, size / 3);
                g.drawLine(c, 3, c, size / 2 + 1);
                g.drawLine(size / 4 + 1, size / 2, size - size / 4 - 1, size / 2);
                g.setStroke(new BasicStroke(Math.max(1.4f, size / 9f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(color.get());
                g.drawRoundRect(size / 4, 2, size / 2, size - 4, size / 3, size / 3);
                g.drawLine(c, 3, c, size / 2 + 1);
                g.drawLine(size / 4 + 1, size / 2, size - size / 4 - 1, size / 2);
                break;
            case GEAR:
                Polygon gear = new Polygon();
                for (int i = 0; i < 24; i++) { double angle = i * Math.PI / 12.0 - Math.PI / 2.0; int radius = i % 3 == 0 || i % 3 == 1 ? size / 2 - 1 : size / 2 - 4; gear.addPoint(c + (int) Math.round(Math.cos(angle) * radius), c + (int) Math.round(Math.sin(angle) * radius)); }
                g.drawPolygon(gear); g.drawOval(c - 3, c - 3, 6, 6); break;
        }
        g.dispose();
    }

    public static Type forSource(app.multimin.model.Source.Kind kind) {
        switch (kind) { case DISPLAY: return Type.DISPLAY; case WINDOW: return Type.WINDOW; case IMAGE: return Type.IMAGE; case VIDEO: return Type.VIDEO; default: return Type.GAME; }
    }
}
