package app.multimin.ui;

import app.multimin.model.Source;
import app.multimin.model.View;
import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.function.IntConsumer;

public final class PreviewPanel extends JPanel {
    private final Palette palette;
    private final FrameTextureCache frameTextures = new FrameTextureCache();
    private final WindowSurfaceCompositor windowSurfaces = new WindowSurfaceCompositor();
    private View view;
    private IntConsumer selectionListener = ignored -> {};
    private Runnable changeListener = () -> {};
    private int selectedIndex = 1;
    private Rectangle canvas = new Rectangle();
    private boolean resizing;
    private boolean dragging;
    private Handle activeHandle = Handle.NONE;
    private boolean shiftFreeResize;
    private Point dragStart;
    private int startX, startY, startScale;
    private int snapX=-1,snapY=-1,guideX=-1,guideY=-1;
    private final Timer guideTimer=new Timer(500,e->{guideX=guideY=-1;repaint();});
    private int snapPosition(int position,int size,boolean horizontal,Source moving){
        int best=position,line=-1;double distance=7.0;
        java.util.List<Integer> targets=new java.util.ArrayList<>();
        int origin=horizontal?canvas.x:canvas.y,extent=horizontal?canvas.width:canvas.height;
        targets.add(origin);targets.add(origin+extent/2);targets.add(origin+extent);
        for(int i=0;i<view.sources.size();i++){Source other=view.sources.get(i);if(other==moving||!other.visible)continue;Rectangle r=boundsFor(other);int edge=horizontal?r.x:r.y,length=horizontal?r.width:r.height;targets.add(edge);targets.add(edge+length/2);targets.add(edge+length);}
        for(int target:targets)for(int offset:new int[]{0,size/2,size}){double delta=Math.abs(target-(position+offset));if(delta<distance){distance=delta;best=target-offset;line=target;}}
        int previous=horizontal?snapX:snapY;if(horizontal)snapX=line;else snapY=line;
        if(line!=previous){if(horizontal)guideX=line;else guideY=line;if(line>=0){guideTimer.setRepeats(false);guideTimer.restart();}}
        return best;
    }
    private boolean locked;
    private boolean nativePresentationAllowed = true;
    public void setNativePresentationAllowed(boolean allowed) {
        if(nativePresentationAllowed!=allowed){nativePresentationAllowed=allowed;windowSurfaces.reset();repaint();}
    }
    private volatile double measuredFps;
    private volatile double lastPaintMillis;
    private long paintSampleStarted = System.nanoTime();
    private int paintsInSample;

    private enum Handle { NONE, NORTH_WEST, NORTH, NORTH_EAST, EAST, SOUTH_EAST, SOUTH, SOUTH_WEST, WEST }

