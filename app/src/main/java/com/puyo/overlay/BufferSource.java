package com.puyo.overlay;

import java.nio.ByteBuffer;

/** ImageReader(RGBA_8888) 한 프레임을 복사 없이 읽는 PixelSource. 프레임을 닫기 전에만 사용. */
final class BufferSource implements PixelSource {
    private final ByteBuffer buf;
    private final int w, h, rowStride, pixelStride;

    BufferSource(ByteBuffer buf, int w, int h, int rowStride, int pixelStride) {
        this.buf = buf;
        this.w = w;
        this.h = h;
        this.rowStride = rowStride;
        this.pixelStride = pixelStride;
    }

    public int width() { return w; }
    public int height() { return h; }

    public int rgb(int x, int y) {
        int o = y * rowStride + x * pixelStride;
        return ((buf.get(o) & 255) << 16) | ((buf.get(o + 1) & 255) << 8) | (buf.get(o + 2) & 255);
    }
}
