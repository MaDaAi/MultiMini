package app.multimin.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.awt.datatransfer.*;
import java.io.IOException;
import java.util.function.Consumer;

/** Frozen screen selection; cropping is mapped back to original physical pixels. */
final class ScreenshotSelection extends JDialog {
    private Point start; private Rectangle selection;
    private final BufferedImage image; private final Palette palette;
    private final JPanel actions=new JPanel(new FlowLayout(FlowLayout.CENTER,6,6));
    ScreenshotSelection(Window owner, Rectangle screen, BufferedImage image, Palette palette, Consumer<BufferedImage> save, Runnable finished) {
        super((Frame)null);this.image=image;this.palette=palette;setUndecorated(true);setModal(false);setAlwaysOnTop(true);setFocusableWindowState(true);setAutoRequestFocus(true);setBounds(screen);setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        JPanel canvas=new JPanel(null){@Override protected void paintComponent(Graphics graphics){Graphics2D g=(Graphics2D)graphics.create();g.drawImage(image,0,0,getWidth(),getHeight(),null);g.setColor(new Color(palette.accent.getRed(),palette.accent.getGreen(),palette.accent.getBlue(),45));g.fillRect(0,0,getWidth(),getHeight());if(selection!=null){Graphics2D clear=(Graphics2D)g.create();clear.clip(selection);clear.drawImage(image,0,0,getWidth(),getHeight(),null);clear.dispose();g.setColor(palette.accent);g.setStroke(new BasicStroke(2));g.draw(selection);}else{g.setColor(Color.WHITE);g.setFont(new Font(Font.DIALOG,Font.BOLD,16));g.drawString("Drag to select a region · Esc to cancel",24,32);}g.dispose();}};
        actions.setBackground(palette.controlSurface());actions.setVisible(false);canvas.add(actions);
        String[] labels={"Copy","Save","Copy and Save","Cancel"};
        for(int i=0;i<labels.length;i++){final int action=i;JButton button=new ModernButton(labels[i],null,palette,i==2);button.addActionListener(e->{if(action==3){dispose();return;}try{BufferedImage crop=crop();if(action==0||action==2)copy(crop);if(action==1||action==2)save.accept(crop);dispose();}catch(Exception error){JOptionPane.showMessageDialog(this,"Could not complete screenshot: "+error.getMessage());}});actions.add(button);}
        MouseAdapter drag=new MouseAdapter(){@Override public void mousePressed(MouseEvent e){start=e.getPoint();selection=null;actions.setVisible(false);canvas.repaint();}@Override public void mouseDragged(MouseEvent e){if(start==null)return;int x=Math.clamp(e.getX(),0,canvas.getWidth()),y=Math.clamp(e.getY(),0,canvas.getHeight());selection=new Rectangle(Math.min(start.x,x),Math.min(start.y,y),Math.abs(x-start.x),Math.abs(y-start.y));canvas.repaint();}@Override public void mouseReleased(MouseEvent e){if(selection==null||selection.width<2||selection.height<2)return;Dimension size=actions.getPreferredSize();actions.setBounds(Math.clamp(selection.x,0,Math.max(0,canvas.getWidth()-size.width)),Math.clamp(selection.y+selection.height+8,0,Math.max(0,canvas.getHeight()-size.height)),size.width,size.height);actions.setVisible(true);canvas.repaint();}};
        canvas.addMouseListener(drag);canvas.addMouseMotionListener(drag);canvas.setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));setContentPane(canvas);
        getRootPane().registerKeyboardAction(e->dispose(),KeyStroke.getKeyStroke("ESCAPE"),JComponent.WHEN_IN_FOCUSED_WINDOW);
        addWindowListener(new WindowAdapter(){@Override public void windowClosed(WindowEvent e){finished.run();}});
    }
    private BufferedImage crop(){double sx=image.getWidth()/(double)getContentPane().getWidth(),sy=image.getHeight()/(double)getContentPane().getHeight();int x=(int)Math.floor(selection.x*sx),y=(int)Math.floor(selection.y*sy);int right=Math.min(image.getWidth(),(int)Math.ceil((selection.x+selection.width)*sx)),bottom=Math.min(image.getHeight(),(int)Math.ceil((selection.y+selection.height)*sy));BufferedImage result=new BufferedImage(right-x,bottom-y,BufferedImage.TYPE_INT_RGB);Graphics2D g=result.createGraphics();g.drawImage(image,0,0,right-x,bottom-y,x,y,right,bottom,null);g.dispose();return result;}
    static void copy(BufferedImage image){Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new Transferable(){public DataFlavor[] getTransferDataFlavors(){return new DataFlavor[]{DataFlavor.imageFlavor};}public boolean isDataFlavorSupported(DataFlavor flavor){return DataFlavor.imageFlavor.equals(flavor);}public Object getTransferData(DataFlavor flavor)throws UnsupportedFlavorException,IOException{if(!isDataFlavorSupported(flavor))throw new UnsupportedFlavorException(flavor);return image;}},null);}
}
