package com.dreamdisplays.util;

import java.nio.ByteBuffer;

/**
 * Native (temporary not) image format converter for high-performance pixel operations.
 * Converts RGBA/ABGR formats using native code (temporarily implemented in Java).
 */
public class ConverterUtil {

    // Java implementation for image scaling with aspect ratio preservation (cover mode)
    private static void scaleRGBAImageJava(
            ByteBuffer srcBuffer,
            int srcW,
            int srcH,
            ByteBuffer dstBuffer,
            int dstW,
            int dstH
    ) {
        if (srcBuffer == null || dstBuffer == null) {
            throw new IllegalArgumentException(
                    "Source and destination buffers cannot be null"
            );
        }

        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) {
            throw new IllegalArgumentException(
                    "Image dimensions must be positive"
            );
        }

        // Calculate scaling to maintain aspect ratio (cover mode)
        double scaleW = (double) dstW / srcW;
        double scaleH = (double) dstH / srcH;
        double scale = Math.max(scaleW, scaleH); // Use larger scale to cover entire area
        int scaledW = (int) (srcW * scale + 0.5);
        int scaledH = (int) (srcH * scale + 0.5);

        // Calculate offsets to center the image
        int offsetX = (dstW - scaledW) / 2;
        int offsetY = (dstH - scaledH) / 2;

        // Fill destination with black (transparent)
        for (int i = 0; i < dstW * dstH * 4; i++) {
            dstBuffer.put(i, (byte) 0);
        }

        // Nearest neighbor scaling with centering
        for (int y = 0; y < dstH; y++) {
            int srcY = (int) (((y - offsetY) * srcH) / (double) scaledH);

            if (srcY < 0 || srcY >= srcH) continue;

            for (int x = 0; x < dstW; x++) {
                int srcX = (int) (((x - offsetX) * srcW) / (double) scaledW);

                if (srcX >= 0 && srcX < srcW) {
                    // Copy 4 bytes (RGBA) from source to destination
                    int srcIdx = (srcY * srcW + srcX) * 4;
                    int dstIdx = (y * dstW + x) * 4;

                    int pixel = srcBuffer.getInt(srcIdx);
                    dstBuffer.putInt(dstIdx, pixel);
                }
            }
        }
    }

    // Scale RGBA image using nearest neighbor scaling - pure Java implementation
    public static void scaleRGBA(
            ByteBuffer srcBuffer,
            int srcW,
            int srcH,
            ByteBuffer dstBuffer,
            int dstW,
            int dstH
    ) {
        scaleRGBAImageJava(srcBuffer, srcW, srcH, dstBuffer, dstW, dstH);
    }
}
