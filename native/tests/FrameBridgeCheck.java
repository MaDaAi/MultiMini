import app.multimin.backend.WindowFrameReader;

/** Run against the fixture in scripts/check-frame-bridge.py. */
public class FrameBridgeCheck {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        boolean expectShared = Boolean.parseBoolean(args[1]);
        // Linux/macOS frames are opaque RGB by design (games leave alpha at zero).
        int expected = app.multimin.backend.PlatformSupport.isPortable() ? 0xff432d17 : 0x80432d17;
        for (int round = 0; round < 10; round++) {
            try (var reader = new WindowFrameReader()) {
                for (int i = 0; i < 20; i++) {
                    String name = i % 2 == 0 ? "small" : "large";
                    var frame = reader.readMemory(name, 0, port);
                    if (frame == null || frame.image.getRGB(0, 0) != expected ||
                        frame.image.getRGB(frame.image.getWidth()-1, frame.image.getHeight()-1) != expected)
                        throw new AssertionError("Pixel format mismatch");
                    if (frame.backend.contains("shared") != expectShared)
                        throw new AssertionError("Unexpected transport: " + frame.backend);
                    if (reader.readMemory(name, frame.sequence, port) != null)
                        throw new AssertionError("Unchanged frame was delivered twice");
                }
            }
        }
        System.out.println("PASS: pixel order/alpha, resize, unchanged frames, reconnect and close; shared=" + expectShared);
    }
}
