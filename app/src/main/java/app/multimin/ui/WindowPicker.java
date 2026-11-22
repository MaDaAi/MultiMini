package app.multimin.ui;

import app.multimin.backend.PythonBridge;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

/**
 * Visual window chooser: a searchable grid of cards with live thumbnails.
 * Selection only reports the chosen WindowTarget; callers keep their own model
 * and capture logic, so choosing a window behaves exactly like the old list.
 */
final class WindowPicker extends JPanel {
    private static final int CARD_W = 224, CARD_H = 178, GAP = 14;
    private final Palette palette;
    private final JTextField search = new JTextField();
    private final JPanel grid;
    private final JScrollPane scroller;
    private final Map<Long, BufferedImage> thumbnails = new HashMap<>();
    private final Set<Long> unavailable = new HashSet<>();
    private List<PythonBridge.WindowTarget> targets = List.of();
    private PythonBridge.WindowTarget selected;
    private Consumer<PythonBridge.WindowTarget> onSelect = ignored -> {};
    private Runnable onConfirm = () -> {};
    private String emptyText = "Scanning open windows…";

    WindowPicker(Palette palette) {
        super(new BorderLayout(0, 12));
        this.palette = palette;
        setOpaque(false);
        search.putClientProperty("JTextField.placeholderText", "Search windows");
        search.getAccessibleContext().setAccessibleName("Search windows");
        search.setFont(Theme.regular(13f)); search.setForeground(palette.text); search.setCaretColor(palette.text);
        search.setBackground(palette.controlSurface()); search.setOpaque(false);
        search.setBorder(BorderFactory.createCompoundBorder(new ControlBorder(palette), new EmptyBorder(7, 30, 7, 10)));
        JPanel searchWrap = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = Theme.quality(graphics);
                g.setColor(palette.controlSurface()); g.fill(Theme.round(0, 0, getWidth(), getHeight(), 8));
                g.setColor(palette.muted); g.setStroke(new BasicStroke(1.6f));
                int cy = getHeight() / 2; g.drawOval(12, cy - 6, 10, 10); g.drawLine(21, cy + 3, 25, cy + 7);
                if (search.getText().isEmpty() && !search.hasFocus()) { g.setFont(Theme.regular(13f)); g.drawString("Search windows", 34, cy + 5); }
                g.dispose();
            }
        };
        searchWrap.setOpaque(false); searchWrap.add(search);
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { rebuild(); } public void removeUpdate(DocumentEvent e) { rebuild(); } public void changedUpdate(DocumentEvent e) { rebuild(); }
        });
        search.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusGained(java.awt.event.FocusEvent e) { searchWrap.repaint(); }
            @Override public void focusLost(java.awt.event.FocusEvent e) { searchWrap.repaint(); }
        });
        add(searchWrap, BorderLayout.NORTH);
        grid = new JPanel(null) {
            @Override public Dimension getPreferredSize() {
                int width = Math.max(CARD_W, scroller == null ? 700 : scroller.getViewport().getWidth());
                int columns = Math.max(1, (width + GAP) / (CARD_W + GAP));
                int rows = (getComponentCount() + columns - 1) / columns;
                return new Dimension(width, Math.max(160, rows * (CARD_H + GAP)));
            }
            @Override public void doLayout() {
                int width = getWidth(), columns = Math.max(1, (width + GAP) / (CARD_W + GAP));
                int cardW = (width - GAP * (columns - 1)) / columns;
                for (int i = 0; i < getComponentCount(); i++)
                    getComponent(i).setBounds((i % columns) * (cardW + GAP), (i / columns) * (CARD_H + GAP), cardW, CARD_H);
            }
            @Override protected void paintComponent(Graphics graphics) {
                if (getComponentCount() > 0) return;
                Graphics2D g = Theme.quality(graphics); g.setFont(Theme.regular(13f)); g.setColor(palette.muted);
                FontMetrics m = g.getFontMetrics(); g.drawString(emptyText, (getWidth() - m.stringWidth(emptyText)) / 2, 70); g.dispose();
            }
        };
        grid.setOpaque(false);
        scroller = new JScrollPane(grid, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroller.setBorder(BorderFactory.createEmptyBorder()); scroller.setOpaque(false); scroller.getViewport().setOpaque(false);
        scroller.getVerticalScrollBar().setUnitIncrement(24); ModernUi.scroll(scroller.getVerticalScrollBar(), palette);
        scroller.getViewport().addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent e) { grid.revalidate(); grid.repaint(); }
        });
        add(scroller, BorderLayout.CENTER);
    }

    void onSelect(Consumer<PythonBridge.WindowTarget> listener) { onSelect = listener == null ? ignored -> {} : listener; }
    void onConfirm(Runnable listener) { onConfirm = listener == null ? () -> {} : listener; }
    PythonBridge.WindowTarget selected() { return selected; }

    void setTargets(List<PythonBridge.WindowTarget> found) {
        targets = List.copyOf(found);
        emptyText = found.isEmpty() ? "No windows found. Open the app you want to add, then refresh." : "No windows match your search";
        thumbnails.keySet().retainAll(found.stream().map(t -> t.hwnd).toList());
        rebuild();
    }

    void setSelected(PythonBridge.WindowTarget target) {
        selected = target; grid.repaint();
        if (target != null) for (Component card : grid.getComponents())
            if (((Card) card).target == target) SwingUtilities.invokeLater(() -> grid.scrollRectToVisible(card.getBounds()));
    }

    /** Thread-safe: may be called from the backend reader thread. */
    void setThumbnail(long hwnd, BufferedImage image) {
        SwingUtilities.invokeLater(() -> {
            if (image == null) unavailable.add(hwnd); else { thumbnails.put(hwnd, image); unavailable.remove(hwnd); }
            grid.repaint();
        });
    }

    private void rebuild() {
        String query = search.getText().trim().toLowerCase(Locale.ROOT);
        grid.removeAll();
        for (PythonBridge.WindowTarget target : targets) {
            if (!query.isEmpty() && !(target.title + " " + appName(target)).toLowerCase(Locale.ROOT).contains(query)) continue;
            grid.add(new Card(target));
        }
        grid.revalidate(); grid.repaint();
    }

    static String appName(PythonBridge.WindowTarget target) {
        String process = target.process == null ? "" : target.process;
        if (process.startsWith("kwin:")) process = process.substring(5);
        int slash = Math.max(process.lastIndexOf('/'), process.lastIndexOf('\\'));
        if (slash >= 0) process = process.substring(slash + 1);
        if (process.toLowerCase(Locale.ROOT).endsWith(".exe")) process = process.substring(0, process.length() - 4);
        if (process.equalsIgnoreCase("ApplicationFrameHost")) return "Windows app";
        if (process.equalsIgnoreCase("javaw") || process.equalsIgnoreCase("java")) return target.title != null && target.title.toLowerCase(Locale.ROOT).startsWith("minecraft") ? "Minecraft" : "Java";
        if (process.isEmpty()) return "Application";
        return Character.toUpperCase(process.charAt(0)) + process.substring(1);
    }

    private static Color badgeColor(String name) {
        Color[] colors = {new Color(96, 148, 255), new Color(72, 199, 132), new Color(245, 158, 72), new Color(178, 120, 240),
            new Color(236, 96, 120), new Color(64, 186, 214), new Color(222, 186, 72)};
        return colors[Math.floorMod(name.toLowerCase(Locale.ROOT).hashCode(), colors.length)];
    }

    private final class Card extends JPanel {
        final PythonBridge.WindowTarget target;
        boolean hover;
        Card(PythonBridge.WindowTarget target) {
            super(null);
            this.target = target;
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText(target.title);
            getAccessibleContext().setAccessibleName(target.title + ", " + appName(target));
            setFocusable(true);
            MouseAdapter mouse = new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                @Override public void mousePressed(MouseEvent e) { choose(); requestFocusInWindow(); if (e.getClickCount() == 2) onConfirm.run(); }
            };
            addMouseListener(mouse);
            getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "confirm");
            getActionMap().put("confirm", new AbstractAction() { public void actionPerformed(java.awt.event.ActionEvent e) { choose(); onConfirm.run(); } });
            getInputMap().put(KeyStroke.getKeyStroke("SPACE"), "choose");
            getActionMap().put("choose", new AbstractAction() { public void actionPerformed(java.awt.event.ActionEvent e) { choose(); } });
            addFocusListener(new java.awt.event.FocusAdapter() { @Override public void focusGained(java.awt.event.FocusEvent e) { repaint(); } @Override public void focusLost(java.awt.event.FocusEvent e) { repaint(); } });
        }
        private void choose() { selected = target; onSelect.accept(target); grid.repaint(); }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = Theme.quality(graphics);
            boolean chosen = selected == target;
            float w = getWidth(), h = getHeight(), lift = hover && !chosen ? 1f : 0f;
            if (chosen) { g.setColor(Theme.withAlpha(palette.accent, 60)); g.fill(Theme.round(0, 0, w, h, Theme.RADIUS_LARGE + 3)); }
            else if (hover) Theme.shadow(g, 3, 4, w - 6, h - 6, Theme.RADIUS_LARGE, 6, Color.BLACK, palette.isLight() ? 40 : 110);
            float x = 3, y = 3 - lift, cw = w - 6, ch = h - 6;
            g.setColor(chosen ? palette.accentTint(palette.card(), 0.10f) : hover ? palette.hover(palette.card()) : palette.card());
            g.fill(Theme.round(x, y, cw, ch, Theme.RADIUS_LARGE));
            g.setColor(chosen ? palette.accent : hover ? Palette.blend(palette.hairline(), palette.text, 0.18f) : palette.hairline());
            g.setStroke(new BasicStroke(chosen ? 2f : 1f));
            g.draw(Theme.round(x + 0.5f, y + 0.5f, cw - 1, ch - 1, Theme.RADIUS_LARGE));
            if (isFocusOwner() && !chosen) { g.setColor(palette.controlAccent()); g.setStroke(new BasicStroke(2f)); g.draw(Theme.round(x + 1, y + 1, cw - 2, ch - 2, Theme.RADIUS_LARGE)); }

            // Preview well (letterboxed, like a screen-share picker).
            Rectangle well = new Rectangle(Math.round(x + 8), Math.round(y + 8), Math.round(cw - 16), Math.round(ch - 62));
            Shape wellShape = Theme.round(well.x, well.y, well.width, well.height, 9);
            g.setColor(palette.canvas()); g.fill(wellShape);
            BufferedImage image = thumbnails.get(target.hwnd);
            if (image != null) {
                Graphics2D c = (Graphics2D) g.create(); c.clip(wellShape);
                c.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                double scale = Math.min((double) well.width / image.getWidth(), (double) well.height / image.getHeight());
                int dw = (int) Math.round(image.getWidth() * scale), dh = (int) Math.round(image.getHeight() * scale);
                c.drawImage(image, well.x + (well.width - dw) / 2, well.y + (well.height - dh) / 2, dw, dh, null);
                c.dispose();
            } else {
                Color tint = badgeColor(appName(target));
                g.setPaint(new GradientPaint(well.x, well.y, Palette.blend(palette.canvas(), tint, 0.16f), well.x, well.y + well.height, palette.canvas()));
                g.fill(wellShape);
                g.setFont(Theme.regular(11.5f)); g.setColor(palette.muted);
                String note = unavailable.contains(target.hwnd) ? (target.hwnd == 0 ? "Chosen in the desktop picker" : "Minimized or no preview") : "Loading preview…";
                FontMetrics m = g.getFontMetrics(); g.drawString(note, well.x + (well.width - m.stringWidth(note)) / 2, well.y + well.height / 2 + 4);
            }
            if (chosen) {
                int cx = well.x + well.width - 26, cy = well.y + 8;
                g.setColor(palette.accent); g.fillOval(cx, cy, 20, 20);
                g.setColor(palette.selectionText()); g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawLine(cx + 5, cy + 10, cx + 9, cy + 14); g.drawLine(cx + 9, cy + 14, cx + 15, cy + 6);
            }

            // App badge, title and app name.
            String app = appName(target);
            int by = Math.round(y + ch - 44), bx = Math.round(x + 10);
            Color badge = badgeColor(app);
            g.setColor(Palette.blend(palette.card(), badge, 0.85f)); g.fill(Theme.round(bx, by + 4, 28, 28, 8));
            g.setFont(Theme.semibold(13f)); g.setColor(Color.WHITE);
            String letter = app.substring(0, 1).toUpperCase(Locale.ROOT); FontMetrics lm = g.getFontMetrics();
            g.drawString(letter, bx + (28 - lm.stringWidth(letter)) / 2, by + 4 + (28 - lm.getHeight()) / 2 + lm.getAscent());
            int tx = bx + 38, tw = Math.round(x + cw - 10) - tx;
            g.setFont(Theme.semibold(12.5f)); g.setColor(palette.text);
            g.drawString(ellipsize(g, target.title + (target.windowNumber > 0 ? " (" + target.windowNumber + ")" : ""), tw), tx, by + 16);
            g.setFont(Theme.regular(11.5f)); g.setColor(palette.muted);
            g.drawString(ellipsize(g, app, tw), tx, by + 33);
            g.dispose();
        }
    }

    private static String ellipsize(Graphics2D g, String text, int width) {
        FontMetrics m = g.getFontMetrics();
        if (m.stringWidth(text) <= width) return text;
        for (int end = text.length(); end > 0; end--) if (m.stringWidth(text.substring(0, end) + "…") <= width) return text.substring(0, end) + "…";
        return "…";
    }

    /** Screenshot harness entry: sample windows with generated previews. */
    static void demo(MainWindow owner) {
        Palette palette = owner.uiPalette();
        JDialog dialog = new JDialog(owner, "Select a window", true);
        WindowPicker picker = new WindowPicker(palette);
        String[][] rows = {{"chrome.exe", "Releases · MultiMini — Google Chrome"}, {"Discord.exe", "#general | Friends - Discord"},
            {"javaw.exe", "Minecraft 1.21.1"}, {"Code.exe", "MainWindow.java — MultiMini"}, {"Spotify.exe", "Spotify Premium"},
            {"ApplicationFrameHost.exe", "Films & TV"}, {"explorer.exe", "Downloads"}, {"obs64.exe", "OBS 31.0.2 - Profile: Untitled"}};
        List<PythonBridge.WindowTarget> sample = new ArrayList<>();
        for (int i = 0; i < rows.length; i++) sample.add(new PythonBridge.WindowTarget(rows[i][0], rows[i][1], 100 + i, 0, 0, 1600, 900));
        picker.setTargets(sample);
        picker.setSelected(sample.get(2));
        Color[][] tones = {{new Color(40, 70, 140), new Color(20, 30, 60)}, {new Color(70, 70, 160), new Color(30, 30, 70)}, {new Color(60, 140, 80), new Color(30, 60, 30)},
            {new Color(30, 40, 60), new Color(20, 25, 35)}, {new Color(30, 150, 90), new Color(15, 40, 25)}, null, {new Color(200, 200, 210), new Color(160, 165, 175)}, {new Color(50, 50, 60), new Color(25, 25, 30)}};
        for (int i = 0; i < rows.length; i++) {
            if (tones[i] == null) { picker.setThumbnail(100 + i, null); continue; }
            BufferedImage image = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics(); g.setPaint(new GradientPaint(0, 0, tones[i][0], 320, 180, tones[i][1])); g.fillRect(0, 0, 320, 180);
            g.setColor(new Color(255, 255, 255, 50)); for (int b = 0; b < 4; b++) g.fillRoundRect(20 + b * 74, 60, 56, 70, 10, 10);
            g.setColor(new Color(0, 0, 0, 80)); g.fillRect(0, 0, 320, 16); g.dispose();
            picker.setThumbnail(100 + i, image);
        }
        JPanel body = new GlassPanel(palette); body.setLayout(new BorderLayout(0, 16)); body.setBorder(new EmptyBorder(22, 22, 18, 22));
        JLabel title = new JLabel("Choose a window"); title.setFont(Theme.title(17f)); title.setForeground(palette.text);
        body.add(title, BorderLayout.NORTH); body.add(picker);
        dialog.setContentPane(body); dialog.setSize(880, 640); dialog.setLocationRelativeTo(owner); dialog.setVisible(true);
    }
}
