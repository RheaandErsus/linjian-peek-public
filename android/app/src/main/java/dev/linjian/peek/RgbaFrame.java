package dev.linjian.peek;

import java.nio.ByteBuffer;

/** ImageReader may omit the final row's padding; only copy actual RGBA pixels. */
final class RgbaFrame {
    static ByteBuffer compact(ByteBuffer source, int width, int height, int pixelStride, int rowStride) {
        long rowBytes = (long) (width - 1) * pixelStride + 4;
        long required = (long) (height - 1) * rowStride + rowBytes;
        long length = (long) width * height * 4;
        if (width <= 0 || height <= 0 || pixelStride < 4 || rowStride < rowBytes
                || length > Integer.MAX_VALUE || required > source.remaining()) {
            throw new IllegalArgumentException("Invalid RGBA frame dimensions or strides");
        }
        ByteBuffer result = ByteBuffer.allocate((int) length);
        int offset = source.position();
        for (int row = 0; row < height; row++) {
            int start = offset + row * rowStride;
            if (pixelStride == 4) {
                ByteBuffer line = source.duplicate(); line.position(start); line.limit(start + width * 4);
                result.put(line);
            } else {
                for (int col = 0; col < width; col++) for (int channel = 0; channel < 4; channel++) result.put(source.get(start + col * pixelStride + channel));
            }
        }
        result.flip();
        return result;
    }
}