    public PreviewPanel(Palette palette, View view) {
        this.palette = palette;
        this.view = view;
        setOpaque(true);
        setBackground(new Color(14, 16, 20));
        MouseAdapter interaction = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) { if (event.isPopupTrigger()) showTransformMenu(event); else beginInteraction(event); }
            @Override public void mouseDragged(MouseEvent event) { updateInteraction(event); }
            @Override public void mouseReleased(MouseEvent event) { if (event.isPopupTrigger()) showTransformMenu(event); dragStart = null; resizing = false; dragging = false; activeHandle = Handle.NONE; }
        };
        addMouseListener(interaction);
        addMouseMotionListener(interaction);
    }

    public void setView(View view) { guideTimer.stop();snapX=snapY=guideX=guideY=-1; windowSurfaces.reset(); this.view = view; frameTextures.retain(view.sources); selectedIndex = Math.min(1, view.sources.size() - 1); repaint(); }
    public Rectangle sourceBoundsInPanel(int index) {
        if (index < 0 || index >= view.sources.size()) return new Rectangle();
        if (locked) canvas = new Rectangle(0, 0, getWidth(), getHeight());
        else {
            int margin = 34, width = Math.max(240, getWidth() - margin * 2);
            int height = Math.min(Math.max(150, getHeight() - margin * 2), width * 9 / 16);
            canvas = new Rectangle((getWidth() - width) / 2, (getHeight() - height) / 2, width, height);
        }
        return boundsFor(view.sources.get(index));
    }
    public Rectangle sourceBoundsOnScreen(int index) {
        if (index < 0 || index >= view.sources.size() || !isShowing()) return new Rectangle();
        Rectangle bounds = sourceBoundsInPanel(index);
        Point location = getLocationOnScreen(); bounds.translate(location.x, location.y);
        return toPhysicalScreenBounds(bounds, getGraphicsConfiguration());
    }

    public static Rectangle toPhysicalScreenBounds(Rectangle bounds, GraphicsConfiguration configuration) {
        bounds = new Rectangle(bounds);
        if (configuration != null) {
            double sx = configuration.getDefaultTransform().getScaleX();
            double sy = configuration.getDefaultTransform().getScaleY();
            if (sx != 1.0 || sy != 1.0) {
                bounds = new Rectangle((int) Math.round(bounds.x * sx), (int) Math.round(bounds.y * sy),
                    (int) Math.round(bounds.width * sx), (int) Math.round(bounds.height * sy));
            }
        }
        return bounds;
    }
    public void setSelectedIndex(int index) { selectedIndex = index; repaint(); }
    public double getMeasuredFps() { return measuredFps; }
    public double getLastPaintMillis() { return lastPaintMillis; }
    public int getAcceleratedSourceCount() { return frameTextures.countAcceleratedSources(); }
    @Override public void removeNotify() { guideTimer.stop();guideX=guideY=snapX=snapY=-1; windowSurfaces.close(); frameTextures.close(); super.removeNotify(); }
    public void setLocked(boolean locked) { if(!locked)windowSurfaces.reset(); this.locked = locked; setOpaque(!locked); dragStart = null; resizing = false; setCursor(Cursor.getDefaultCursor()); repaint(); }
    public void refreshNativePresentation() {
        if(!locked || !nativePresentationAllowed)return;
        boolean active=false;for(int i=0;i<view.sources.size();i++)active|=view.sources.get(i).nativeCompositionActive;
        if(!active)return;
        canvas=new Rectangle(0,0,getWidth(),getHeight());
        windowSurfaces.update(this,view,canvas,true,this::sourceBoundsInPanel);
    }
    public boolean isSourceRendered(int index) {
        Source source = view.sources.get(index);
        return source.visible && !(locked && index == view.sources.size()-1 && source.kind == Source.Kind.DISPLAY
            && source.displayDeviceIndex == 0 && source.opacity == 100 && source.scale == 100 && source.x == 0 && source.y == 0
            && (source.scaleX < 0 || source.scaleX == 100) && (source.scaleY < 0 || source.scaleY == 100));
    }
    public void onSelectionChanged(IntConsumer listener) { selectionListener = listener == null ? ignored -> {} : listener; }
    public void onSourceChanged(Runnable listener) { changeListener = listener == null ? () -> {} : listener; }

    private Rectangle boundsFor(Source source) {
        int sourceWidth=source.nativeSourceWidth>0?source.nativeSourceWidth:source.liveFrame==null?0:source.liveFrame.getWidth();
        int sourceHeight=source.nativeSourceHeight>0?source.nativeSourceHeight:source.liveFrame==null?0:source.liveFrame.getHeight();
        double aspect = sourceHeight==0 ? 16.0/9.0 : (double)sourceWidth/sourceHeight;
        int fitWidth = canvas.width, fitHeight = canvas.height;
        if ((source.kind == Source.Kind.WINDOW || source.kind == Source.Kind.GAME) && sourceWidth>0 && sourceHeight>0) {
            DisplayMode display = referenceDisplay();
            fitWidth = Math.max(1, (int) Math.round((double) sourceWidth * canvas.width / Math.max(1, display.getWidth())));
            fitHeight = Math.max(1, (int) Math.round((double) sourceHeight * canvas.height / Math.max(1, display.getHeight())));
        } else if ((double) fitWidth / Math.max(1, fitHeight) > aspect) fitWidth = (int) (fitHeight * aspect);
        else fitHeight = (int) (fitWidth / aspect);
        int width = Math.max(20, fitWidth * (source.scaleX < 0 ? source.scale : source.scaleX) / 100);
        int height = Math.max(20, fitHeight * (source.scaleY < 0 ? source.scale : source.scaleY) / 100);
        int x = canvas.x + (canvas.width - width) * source.x / 100;
        int y = canvas.y + (canvas.height - height) * source.y / 100;
        return new Rectangle(x, y, width, height);
    }

    private DisplayMode referenceDisplay() {
        GraphicsConfiguration configuration = getGraphicsConfiguration();
        return (configuration == null ? GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
            : configuration.getDevice()).getDisplayMode();
    }

    private void beginInteraction(MouseEvent event) {
        guideTimer.stop();guideX=guideY=snapX=snapY=-1;
        if (locked) return;
        if (SwingUtilities.isRightMouseButton(event)) { showTransformMenu(event); return; }
        if (selectedIndex >= 0 && selectedIndex < view.sources.size()) {
            Source selected = view.sources.get(selectedIndex);
            if (selected.visible) {
                Rectangle bounds = boundsFor(selected);
                Handle handle = handleAt(bounds, event.getPoint());
                if (handle != Handle.NONE) { beginResize(selected, bounds, handle, event); return; }
            }
        }
        for (int index = 0; index < view.sources.size(); index++) {
            Source source = view.sources.get(index);
            if (!source.visible) continue;
            Rectangle bounds = boundsFor(source);
            Handle handle = handleAt(bounds, event.getPoint());
            if (bounds.contains(event.getPoint()) || handle != Handle.NONE) {
                selectedIndex = index;
                selectionListener.accept(index);
                if (handle != Handle.NONE) beginResize(source, bounds, handle, event);
                else {
                    resizing = false; dragging = true;
                    dragStart = event.getPoint();
                    startX = source.x; startY = source.y;
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                }
                repaint();
                return;
            }
        }
        setCursor(Cursor.getDefaultCursor());
    }

    private void beginResize(Source source, Rectangle bounds, Handle handle, MouseEvent event) {
        resizing = true; dragging = false; activeHandle = handle; shiftFreeResize = event.isShiftDown();
        dragStart = event.getPoint(); startX = source.x; startY = source.y; startScale = source.scale;
        startScaleX = source.scaleX < 0 ? source.scale : source.scaleX;
        startScaleY = source.scaleY < 0 ? source.scale : source.scaleY;
        startWidth = Math.max(1, bounds.width); startHeight = Math.max(1, bounds.height);
        setCursor(Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR));
    }

    private int startScaleX, startScaleY, startWidth, startHeight;

    private Handle handleAt(Rectangle b, Point p) {
        int cx = b.x + b.width / 2, cy = b.y + b.height / 2;
        int[][] points = {{b.x, b.y}, {cx, b.y}, {b.x + b.width, b.y}, {b.x + b.width, cy},
            {b.x + b.width, b.y + b.height}, {cx, b.y + b.height}, {b.x, b.y + b.height}, {b.x, cy}};
        Handle[] handles = Handle.values();
        for (int i = 1; i < handles.length; i++) if (new Rectangle(points[i - 1][0] - 6, points[i - 1][1] - 6, 12, 12).contains(p)) return handles[i];
        return Handle.NONE;
    }

    private void showTransformMenu(MouseEvent event) {
        if (locked) return;
        int index = selectedIndex;
        boolean clickedSource = false;
        for (int candidate = 0; candidate < view.sources.size(); candidate++) {
            Source underPointer = view.sources.get(candidate);
            if (underPointer.visible && boundsFor(underPointer).contains(event.getPoint())) { index = candidate; clickedSource = true; break; }
        }
        if (!clickedSource && (index < 0 || index >= view.sources.size() || !boundsFor(view.sources.get(index)).contains(event.getPoint()))) return;
        if (index < 0 || index >= view.sources.size()) return;
        Source source = view.sources.get(index);
        selectedIndex = index; selectionListener.accept(index);
        JPopupMenu menu = new JPopupMenu();
        JCheckBoxMenuItem free = new JCheckBoxMenuItem("Free transform", source.freeTransform);
        free.addActionListener(action -> {
            source.freeTransform = free.isSelected();
            if (source.freeTransform) {
                source.scaleX = source.scaleX < 0 ? source.scale : source.scaleX;
                source.scaleY = source.scaleY < 0 ? source.scale : source.scaleY;
            } else { source.scaleX = -1; source.scaleY = -1; }
            repaint(); changeListener.run();
        });
        menu.add(free);
        menu.show(this, event.getX(), event.getY());
    }

    private void updateInteraction(MouseEvent event) {
        if (locked) return;
        if (dragStart == null || selectedIndex < 0 || selectedIndex >= view.sources.size()) return;
        Source source = view.sources.get(selectedIndex);
        int dx = event.getX() - dragStart.x, dy = event.getY() - dragStart.y;
        if (resizing) {
            boolean west = activeHandle == Handle.NORTH_WEST || activeHandle == Handle.WEST || activeHandle == Handle.SOUTH_WEST;
            boolean east = activeHandle == Handle.NORTH_EAST || activeHandle == Handle.EAST || activeHandle == Handle.SOUTH_EAST;
            boolean north = activeHandle == Handle.NORTH_WEST || activeHandle == Handle.NORTH || activeHandle == Handle.NORTH_EAST;
            boolean south = activeHandle == Handle.SOUTH_WEST || activeHandle == Handle.SOUTH || activeHandle == Handle.SOUTH_EAST;
            int nextX = startScaleX, nextY = startScaleY;
            if (west) nextX -= dx * 100 / Math.max(1, canvas.width);
            if (east) nextX += dx * 100 / Math.max(1, canvas.width);
            if (north) nextY -= dy * 100 / Math.max(1, canvas.height);
            if (south) nextY += dy * 100 / Math.max(1, canvas.height);
            boolean corner = (west || east) && (north || south);
            if (corner && !source.freeTransform && !shiftFreeResize) {
                int delta = Math.abs(nextX - startScaleX) > Math.abs(nextY - startScaleY) ? nextX - startScaleX : nextY - startScaleY;
                source.scale = Math.max(5, Math.min(250, startScale + delta));
                source.scaleX = source.scaleY = -1;
            } else {
                source.scaleX = Math.max(5, Math.min(250, nextX));
                source.scaleY = Math.max(5, Math.min(250, nextY));
                source.scale = Math.max(5, Math.min(250, (source.scaleX + source.scaleY) / 2));
            }
        } else if (dragging) {
            Rectangle bounds = boundsFor(source);
            int availableX=canvas.width-bounds.width,availableY=canvas.height-bounds.height;
            int px=canvas.x+availableX*startX/100+dx,py=canvas.y+availableY*startY/100+dy;
            if(!event.isAltDown()){px=snapPosition(px,bounds.width,true,source);py=snapPosition(py,bounds.height,false,source);}
            else {snapX=snapY=guideX=guideY=-1;}
            source.x=availableX==0?0:Math.clamp((int)Math.round((px-canvas.x)*100.0/availableX),0,100);
            source.y=availableY==0?0:Math.clamp((int)Math.round((py-canvas.y)*100.0/availableY),0,100);
        }
        changeListener.run();
        repaint();
    }

    @Override protected void paintComponent(Graphics graphics) {
        long paintStarted = System.nanoTime();
        frameTextures.retain(view.sources);
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int margin = locked ? 0 : 34, width = locked ? getWidth() : Math.max(240, getWidth() - margin * 2);
        int height = locked ? getHeight() : Math.min(Math.max(150, getHeight() - margin * 2), width * 9 / 16);
        int x = locked ? 0 : (getWidth() - width) / 2, y = locked ? 0 : (getHeight() - height) / 2;
        canvas = new Rectangle(x, y, width, height);
        windowSurfaces.update(this,view,canvas,locked && nativePresentationAllowed,this::sourceBoundsInPanel);
        if (!locked) { g.setColor(Color.BLACK); g.fillRect(x, y, width, height); }
        else if (!hasVisibleDisplaySource()) {
            g.setColor(Color.BLACK); g.fillRect(0, 0, getWidth(), getHeight());
        }

        Graphics2D layers = (Graphics2D) g.create();
        layers.clip(canvas);
        for (int index = view.sources.size() - 1; index >= 0; index--) {
            Source source = view.sources.get(index);
            if (!isSourceRendered(index)) continue;
            Rectangle bounds = boundsFor(source);
            paintSource(layers, source, bounds, index);
        }
        layers.dispose();

        if (!locked) {
            g.setColor(new Color(210, 216, 225));
            g.setStroke(new BasicStroke(1.5f));
            g.drawRect(x, y, width - 1, height - 1);
        }
        if(!locked&&(guideX>=0||guideY>=0)){g.setColor(palette.accent);g.setStroke(new BasicStroke(1f));if(guideX>=0)g.drawLine(guideX,canvas.y,guideX,canvas.y+canvas.height);if(guideY>=0)g.drawLine(canvas.x,guideY,canvas.x+canvas.width,guideY);}
        g.dispose();
        lastPaintMillis = (System.nanoTime() - paintStarted) / 1_000_000.0;
        long presented = System.nanoTime();
        for (int i=0; i<view.sources.size(); i++) {
            Source source=view.sources.get(i);
            if (isSourceRendered(i) && !source.nativeCompositionActive && source.producedNanos>0 && source.lastPresentedCapture!=source.capturedFrames) {
                source.lastPresentedCapture=source.capturedFrames; source.presentedFrames++;
                source.publicationToPaintMillis=Math.max(0, (presented-source.producedNanos)/1_000_000.0);
            }
        }
        paintsInSample++;
        long now = System.nanoTime();
        long elapsed = now - paintSampleStarted;
        if (elapsed >= 1_000_000_000L) {
            measuredFps = paintsInSample * 1_000_000_000.0 / elapsed;
            paintsInSample = 0;
            paintSampleStarted = now;
        }
    }

    private void paintSource(Graphics2D g, Source source, Rectangle bounds, int index) {
        if(source.nativeCompositionActive){frameTextures.release(source);return;}
        float alpha = Math.max(0.05f, source.opacity / 100f);
        g.setComposite(AlphaComposite.SrcOver.derive(alpha));
        Color fill;
        switch (source.kind) {
            case DISPLAY: fill = new Color(80, 91, 109); break;
            case WINDOW: fill = new Color(40, 46, 57); break;
            case IMAGE: fill = new Color(71, 83, 103); break;
            case VIDEO: fill = new Color(48, 43, 67); break;
            default: fill = new Color(47, 57, 51); break;
        }
        g.setColor(fill);
        g.fill(new RoundRectangle2D.Float(bounds.x, bounds.y, bounds.width, bounds.height, 10, 10));
        if ((source.kind == Source.Kind.DISPLAY || source.kind == Source.Kind.WINDOW || source.kind == Source.Kind.GAME || source.kind == Source.Kind.IMAGE) && source.liveFrame != null) {
            Graphics2D pixels = (Graphics2D) g.create();
            var transform = pixels.getTransform();
            var physical = transform.createTransformedShape(bounds).getBounds2D();
            int px = (int)Math.round(physical.getX()), py = (int)Math.round(physical.getY());
            int pw = Math.max(1, (int)Math.round(physical.getWidth())), ph = Math.max(1, (int)Math.round(physical.getHeight()));
            boolean nativePixels = pw == source.liveFrame.getWidth() && ph == source.liveFrame.getHeight();
            if (transform.getShearX() == 0 && transform.getShearY() == 0) {
                pixels.setTransform(new java.awt.geom.AffineTransform());
            } else { px = bounds.x; py = bounds.y; pw = bounds.width; ph = bounds.height; }
            pixels.setRenderingHint(RenderingHints.KEY_INTERPOLATION, nativePixels
                ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                : !locked ? RenderingHints.VALUE_INTERPOLATION_BICUBIC : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            Image scaled = !nativePixels && !locked ? frameTextures.scaledImage(source,pw,ph) : null;
            pixels.drawImage(scaled != null ? scaled : frameTextures.imageFor(source, getGraphicsConfiguration()), px, py, pw, ph, null);
            pixels.dispose();
        } else if (source.kind == Source.Kind.DISPLAY) {
            if (source.liveFrame != null) {
                g.drawImage(source.liveFrame, bounds.x, bounds.y, bounds.width, bounds.height, null);
            } else {
                int inset = Math.max(22, bounds.width / 14);
                g.setColor(new Color(209, 215, 220));
                g.fillRoundRect(bounds.x + inset, bounds.y + inset, bounds.width - inset * 2, bounds.height - inset * 2, 8, 8);
                g.setColor(new Color(188, 196, 202));
                g.fillRoundRect(bounds.x + inset, bounds.y + inset, bounds.width - inset * 2, Math.max(20, bounds.height / 11), 8, 8);
            }
        } else {
            new VectorIcon(VectorIcon.forSource(source.kind), 14, Color.WHITE).paintIcon(this, g, bounds.x + 16, bounds.y + 15);
            g.setColor(Color.WHITE); g.setFont(getFont().deriveFont(Font.BOLD, 12f));
            g.drawString(source.name, bounds.x + 38, bounds.y + 27);
            g.setFont(getFont().deriveFont(9f)); g.setColor(new Color(185, 192, 203));
            g.drawString(source.kind.label, bounds.x + 17, bounds.y + 43);
        }
        g.setComposite(AlphaComposite.SrcOver);
        if (!locked && index == selectedIndex) {
            g.setColor(palette.accent); g.setStroke(new BasicStroke(2f)); g.drawRect(bounds.x, bounds.y, bounds.width, bounds.height);
            int size = 8; int cx = bounds.x + bounds.width / 2, cy = bounds.y + bounds.height / 2;
            int[][] points = {{bounds.x, bounds.y}, {cx, bounds.y}, {bounds.x + bounds.width, bounds.y}, {bounds.x + bounds.width, cy},
                {bounds.x + bounds.width, bounds.y + bounds.height}, {cx, bounds.y + bounds.height}, {bounds.x, bounds.y + bounds.height}, {bounds.x, cy}};
            for (int[] point : points) { g.fillOval(point[0] - size / 2, point[1] - size / 2, size, size); }
        }
    }

    private boolean hasVisibleDisplaySource() {
        for (int index = 0; index < view.sources.size(); index++) {
            Source source = view.sources.get(index);
            if (source.visible && source.kind == Source.Kind.DISPLAY) return true;
        }
        return false;
    }
}
