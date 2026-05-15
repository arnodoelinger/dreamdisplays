package com.dreamdisplays.media;

import org.jspecify.annotations.NullMarked;

import java.nio.ByteBuffer;

/**
 * Per-sample / pixel adjustments applied to raw RGBA video frames and S16LE audio
 * before they reach the GPU / audio line.
 */
@NullMarked
public final class MediaBufferEffects {

    private MediaBufferEffects() {
    }

    public static void applyBrightness(ByteBuffer buffer, double brightness) {
        if (Math.abs(brightness - 1.0) < 1e-5) return;
        buffer.rewind();
        while (buffer.remaining() >= 4) {
            int r = (int) Math.min(255, (buffer.get() & 0xFF) * brightness);
            int g = (int) Math.min(255, (buffer.get() & 0xFF) * brightness);
            int b = (int) Math.min(255, (buffer.get() & 0xFF) * brightness);
            byte a = buffer.get();
            buffer.position(buffer.position() - 4);
            buffer.put((byte) r).put((byte) g).put((byte) b).put(a);
        }
        buffer.flip();
    }

    public static void applyVolumeS16LE(byte[] buf, int len, double volume) {
        if (Math.abs(volume - 1.0) < 1e-5) return;
        for (int i = 0; i + 1 < len; i += 2) {
            int lo = buf[i] & 0xFF;
            int hi = buf[i + 1];
            int s = (hi << 8) | lo;
            int scaled = (int) (s * volume);
            if (scaled > 32767) scaled = 32767;
            else if (scaled < -32768) scaled = -32768;
            buf[i] = (byte) (scaled & 0xFF);
            buf[i + 1] = (byte) ((scaled >> 8) & 0xFF);
        }
    }
}
