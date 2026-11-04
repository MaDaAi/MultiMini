package app.multimin.ui;

import app.multimin.model.Source;
import java.awt.*;
import java.awt.image.BufferedImage;

/** Desktop integration test for physical-pixel preservation at Windows HiDPI. */
public class FrameQualityCheck {
    static BufferedImage render(Image input, int width, int height, double sx, double sy) {
        var output = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        var g = output.createGraphics();
        g.scale(sx, sy);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(input, 0, 0, (int)Math.round(width/sx), (int)Math.round(height/sy), null);
        g.dispose(); return output;
    }
    static int differences(BufferedImage expected, BufferedImage actual) {
        int count = 0;
        for (int y=0;y<expected.getHeight();y++) for (int x=0;x<expected.getWidth();x++)
            if (expected.getRGB(x,y) != actual.getRGB(x,y)) count++;
        return count;
    }
    public static void main(String[] args) {
        var config = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        double sx=config.getDefaultTransform().getScaleX(), sy=config.getDefaultTransform().getScaleY();
        var pixels = new BufferedImage(600,400,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<400;y++) for(int x=0;x<600;x++)
            pixels.setRGB(x,y, ((x+y)%2 == 0) ? 0xff000000 : 0xffffffff);
        var source = new Source("Quality check",Source.Kind.WINDOW);
        source.liveFrame=pixels;
        try(var cache = new FrameTextureCache()) {
            Image result=cache.imageFor(source,config);
            int errors=differences(pixels,render(result,600,400,sx,sy));
            System.out.println("DPI="+sx+"x"+sy+"; native-size pixel mismatches="+errors);
            if(errors!=0) throw new AssertionError("Native-size pixel detail lost");
        }
        var view = new app.multimin.model.View("Quality");
        view.sources.clear(); view.sources.addElement(source);
        var preview = new PreviewPanel(new Palette(), view);
        preview.setLocked(true); preview.setSize(768,432);
        var scaled = new BufferedImage(960,540,BufferedImage.TYPE_INT_ARGB);
        var draw=scaled.createGraphics(); draw.scale(1.25,1.25); preview.paint(draw); draw.dispose();
        int aliased=0;
        for(int y=10;y<190;y++) for(int x=10;x<290;x++) {
            int level=scaled.getRGB(x,y)&255;
            if(level<120 || level>135) aliased++;
        }
        System.out.println("Half-size preview aliased pixels="+aliased);
        if(aliased!=0) throw new AssertionError("Downscaled fine detail aliases instead of filtering");
        var odd = new BufferedImage(601,399,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<399;y++) for(int x=0;x<601;x++) odd.setRGB(x,y,((x+y)%2==0)?0xff000000:0xffffffff);
        source.liveFrame=odd; preview.setSize(1536,864);
        var nativeView=new BufferedImage(1920,1080,BufferedImage.TYPE_INT_ARGB);
        var nativeDraw=nativeView.createGraphics(); nativeDraw.scale(1.25,1.25); preview.paint(nativeDraw); nativeDraw.dispose();
        int blurred=0;
        for(int y=0;y<399;y++) for(int x=0;x<601;x++) if(nativeView.getRGB(x,y)!=odd.getRGB(x,y)) blurred++;
        System.out.println("Odd-size native window pixel mismatches="+blurred);
        if(blurred!=0) throw new AssertionError("Fractional HiDPI bounds blurred a native-size window");
    }
}
