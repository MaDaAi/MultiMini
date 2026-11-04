package app.multimin.ui;
import javax.swing.Icon;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.geom.RoundRectangle2D;
import java.util.concurrent.ConcurrentHashMap;
/** Publisher-supplied PNG, cached at device resolution for sharp small icons. */
final class LogoIcon implements Icon {
 private static final BufferedImage SOURCE=load();
 private static final ConcurrentHashMap<Integer,BufferedImage> CACHE=new ConcurrentHashMap<>();
 private final int size;
 LogoIcon(int size){this.size=size;}
 private static BufferedImage load(){try(var in=LogoIcon.class.getResourceAsStream("/app/multimin/multimini-logo.png")){if(in==null)throw new IllegalStateException("PNG logo missing");return ImageIO.read(in);}catch(Exception e){throw new IllegalStateException("Cannot load logo",e);}}
 private static BufferedImage render(int size){return CACHE.computeIfAbsent(size,n->{BufferedImage image=new BufferedImage(n,n,BufferedImage.TYPE_INT_ARGB);Graphics2D g=image.createGraphics();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);g.setClip(new RoundRectangle2D.Double(0,0,n,n,Math.max(4,n/5.0),Math.max(4,n/5.0)));g.setColor(new Color(26,26,26));g.fillRect(0,0,n,n);g.drawImage(SOURCE,0,0,n,n,null);g.dispose();return image;});}
 public int getIconWidth(){return size;}public int getIconHeight(){return size;}
 public void paintIcon(Component c,Graphics graphics,int x,int y){Graphics2D g=(Graphics2D)graphics.create();int pixels=Math.max(size,(int)Math.ceil(size*g.getTransform().getScaleX()));g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);g.drawImage(render(pixels),x,y,size,size,null);g.dispose();}
 BufferedImage toImage(){return render(size);}
}
