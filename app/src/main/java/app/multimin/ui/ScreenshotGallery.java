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
    final JButton keep,delete,location;
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
        JPanel header=new JPanel(new BorderLayout(12,8));JLabel title=new JLabel("Gallery");title.setFont(new Font(Font.DIALOG,Font.BOLD,23));header.add(title);
        JLabel subtitle=new JLabel("Screenshots are stored on this device");subtitle.setForeground(palette.muted);subtitle.setFont(new Font(Font.DIALOG,Font.PLAIN,12));header.add(subtitle,BorderLayout.SOUTH);
        JButton folder=button("Open folder");folder.addActionListener(e->work(()->{Files.createDirectories(store.directory);Desktop.getDesktop().open(store.directory.toFile());}));header.add(folder,BorderLayout.EAST);root.add(header,BorderLayout.NORTH);
        photos.setLayoutOrientation(JList.HORIZONTAL_WRAP);photos.setVisibleRowCount(-1);photos.setFixedCellWidth(190);photos.setFixedCellHeight(152);photos.setCellRenderer(new Thumbnail());
        photos.addListSelectionListener(e->{if(!e.getValueIsAdjusting())select();});
        JScrollPane scroll=new JScrollPane(photos);ModernUi.scroll(scroll.getVerticalScrollBar(),palette);ModernUi.scroll(scroll.getHorizontalScrollBar(),palette);scroll.setMinimumSize(new Dimension(220,0));
        JPanel right=new JPanel(new BorderLayout(8,12));right.setBorder(new EmptyBorder(0,12,0,0));right.add(preview);right.setMinimumSize(new Dimension(250,0));
        detail.setFont(new Font(Font.DIALOG,Font.PLAIN,12));status.setFont(new Font(Font.DIALOG,Font.PLAIN,11));detail.setLineWrap(true);detail.setWrapStyleWord(true);detail.setEditable(false);detail.setFocusable(false);detail.setOpaque(false);detail.setRows(2);JPanel bottom=new JPanel(new BorderLayout(8,12));bottom.add(detail,BorderLayout.NORTH);JPanel actions=new JPanel(new GridLayout(0,1,0,8));
        keep=button("Keep");keep.setModel(new JToggleButton.ToggleButtonModel());delete=button("Delete");location=button("Open file location");
        keep.setToolTipText("Keep indefinitely. Turning Keep off starts a new seven-day timer.");
        keep.addActionListener(e->{var p=photos.getSelectedValue();if(p!=null)work(()->store.keep(p,!p.kept()));});
        delete.addActionListener(e->{var p=photos.getSelectedValue();if(p!=null&&confirmDelete())work(()->store.delete(p));});
        location.addActionListener(e->{var p=photos.getSelectedValue();if(p!=null)work(()->{if(System.getProperty("os.name").startsWith("Windows"))new ProcessBuilder("explorer.exe","/select,"+p.path().toString()).start();else Desktop.getDesktop().open(store.directory.toFile());});});
        actions.add(keep);actions.add(delete);actions.add(location);bottom.add(actions);right.add(bottom,BorderLayout.SOUTH);
        JSplitPane split=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,scroll,right);ModernUi.split(split,palette);split.setResizeWeight(.58);split.setDividerLocation(560);root.add(split);root.add(status,BorderLayout.SOUTH);setContentPane(root);
        theme(root);select();reload(null);timer=new javax.swing.Timer(30000,e->reload(null));timer.start();
    }
    private boolean confirmDelete(){JDialog dialog=new JDialog(this,"Delete screenshot",true);JPanel body=new JPanel(new BorderLayout(16,18));body.setBackground(palette.background);body.setBorder(new EmptyBorder(20,22,18,22));JLabel message=new JLabel("Delete this screenshot permanently?");message.setFont(new Font(Font.DIALOG,Font.PLAIN,14));message.setForeground(palette.text);body.add(message);JPanel row=new JPanel(new FlowLayout(FlowLayout.RIGHT,8,0));row.setOpaque(false);boolean[] result={false};JButton cancel=button("Cancel"),remove=button("Delete");remove.setBackground(new Color(190,65,72));remove.addActionListener(e->{result[0]=true;dialog.dispose();});cancel.addActionListener(e->dialog.dispose());row.add(cancel);row.add(remove);body.add(row,BorderLayout.SOUTH);dialog.setContentPane(body);dialog.setDefaultCloseOperation(DISPOSE_ON_CLOSE);dialog.pack();dialog.setMinimumSize(new Dimension(360,130));dialog.setLocationRelativeTo(this);dialog.setVisible(true);return result[0];}
    private JButton button(String text){JButton button=new ModernButton(text,null,palette,false);button.setFont(new Font(Font.DIALOG,Font.BOLD,13));return button;}
    private void theme(Component c){c.setForeground(palette.text);if(c instanceof JPanel||c instanceof JList)c.setBackground(palette.background);if(c instanceof Container parent)for(Component child:parent.getComponents())theme(child);if(c instanceof JLabel label&&label.getText()!=null&&label.getText().startsWith("Screenshots are stored"))label.setForeground(palette.muted);}
    void refreshTheme(){theme(getContentPane());repaint();}
    interface Work{void run()throws Exception;}
    private void work(Work action){files.submit(()->{try{action.run();SwingUtilities.invokeLater(()->reload(null));}catch(Exception ex){SwingUtilities.invokeLater(()->status.setText("Could not complete action: "+ex.getMessage()));}});}
    void reload(Path requested){if(closed)return;java.util.List<Path> selection=requested==null?photos.getSelectedValuesList().stream().map(ScreenshotStore.Photo::path).toList():java.util.List.of(requested);
        files.submit(()->{try{var entries=store.list();SwingUtilities.invokeLater(()->{if(closed)return;model.clear();entries.forEach(model::addElement);for(int i=0;i<model.size();i++)if(selection.contains(model.get(i).path()))photos.addSelectionInterval(i,i);status.setText(entries.size()+" screenshots · Temporary photos expire after seven days. Keep saves them indefinitely.");select();});}catch(Exception ex){SwingUtilities.invokeLater(()->status.setText("Could not load screenshots: "+ex.getMessage()));}});
    }
    private void select(){int token=++generation;boolean single=photos.getSelectedIndices().length==1;keep.setEnabled(single);delete.setEnabled(single);location.setEnabled(single);preview.image=null;preview.repaint();
        if(!single){detail.setText("Select one screenshot");keep.setSelected(false);return;}
        var photo=photos.getSelectedValue();keep.setSelected(photo.kept());detail.setText(photo.time(System.currentTimeMillis()));
        try{images.execute(()->{BufferedImage image=read(photo.path(),1600);SwingUtilities.invokeLater(()->{if(!closed&&token==generation){preview.image=image;preview.repaint();if(image==null)detail.setText("Image could not be opened");}});});}catch(RejectedExecutionException ignored){}
    }
    private static BufferedImage read(Path path,int limit){try(ImageInputStream in=ImageIO.createImageInputStream(path.toFile())){if(in==null)return null;var readers=ImageIO.getImageReaders(in);if(!readers.hasNext())return null;ImageReader reader=readers.next();try{reader.setInput(in);int sample=Math.max(1,Math.max(reader.getWidth(0),reader.getHeight(0))/limit);var param=reader.getDefaultReadParam();param.setSourceSubsampling(sample,sample,0,0);return reader.read(0,param);}finally{reader.dispose();}}catch(Exception e){return null;}}
    private final class Thumbnail extends JPanel implements ListCellRenderer<ScreenshotStore.Photo>{ScreenshotStore.Photo photo;boolean selected;
        public Component getListCellRendererComponent(JList<? extends ScreenshotStore.Photo> list,ScreenshotStore.Photo p,int index,boolean selected,boolean focus){photo=p;this.selected=selected;if(!thumbnails.containsKey(p.path())&&pending.add(p.path())){try{images.execute(()->{BufferedImage image=read(p.path(),360);SwingUtilities.invokeLater(()->{pending.remove(p.path());if(!closed){thumbnails.put(p.path(),image);photos.repaint();}});});}catch(RejectedExecutionException ex){pending.remove(p.path());}}return this;}
        @Override protected void paintComponent(Graphics graphics){Graphics2D g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setColor(palette.background);g.fillRect(0,0,getWidth(),getHeight());g.setColor(selected?palette.accent:palette.line);g.fillRoundRect(4,4,getWidth()-8,getHeight()-8,12,12);g.setColor(palette.controlSurface());g.fillRoundRect(6,6,getWidth()-12,getHeight()-12,10,10);BufferedImage image=thumbnails.get(photo.path());if(image!=null)draw(g,image,new Rectangle(12,12,getWidth()-24,104));else{g.setColor(palette.muted);g.drawString("Preview unavailable",16,65);}String time=photo.shortTime(System.currentTimeMillis());g.setColor(palette.accent);g.fillRoundRect(12,121,64,22,8,8);g.setColor(palette.selectionText());g.drawString(time,22,137);g.dispose();}
    }
    private static void draw(Graphics2D g,BufferedImage image,Rectangle r){double ratio=Math.min((double)r.width/image.getWidth(),(double)r.height/image.getHeight());int w=(int)(image.getWidth()*ratio),h=(int)(image.getHeight()*ratio);g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);g.drawImage(image,r.x+(r.width-w)/2,r.y+(r.height-h)/2,w,h,null);}
    private final class ImagePreview extends JPanel{BufferedImage image;@Override protected void paintComponent(Graphics g){g.setColor(palette.controlSurface());g.fillRect(0,0,getWidth(),getHeight());if(image!=null)draw((Graphics2D)g,image,new Rectangle(8,8,Math.max(1,getWidth()-16),Math.max(1,getHeight()-16)));}}
    @Override public void dispose(){closed=true;generation++;timer.stop();files.shutdownNow();images.shutdownNow();thumbnails.clear();super.dispose();}
}
