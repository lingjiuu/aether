package io.github.lingjiuu.tool.tools;

import junit.framework.TestCase;

public class ImageMimeDetectorTest extends TestCase {

    public void testDetectsPng() {
        assertEquals("image/png", ImageMimeDetector.detect(new byte[]{
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
                0x00, 0x00, 0x00, 0x0d,
                0x49, 0x48, 0x44, 0x52
        }));
    }

    public void testRejectsNonImageBytes() {
        assertNull(ImageMimeDetector.detect("hello".getBytes()));
    }

    public void testDetectsGif() {
        assertEquals("image/gif", ImageMimeDetector.detect(new byte[]{'G', 'I', 'F', '8', '9', 'a'}));
    }
}
