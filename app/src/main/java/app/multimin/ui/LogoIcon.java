package app.multimin.ui;
import javax.swing.Icon;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.concurrent.ConcurrentHashMap;
/** Publisher-supplied SVG geometry, cached at device resolution for sharp small icons. */
final class LogoIcon implements Icon {

 private static final ConcurrentHashMap<Integer,BufferedImage> CACHE=new ConcurrentHashMap<>();
 private final int size;
 LogoIcon(int size){this.size=size;}
 // Exact paths and colors from the publisher-supplied 800x800 SVG.
 private static BufferedImage render(int size){return CACHE.computeIfAbsent(size,n->{BufferedImage image=new BufferedImage(n,n,BufferedImage.TYPE_INT_ARGB);Graphics2D g=image.createGraphics();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.scale(n/800.0,n/800.0);
{var path=new java.awt.geom.Path2D.Double();path.moveTo(0,0);path.lineTo(800,0);path.lineTo(800,800);path.lineTo(0,800);path.closePath();g.setColor(new Color(0x1a1a1a));g.fill(path);}{var path=new java.awt.geom.Path2D.Double();path.moveTo(543.307,532.323);g.setColor(new Color(0x005150));g.fill(path);}{var path=new java.awt.geom.Path2D.Double();path.moveTo(126.057,658.396);path.lineTo(126.057,72.247);path.lineTo(275.502,72.247);path.lineTo(409.886,425.443);path.lineTo(508.358,72.247);path.lineTo(653.169,72.247);path.lineTo(653.169,515.404);path.lineTo(653.169,658.396);path.closePath();g.setColor(new Color(0x00928e));g.fill(path);}{var path=new java.awt.geom.Path2D.Double();path.moveTo(587.813,653.092);path.lineTo(518.194,580.199);path.lineTo(518.194,843.505);path.lineTo(653.169,843.505);path.lineTo(653.169,580.199);path.closePath();g.setColor(new Color(0x1a1a1a));g.fill(path);}{var path=new java.awt.geom.Path2D.Double();path.moveTo(587.813,666.25);path.lineTo(518.194,622.194);path.lineTo(518.194,781.335);path.lineTo(653.169,781.335);path.lineTo(653.169,622.194);path.closePath();g.setColor(new Color(0xa11740));g.fill(path);}{var path=new java.awt.geom.Path2D.Double();path.moveTo(559.397,854.867);path.lineTo(559.397,690.487);path.lineTo(587.813,720.239);path.lineTo(616.228,690.487);path.lineTo(616.228,854.867);path.closePath();g.setColor(new Color(0x1a1a1a));g.fill(path);}{var path=new java.awt.geom.Path2D.Double();path.moveTo(518.194,381.267);path.lineTo(421.878,655.647);path.lineTo(278.081,374.231);path.lineTo(278.081,932.372);path.lineTo(518.194,941.752);path.closePath();g.setColor(new Color(0x1a1a1a));g.fill(path);}{var path=new java.awt.geom.Path2D.Double();path.moveTo(587.813,653.092);path.lineTo(653.169,580.199);path.lineTo(653.169,622.194);path.lineTo(589.054,666.25);path.lineTo(518.194,622.194);path.lineTo(518.194,580.199);path.closePath();g.setColor(new Color(0x1a1a1a));g.fill(path);g.setStroke(new BasicStroke(1f));g.draw(path);}g.dispose();return image;});}
 public int getIconWidth(){return size;}public int getIconHeight(){return size;}
 public void paintIcon(Component c,Graphics graphics,int x,int y){Graphics2D g=(Graphics2D)graphics.create();int pixels=Math.max(size,(int)Math.ceil(size*g.getTransform().getScaleX()));g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);g.drawImage(render(pixels),x,y,size,size,null);g.dispose();}
 BufferedImage toImage(){return render(size);}
}
