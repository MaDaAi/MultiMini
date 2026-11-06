package app.multimin.ui;
import java.awt.image.*;import app.multimin.backend.NativeImageScaler;
public class ResizeCheck {
 public static void main(String[] args){NativeImageScaler scaler=new NativeImageScaler();BufferedImage a=new BufferedImage(1920,1080,BufferedImage.TYPE_INT_ARGB),b=new BufferedImage(1920,1080,BufferedImage.TYPE_INT_ARGB);java.util.Arrays.fill(((DataBufferInt)a.getRaster().getDataBuffer()).getData(),0xff336699);java.util.Arrays.fill(((DataBufferInt)b.getRaster().getDataBuffer()).getData(),0xff997733);long start=0;for(int i=0;i<220;i++){if(i==20)start=System.nanoTime();BufferedImage input=i%2==0?a:b;BufferedImage out=scaler.resize(input,960,540);if(out==null)throw new AssertionError("Native scaler unavailable");if(out.getRGB(480,270)!=input.getRGB(0,0))throw new AssertionError("Stale or incorrect scaled pixels");}System.out.printf("PASS: 200 alternating native resizes, %.3f ms/frame%n",(System.nanoTime()-start)/200e6);}
}
