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
        public Frame(BufferedImage image, long sequence, long latencyMillis, String backend) {
            this.image = image; this.sequence = sequence; this.latencyMillis = latencyMillis; this.backend = backend;
        }
    }

    private Socket socket;
    private DataInputStream input;
    private DataOutputStream output;
    private int connectedPort;
    private byte[] pixelBuffer = new byte[0];

    public Frame readMemory(String id, long previousSequence, int port) throws IOException {
        return readMemory(id, previousSequence, port, null);
    }

    public Frame readMemory(String id, long previousSequence, int port, BufferedImage reusable) throws IOException {
        if (port <= 0) return null;
        if (socket == null || socket.isClosed() || connectedPort != port) connect(port);
        byte[] name = id.getBytes(StandardCharsets.UTF_8);
        if (name.length > 4096) return null;
        try {
            output.writeShort(name.length); output.write(name); output.writeLong(previousSequence); output.flush();
            int width = input.readInt(), height = input.readInt();
            long sequence = input.readLong(), produced = input.readLong();
            int length = input.readInt();
            if (width <= 0 || height <= 0 || length == 0) return null;
            if (width > 16384 || height > 16384 || (long) width * height * 4 != length) throw new IOException("Invalid frame size");
            if (pixelBuffer.length < length) pixelBuffer = new byte[length];
            input.readFully(pixelBuffer, 0, length);
            BufferedImage image = reusable != null && reusable.getWidth() == width && reusable.getHeight() == height
                && reusable.getType() == BufferedImage.TYPE_INT_ARGB
                ? reusable : new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            int[] argb = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
            for (int i = 0, p = 0; i < argb.length; i++, p += 4)
                argb[i] = ((pixelBuffer[p + 3] & 255) << 24) | ((pixelBuffer[p + 2] & 255) << 16)
                    | ((pixelBuffer[p + 1] & 255) << 8) | (pixelBuffer[p] & 255);
            long latency = Math.max(0, (System.nanoTime() - produced) / 1_000_000);
            return new Frame(image, sequence, latency, "Windows Graphics Capture · memory");
        } catch (IOException error) { close(); throw error; }
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
        socket.setSoTimeout(2500); connectedPort = port;
        input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
    }

    @Override public void close() {
        try { if (socket != null) socket.close(); } catch (IOException ignored) { }
        socket = null; input = null; output = null; connectedPort = 0;
    }
}
