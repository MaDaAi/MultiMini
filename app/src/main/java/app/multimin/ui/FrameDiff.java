package app.multimin.ui;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;

/** Finds which rows of two consecutive captures differ, using vectorised array comparisons. */
final class FrameDiff {
    private static final int BLOCK_ROWS = 8;

    private FrameDiff() { }

    /**
     * Returns {firstChangedRow, rowAfterLastChangedRow}, null when the images are identical,
     * or the full height when they cannot be compared (different size or raster type).
     */
    static int[] changedRows(BufferedImage previous, BufferedImage next) {
        int width = next.getWidth(), height = next.getHeight();
        if (previous == null || previous.getWidth() != width || previous.getHeight() != height
            || !(previous.getRaster().getDataBuffer() instanceof DataBufferInt a)
            || !(next.getRaster().getDataBuffer() instanceof DataBufferInt b)
            || a.getNumBanks() != 1 || b.getNumBanks() != 1
            || previous.getRaster().getWidth() != width || next.getRaster().getWidth() != width)
            return new int[]{0, height};
        int[] old = a.getData(), now = b.getData();
        int total = width * height;
        if (old.length < total || now.length < total) return new int[]{0, height};
        int first = Arrays.mismatch(old, 0, total, now, 0, total);
        if (first < 0) return null;
        int top = first / width;
        int bottom = height;
        // Walk back in blocks of rows from the end; each comparison stops at the first difference.
        while (bottom > top) {
            int blockTop = Math.max(top, bottom - BLOCK_ROWS);
            int from = blockTop * width, to = bottom * width;
            if (!Arrays.equals(old, from, to, now, from, to)) {
                // The last differing row lies inside this block.
                for (int row = bottom - 1; row >= blockTop; row--)
                    if (!Arrays.equals(old, row * width, (row + 1) * width, now, row * width, (row + 1) * width)) return new int[]{top, row + 1};
            }
            bottom = blockTop;
        }
        return new int[]{top, top + 1};
    }
}
