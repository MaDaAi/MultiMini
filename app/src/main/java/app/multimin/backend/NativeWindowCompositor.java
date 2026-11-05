package app.multimin.backend;

/** DWM maintains live window surfaces without CPU capture or image transport. */
public final class NativeWindowCompositor {
    private static final boolean LOADED = SharedFrameBridge.AVAILABLE;
    private NativeWindowCompositor() {}
    public static boolean available() { return LOADED; }
    public static native int captureAffinity(long window,int affinity);
    public static native long destination(int x,int y,int width,int height);
    public static native long register(long destination,long source);
    public static native long size(long thumbnail);
    public static native boolean update(long thumbnail,long destination,int x,int y,int width,int height,
        int sourceX,int sourceY,int sourceWidth,int sourceHeight,int opacity);
    public static native void unregister(long thumbnail);
}
