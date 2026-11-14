package app.multimin.backend;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/** Reusable WIC high-quality resize; Java2D remains the fallback. */
public final class NativeImageScaler {
    private BufferedImage input, output;
    private boolean disabled;
    public BufferedImage resize(BufferedImage image,int width,int height) {
        if(disabled || !SharedFrameBridge.AVAILABLE || image.getType()!=BufferedImage.TYPE_INT_ARGB || width<=0 || height<=0)return null;
        if(input==image && output!=null && output.getWidth()==width && output.getHeight()==height)return output;
        if(output==null || output.getWidth()!=width || output.getHeight()!=height) {
            output=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
        }
        try {
            int[] pixels=((DataBufferInt)image.getRaster().getDataBuffer()).getData();
            int[] destination=((DataBufferInt)output.getRaster().getDataBuffer()).getData();
            if(!SharedFrameBridge.resize(pixels,image.getWidth(),image.getHeight(),destination,width,height))return null;
            input=image;return output;
        } catch(LinkageError error){disabled=true;return null;}
    }
}
