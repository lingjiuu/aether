package io.github.lingjiuu.tool.builtin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

final class ImageMimeDetector {

    private static final int IMAGE_TYPE_SNIFF_BYTES = 4100;
    private static final byte[] PNG_SIGNATURE = new byte[]{
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };

    private ImageMimeDetector() {
    }

    static String detect(Path path) throws IOException {
        try (InputStream inputStream = Files.newInputStream(path)) {
            return detect(inputStream.readNBytes(IMAGE_TYPE_SNIFF_BYTES));
        }
    }

    static String detect(byte[] buffer) {
        byte[] safeBuffer = buffer == null ? new byte[0] : buffer;
        if (startsWith(safeBuffer, new int[]{0xff, 0xd8, 0xff})) {
            return safeBuffer.length > 3 && unsigned(safeBuffer[3]) == 0xf7 ? null : "image/jpeg";
        }
        if (startsWith(safeBuffer, PNG_SIGNATURE)) {
            return isPng(safeBuffer) && !isAnimatedPng(safeBuffer) ? "image/png" : null;
        }
        if (startsWithAscii(safeBuffer, 0, "GIF")) {
            return "image/gif";
        }
        if (startsWithAscii(safeBuffer, 0, "RIFF") && startsWithAscii(safeBuffer, 8, "WEBP")) {
            return "image/webp";
        }
        return null;
    }

    private static boolean isPng(byte[] buffer) {
        return buffer.length >= 16
                && readUint32BE(buffer, PNG_SIGNATURE.length) == 13
                && startsWithAscii(buffer, 12, "IHDR");
    }

    private static boolean isAnimatedPng(byte[] buffer) {
        int offset = PNG_SIGNATURE.length;
        while (offset + 8 <= buffer.length) {
            long chunkLength = readUint32BE(buffer, offset);
            int chunkTypeOffset = offset + 4;
            if (startsWithAscii(buffer, chunkTypeOffset, "acTL")) {
                return true;
            }
            if (startsWithAscii(buffer, chunkTypeOffset, "IDAT")) {
                return false;
            }

            long nextOffset = offset + 8L + chunkLength + 4L;
            if (nextOffset <= offset || nextOffset > buffer.length || nextOffset > Integer.MAX_VALUE) {
                return false;
            }
            offset = (int) nextOffset;
        }
        return false;
    }

    private static int readUint32BE(byte[] buffer, int offset) {
        return (unsignedAt(buffer, offset) * 0x1000000)
                + (unsignedAt(buffer, offset + 1) << 16)
                + (unsignedAt(buffer, offset + 2) << 8)
                + unsignedAt(buffer, offset + 3);
    }

    private static boolean startsWith(byte[] buffer, int[] bytes) {
        if (buffer.length < bytes.length) {
            return false;
        }
        for (int i = 0; i < bytes.length; i++) {
            if (unsigned(buffer[i]) != bytes[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean startsWith(byte[] buffer, byte[] bytes) {
        if (buffer.length < bytes.length) {
            return false;
        }
        for (int i = 0; i < bytes.length; i++) {
            if (buffer[i] != bytes[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean startsWithAscii(byte[] buffer, int offset, String text) {
        if (buffer.length < offset + text.length()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (unsigned(buffer[offset + i]) != text.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static int unsignedAt(byte[] buffer, int offset) {
        return offset < buffer.length ? unsigned(buffer[offset]) : 0;
    }

    private static int unsigned(byte value) {
        return value & 0xff;
    }
}
