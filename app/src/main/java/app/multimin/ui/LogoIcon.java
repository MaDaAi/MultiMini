package app.multimin.ui;

import javax.swing.Icon;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Renders the supplied SVG paths as vectors without adding an SVG dependency. */
final class LogoIcon implements Icon {
    private static final Pattern FILL = Pattern.compile("\\.cls-(\\d+)\\{fill:#([0-9a-fA-F]{6})");
    private static final Pattern PATH = Pattern.compile("<path\\s+d=\"([^\"]+)\"\\s+class=\"cls-(\\d+)\"/>");
    private static final Pattern TOKEN = Pattern.compile("[MLZmlz]|-?\\d+(?:\\.\\d+)?");
    private final List<Shape> paths = new ArrayList<>();
    private final List<Color> colors = new ArrayList<>();
    private final int size;

    LogoIcon(int size) {
        this.size = size;
        try (InputStream input = LogoIcon.class.getResourceAsStream("/app/multimin/multimin-logo.svg")) {
            if (input == null) throw new IllegalStateException("SVG logo is missing");
            String svg = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            Map<String, Color> fills = new HashMap<>();
            Matcher fill = FILL.matcher(svg);
            while (fill.find()) fills.put(fill.group(1), Color.decode("#" + fill.group(2)));
            Matcher path = PATH.matcher(svg);
            while (path.find()) {
                Path2D.Float shape = parse(path.group(1));
                Color color = fills.get(path.group(2));
                if (color != null) { paths.add(shape); colors.add(color); }
            }
            if (paths.isEmpty()) throw new IllegalStateException("SVG logo has no supported paths");
        } catch (Exception error) { throw new IllegalStateException("Cannot load MultiMin SVG logo", error); }
    }

    private static Path2D.Float parse(String data) {
        Matcher tokens = TOKEN.matcher(data);
        List<String> values = new ArrayList<>();
        while (tokens.find()) values.add(tokens.group());
        Path2D.Float path = new Path2D.Float();
        char command = ' ';
        for (int i = 0; i < values.size();) {
            String value = values.get(i);
            if (value.length() == 1 && Character.isLetter(value.charAt(0))) {
                command = Character.toUpperCase(value.charAt(0)); i++;
                if (command == 'Z') { path.closePath(); command = ' '; }
                continue;
            }
            if (i + 1 >= values.size()) break;
            float x = Float.parseFloat(values.get(i++)), y = Float.parseFloat(values.get(i++));
            if (command == 'M') { path.moveTo(x, y); command = 'L'; }
            else if (command == 'L') path.lineTo(x, y);
        }
        return path;
    }

    @Override public int getIconWidth() { return size; }
    @Override public int getIconHeight() { return size; }

    @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.translate(x, y);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setClip(new java.awt.geom.RoundRectangle2D.Float(0, 0, size, size, Math.max(4, size / 5f), Math.max(4, size / 5f)));
        g.setColor(new Color(26, 26, 26)); g.fillRect(0, 0, size, size);
        g.scale(size / 800.0, size / 800.0);
        for (int i = 0; i < paths.size(); i++) { g.setColor(colors.get(i)); g.fill(paths.get(i)); }
        g.dispose();
    }

    BufferedImage toImage() {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics(); paintIcon(null, g, 0, 0); g.dispose(); return image;
    }
}
