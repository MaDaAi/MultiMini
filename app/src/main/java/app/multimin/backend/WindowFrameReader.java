package app.multimin.backend;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads the newest WGC frame from loopback memory, with the disk capture as fallback. */
public final class WindowFrameReader implements AutoCloseable {
    public static final class Frame {
        public final BufferedImage image;
        public final long sequence, latencyMillis;
        public final String backend;
        public long producedNanos;
        public Frame(BufferedImage image, long sequence, long latencyMillis, String backend) {
            this.image = image; this.sequence = sequence; this.latencyMillis = latencyMillis; this.backend = backend;
        }
    }

    private Socket socket;
    private DataInputStream input;
    private DataOutputStream output;
    private int connectedPort;
    private byte[] pixelBuffer = new byte[0];
    private boolean sharedEnabled = SharedFrameBridge.AVAILABLE;
    private long sharedMapping;
    private String sharedName = "";
    private int sharedBytes;

    public Frame readMemory(String id, long previousSequence, int port) throws IOException {
        return readMemory(id, previousSequence, port, null);
    }

    public synchronized Frame readMemory(String id, long previousSequence, int port, BufferedImage reusable) throws IOException {
        if (port <= 0) return null;
        if (socket == null || socket.isClosed() || connectedPort != port) connect(port);
        byte[] name = id.getBytes(StandardCharsets.UTF_8);
        if (name.length > 4096) return null;
        try {
            output.writeShort(name.length | (sharedEnabled ? 0x8000 : 0)); output.write(name); output.writeLong(previousSequence); output.flush();
            int width = input.readInt(), height = input.readInt();
            long sequence = input.readLong(), produced = input.readLong();
            int length = input.readInt();
            if (width <= 0 || height <= 0 || length == 0) return null;
            boolean shared = length == -1;
            long bytes = (long) width * height * 4;
            if (width > 16384 || height > 16384 || bytes > 256 * 1024 * 1024 || (!shared && bytes != length)) throw new IOException("Invalid frame size");
            if (shared) {
                int nameSize = input.readUnsignedShort();
                if (!sharedEnabled || nameSize <= 0 || nameSize > 200) throw new IOException("Invalid shared frame response");
                String mappingName = new String(input.readNBytes(nameSize), StandardCharsets.US_ASCII);
                if (!mappingName.startsWith("Local\\MultiMiniFrame-")) throw new IOException("Invalid shared frame name");
                if (!mappingName.equals(sharedName) || bytes != sharedBytes) {
                    releaseSharedMapping();
                    sharedMapping = SharedFrameBridge.open(mappingName, (int) bytes);
                    if (sharedMapping == 0) { sharedEnabled = false; throw new IOException("Shared frame mapping failed; using socket fallback"); }
                    sharedName = mappingName; sharedBytes = (int) bytes;
                }
            } else {
                if (pixelBuffer.length < length) pixelBuffer = new byte[length];
                input.readFully(pixelBuffer, 0, length);
            }
            BufferedImage image = reusable != null && reusable.getWidth() == width && reusable.getHeight() == height
                && reusable.getType() == BufferedImage.TYPE_INT_ARGB
                ? reusable : new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            int[] argb = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
            if (shared) SharedFrameBridge.copy(sharedMapping, argb);
            else java.nio.ByteBuffer.wrap(pixelBuffer, 0, length).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .asIntBuffer().get(argb);
            long latency = Math.max(0, (System.nanoTime() - produced) / 1_000_000);
            Frame frame = new Frame(image, sequence, latency, shared ? "Windows Graphics Capture · shared memory" : "Windows Graphics Capture · memory");
            frame.producedNanos = produced;
            return frame;
        } catch (IOException error) { sharedEnabled = false; close(); throw error; }
    }

    public Frame readDisk(String path, long previousModified, long previousSize) throws IOException {
        Path file = Path.of(path);
        if (!Files.isRegularFile(file)) return null;
        long modified = Files.getLastModifiedTime(file).toMillis(), size = Files.size(file);
        if (modified == previousModified && size == previousSize) return null;
        BufferedImage image = ImageIO.read(file.toFile());
        return image == null ? null : new Frame(image, modified, Math.max(0, System.currentTimeMillis() - modified), "Window capture · BMP fallback");
    }

    private void connect(int port) throws IOException {
        close(); socket = new Socket();
        socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 800);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(2500); connectedPort = port;
        input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
    }

    @Override public synchronized void close() {
        releaseSharedMapping();
        try { if (socket != null) socket.close(); } catch (IOException ignored) { }
        socket = null; input = null; output = null; connectedPort = 0;
    }
    private void releaseSharedMapping() {
        if (sharedMapping != 0) SharedFrameBridge.release(sharedMapping);
        sharedMapping = 0; sharedName = ""; sharedBytes = 0;
    }
}
