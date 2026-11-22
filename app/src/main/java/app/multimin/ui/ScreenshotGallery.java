package app.multimin.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Local screenshot library; image decoding and filesystem work never run on the EDT. */
final class ScreenshotGallery extends JFrame {
    final Palette palette; final ScreenshotStore store;
    final DefaultListModel<ScreenshotStore.Photo> model=new DefaultListModel<>();
    final JList<ScreenshotStore.Photo> photos=new JList<>(model);
    final JTextArea detail=new JTextArea("Select one screenshot"); final JLabel status=new JLabel(" ");
    final ImagePreview preview=new ImagePreview();
    final JButton keep,delete,location,copy;
    final Map<Path,BufferedImage> thumbnails=new LinkedHashMap<>(96,.75f,true){@Override protected boolean removeEldestEntry(Map.Entry<Path,BufferedImage> e){return size()>96;}};
    final Set<Path> pending=new HashSet<>();
    final ExecutorService files=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"screenshot-files");t.setDaemon(true);return t;});
    final ThreadPoolExecutor images=new ThreadPoolExecutor(1,2,10,TimeUnit.SECONDS,new ArrayBlockingQueue<>(64),r->{Thread t=new Thread(r,"screenshot-images");t.setDaemon(true);return t;});
    final javax.swing.Timer timer; int generation; boolean closed;
    ScreenshotGallery(Palette palette){this(palette,new ScreenshotStore());}
    ScreenshotGallery(Palette palette,ScreenshotStore store){
        super("MultiMini · Gallery");this.palette=palette;this.store=store;
        setIconImages(java.util.List.of(new LogoIcon(16).toImage(),new LogoIcon(32).toImage(),new LogoIcon(64).toImage()));
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);setMinimumSize(new Dimension(680,440));setSize(1050,680);setLocationByPlatform(true);
        JPanel root=new JPanel(new BorderLayout(14,14));root.setBorder(new EmptyBorder(18,18,18,18));
        JPanel header=new JPanel(new BorderLayout(12,8));JLabel title=new JLabel("Gallery");title.setFont(Theme.title(24f));header.add(title);
        JLabel subtitle=new JLabel("Screenshots are stored on this device");subtitle.setForeground(palette.muted);subtitle.setFont(Theme.regular(12.5f));header.add(subtitle,BorderLayout.SOUTH);
        JButton folder=button("Open folder");folder.addActionListener(e->work(()->{Files.createDirectories(store.directory);Desktop.getDesktop().open(store.directory.toFile());}));header.add(folder,BorderLayout.EAST);root.add(header,BorderLayout.NORTH);
        photos.setLayoutOrientation(JList.HORIZONTAL_WRAP);photos.setVisibleRowCount(-1);photos.setFixedCellWidth(206);photos.setFixedCellHeight(170);photos.setCellRenderer(new Thumbnail());
        photos.addListSelectionListener(e->{if(!e.getValueIsAdjusting())select();});
        JScrollPane scroll=new JScrollPane(photos);scroll.setBorder(BorderFactory.createEmptyBorder());scroll.getViewport().setOpaque(false);ModernUi.scroll(scroll.getVerticalScrollBar(),palette);ModernUi.scroll(scroll.getHorizontalScrollBar(),palette);scroll.setMinimumSize(new Dimension(220,0));
        JPanel right=new JPanel(new BorderLayout(8,12));right.setBorder(new EmptyBorder(0,12,0,0));right.add(preview);right.setMinimumSize(new Dimension(250,0));
        detail.setFont(Theme.regular(12.5f));status.setFont(Theme.regular(11.5f));detail.setLineWrap(true);detail.setWrapStyleWord(true);detail.setEditable(false);detail.setFocusable(false);detail.setOpaque(false);detail.setRows(2);JPanel bottom=new JPanel(new BorderLayout(8,12));bottom.add(detail,BorderLayout.NORTH);JPanel actions=new JPanel(new GridLayout(0,1,0,8));
        copy=button("Copy");copy.addActionListener(e->{var photo=photos.getSelectedValue();if(photo!=null){final int token=generation;images.execute(()->{BufferedImage image=read(photo.path(),Integer.MAX_VALUE);SwingUtilities.invokeLater(()->{if(closed||token!=generation)return;try{if(image==null)throw new IllegalStateException("Image unavailable");ScreenshotSelection.copy(image);status.setText("Screenshot copied to clipboard");}catch(Exception error){status.setText("Could not copy: "+error.getMessage());}});});}});
        keep=button("Keep");keep.setModel(new JToggleButton.ToggleButtonModel());delete=button("Delete");location=button("Open file location");
        keep.setToolTipText("Keep indefinitely. Turning Keep off starts a new seven-day timer.");
        keep.addActionListener(e->{var p=photos.getSelectedValue();if(p!=null)work(()->store.keep(p,!p.kept()));});
        delete.addActionListener(e->{var p=photos.getSelectedValue();if(p!=null&&confirmDelete())work(()->store.delete(p));});
        location.addActionListener(e->{var p=photos.getSelectedValue();if(p!=null)work(()->{if(System.getProperty("os.name").startsWith("Windows"))new ProcessBuilder("explorer.exe","/select,"+p.path().toString()).start();else Desktop.getDesktop().open(store.directory.toFile());});});
        actions.add(copy);actions.add(keep);actions.add(delete);actions.add(location);bottom.add(actions);right.add(bottom,BorderLayout.SOUTH);
        JSplitPane split=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,scroll,right);ModernUi.split(split,palette);split.setResizeWeight(.58);split.setDividerLocation(560);root.add(split);root.add(status,BorderLayout.SOUTH);setContentPane(root);
        theme(root);select();reload(null);timer=new javax.swing.Timer(30000,e->reload(null));timer.start();
    }
    private boolean confirmDelete(){JDialog dialog=new JDialog(this,"Delete screenshot",true);JPanel body=new JPanel(new BorderLayout(16,18));body.setBackground(palette.background);body.setBorder(new EmptyBorder(20,22,18,22));JLabel message=new JLabel("Delete this screenshot permanently?");message.setFont(new Font(Font.DIALOG,Font.PLAIN,14));message.setForeground(palette.text);body.add(message);JPanel row=new JPanel(new FlowLayout(FlowLayout.RIGHT,8,0));row.setOpaque(false);boolean[] result={false};JButton cancel=button("Cancel"),remove=button("Delete");remove.setBackground(new Color(190,65,72));remove.addActionListener(e->{result[0]=true;dialog.dispose();});cancel.addActionListener(e->dialog.dispose());row.add(cancel);row.add(remove);body.add(row,BorderLayout.SOUTH);dialog.setContentPane(body);dialog.setDefaultCloseOperation(DISPOSE_ON_CLOSE);dialog.pack();dialog.setMinimumSize(new Dimension(360,130));dialog.setLocationRelativeTo(this);dialog.setVisible(true);return result[0];}
    private JButton button(String text){JButton button=new ModernButton(text,null,palette,false);button.setFont(Theme.regular(13f));button.setBorder(new EmptyBorder(8,14,8,14));return button;}
    private void theme(Component c){c.setForeground(palette.text);if(c instanceof JPanel||c instanceof JList)c.setBackground(palette.panel);if(c instanceof Container parent)for(Component child:parent.getComponents())theme(child);if(c instanceof JLabel label&&label.getText()!=null&&label.getText().startsWith("Screenshots are stored"))label.setForeground(palette.muted);}
    void refreshTheme(){theme(getContentPane());repaint();}
    interface Work{void run()throws Exception;}
    private void work(Work action){files.submit(()->{try{action.run();SwingUtilities.invokeLater(()->reload(null));}catch(Exception ex){SwingUtilities.invokeLater(()->status.setText("Could not complete action: "+ex.getMessage()));}});}
    void reload(Path requested){if(closed)return;java.util.List<Path> selection=requested==null?photos.getSelectedValuesList().stream().map(ScreenshotStore.Photo::path).toList():java.util.List.of(requested);
        files.submit(()->{try{var entries=store.list();SwingUtilities.invokeLater(()->{if(closed)return;model.clear();entries.forEach(model::addElement);for(int i=0;i<model.size();i++)if(selection.contains(model.get(i).path()))photos.addSelectionInterval(i,i);status.setText(entries.size()+" screenshots · Temporary photos expire after seven days. Keep saves them indefinitely.");select();});}catch(Exception ex){SwingUtilities.invokeLater(()->status.setText("Could not load screenshots: "+ex.getMessage()));}});
    }
    private void select(){int token=++generation;boolean single=photos.getSelectedIndices().length==1;copy.setEnabled(single);keep.setEnabled(single);delete.setEnabled(single);location.setEnabled(single);preview.image=null;preview.repaint();
        if(!single){preview.getAccessibleContext().setAccessibleName("No screenshot selected");detail.setText("Select one screenshot");keep.setSelected(false);return;}
        var photo=photos.getSelectedValue();preview.getAccessibleContext().setAccessibleName("Screenshot preview: "+photo.path().getFileName());preview.getAccessibleContext().setAccessibleDescription(photo.time(System.currentTimeMillis()));keep.setSelected(photo.kept());detail.setText(photo.time(System.currentTimeMillis()));
        try{images.execute(()->{BufferedImage image=read(photo.path(),1600);SwingUtilities.invokeLater(()->{if(!closed&&token==generation){preview.image=image;preview.repaint();if(image==null)detail.setText("Image could not be opened");}});});}catch(RejectedExecutionException ignored){}
    }
    private static BufferedImage read(Path path,int limit){try(ImageInputStream in=ImageIO.createImageInputStream(path.toFile())){if(in==null)return null;var readers=ImageIO.getImageReaders(in);if(!readers.hasNext())return null;ImageReader reader=readers.next();try{reader.setInput(in);int sample=Math.max(1,Math.max(reader.getWidth(0),reader.getHeight(0))/limit);var param=reader.getDefaultReadParam();param.setSourceSubsampling(sample,sample,0,0);return reader.read(0,param);}finally{reader.dispose();}}catch(Exception e){return null;}}
    private final class Thumbnail extends JPanel implements ListCellRenderer<ScreenshotStore.Photo>{ScreenshotStore.Photo photo;boolean selected;
        public Component getListCellRendererComponent(JList<? extends ScreenshotStore.Photo> list,ScreenshotStore.Photo p,int index,boolean selected,boolean focus){photo=p;this.selected=selected;getAccessibleContext().setAccessibleName("Screenshot "+p.path().getFileName()+", "+p.time(System.currentTimeMillis()));if(!thumbnails.containsKey(p.path())&&pending.add(p.path())){try{images.execute(()->{BufferedImage image=read(p.path(),360);SwingUtilities.invokeLater(()->{pending.remove(p.path());if(!closed){thumbnails.put(p.path(),image);photos.repaint();}});});}catch(RejectedExecutionException ex){pending.remove(p.path());}}return this;}
        @Override protected void paintComponent(Graphics graphics){Graphics2D g=Theme.quality(graphics);g.setColor(palette.panel);g.fillRect(0,0,getWidth(),getHeight());
            float x=6,y=6,w=getWidth()-12,h=getHeight()-12;
            if(selected){g.setColor(Theme.withAlpha(palette.accent,55));g.fill(Theme.round(x-3,y-3,w+6,h+6,Theme.RADIUS_LARGE+3));}
            g.setColor(selected?palette.accentTint(palette.card(),0.12f):palette.card());g.fill(Theme.round(x,y,w,h,Theme.RADIUS_LARGE));
            g.setColor(selected?palette.accent:palette.hairline());g.setStroke(new BasicStroke(selected?1.6f:1f));g.draw(Theme.round(x+0.5f,y+0.5f,w-1,h-1,Theme.RADIUS_LARGE));
            Shape well=Theme.round(x+7,y+7,w-14,h-46,9);g.setColor(palette.canvas());g.fill(well);
            BufferedImage image=thumbnails.get(photo.path());
            if(image!=null){Graphics2D c=(Graphics2D)g.create();c.clip(well);Rectangle r=well.getBounds();double scale=Math.max((double)r.width/image.getWidth(),(double)r.height/image.getHeight());int dw=(int)Math.ceil(image.getWidth()*scale),dh=(int)Math.ceil(image.getHeight()*scale);c.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);c.drawImage(image,r.x+(r.width-dw)/2,r.y+(r.height-dh)/2,dw,dh,null);c.dispose();}
            else{g.setColor(palette.muted);g.setFont(Theme.regular(11f));g.drawString("Loading…",(int)x+16,(int)(y+(h-46)/2+10));}
            int baseline=(int)(y+h-15);g.setFont(Theme.regular(11.5f));g.setColor(palette.muted);if(!photo.kept())g.drawString("Expires in "+photo.shortTime(System.currentTimeMillis()).toLowerCase(java.util.Locale.ROOT),(int)x+10,baseline);else g.drawString("Saved permanently",(int)x+10,baseline);
            if(photo.kept()){g.setFont(Theme.semibold(10.5f));FontMetrics m=g.getFontMetrics();int cw=m.stringWidth("Kept")+16,cx=(int)(x+w)-cw-8,cy=baseline-14;g.setColor(palette.accentTint(palette.card(),0.30f));g.fill(Theme.round(cx,cy,cw,19,10));g.setColor(palette.text);g.drawString("Kept",cx+8,cy+14);}
            g.dispose();}
    }
    private static void draw(Graphics2D g,BufferedImage image,Rectangle r){double ratio=Math.min((double)r.width/image.getWidth(),(double)r.height/image.getHeight());int w=(int)(image.getWidth()*ratio),h=(int)(image.getHeight()*ratio);g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);g.drawImage(image,r.x+(r.width-w)/2,r.y+(r.height-h)/2,w,h,null);}
    private final class ImagePreview extends JPanel{BufferedImage image;@Override protected void paintComponent(Graphics graphics){Graphics2D g=Theme.quality(graphics);g.setColor(palette.panel);g.fillRect(0,0,getWidth(),getHeight());g.setColor(palette.canvas());g.fill(Theme.round(0,0,getWidth(),getHeight(),Theme.RADIUS_LARGE));g.setColor(palette.hairline());g.draw(Theme.round(0.5f,0.5f,getWidth()-1,getHeight()-1,Theme.RADIUS_LARGE));if(image!=null)draw(g,image,new Rectangle(12,12,Math.max(1,getWidth()-24),Math.max(1,getHeight()-24)));else{g.setFont(Theme.regular(13f));g.setColor(palette.muted);String t="Select a screenshot to preview it";FontMetrics m=g.getFontMetrics();g.drawString(t,(getWidth()-m.stringWidth(t))/2,getHeight()/2);}g.dispose();}}
    @Override public void dispose(){closed=true;generation++;timer.stop();files.shutdownNow();images.shutdownNow();thumbnails.clear();super.dispose();}
}
