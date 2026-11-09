package org.foxprod.rs232_remote;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Wire format for the Stream Deck Mini family (VID 0x0FD9).
 *
 * <p>This class only converts between the application's data and the bytes the
 * device exchanges; it performs no HID I/O and owns no lifecycle.
 *
 * <p>Report layout follows Elgato's Mini HID reference:
 * <ul>
 *   <li>input reports use report ID {@code 0x01} and carry one state byte per key
 *       ({@code 0x00} released, {@code 0x01} pressed) starting at offset 1;</li>
 *   <li>image output reports are always 1024 bytes, with report ID {@code 0x02},
 *       command {@code 0x01}, the chunk index, a show-image flag and the key index
 *       in the 16-byte header.</li>
 * </ul>
 *
 * <p>The chunk index, minimal padding and final-chunk show flag mirror the
 * python-elgato-streamdeck Mini driver, which is known to work on real hardware.
 */
final class StreamDeckMiniProtocol {

    static final int KEY_COUNT = 6;

    /** Both edges of a key image are 80 px, as required by the Mini. */
    static final int KEY_PIXELS = 80;

    static final int INPUT_REPORT_ID = 0x01;
    static final int IMAGE_REPORT_ID = 0x02;
    static final int IMAGE_REPORT_COMMAND = 0x01;
    static final int IMAGE_REPORT_LENGTH = 1024;
    static final int IMAGE_REPORT_HEADER_LENGTH = 16;
    static final int IMAGE_REPORT_PAYLOAD_LENGTH = IMAGE_REPORT_LENGTH - IMAGE_REPORT_HEADER_LENGTH;

    private StreamDeckMiniProtocol() {
    }

    /**
     * Decodes the key states of an input report.
     *
     * @param report the buffer that was read into, possibly longer than the report
     * @param length the number of bytes the read returned
     * @return six key states, or {@code null} when the bytes are not a valid report
     */
    static boolean[] decodeKeys(byte[] report, int length) {
        if (report == null || length < KEY_COUNT + 1 || length > report.length) {
            return null;
        }
        if ((report[0] & 0xFF) != INPUT_REPORT_ID) {
            return null;
        }

        boolean[] keys = new boolean[KEY_COUNT];
        for (int key = 0; key < KEY_COUNT; key++) {
            int state = report[key + 1] & 0xFF;
            if (state != 0x00 && state != 0x01) {
                return null;
            }
            keys[key] = state == 0x01;
        }
        return keys;
    }

    /**
     * Converts one piece of prepared key artwork into the BMP the device stores.
     *
     * <p>The source must be an upright {@value #KEY_PIXELS}x{@value #KEY_PIXELS} image.
     * Transparency is composited onto black, the result is rotated 90 degrees
     * counterclockwise then flipped vertically, and the BMP is written with the bottom-up
     * row order that the device expects. The caller owns {@code image}.
     *
     * @return a complete 24-bit BMP file image
     * @throws IOException when the stream is absent, undecodable or the wrong size
     */
    static byte[] encodeImage(InputStream image) throws IOException {
        if (image == null) {
            throw new IOException("Stream Deck key artwork is missing: no image stream was supplied.");
        }

        BufferedImage source = ImageIO.read(image);
        if (source == null) {
            throw new IOException("Stream Deck key artwork is unreadable; expected an "
                    + KEY_PIXELS + "x" + KEY_PIXELS + " PNG.");
        }
        return encodeImage(source);
    }

    /**
     * Encodes an upright {@value #KEY_PIXELS}x{@value #KEY_PIXELS} image that was
     * rendered in memory rather than read from an asset.
     *
     * @return a complete 24-bit BMP file image
     * @throws IOException when the image is absent or the wrong size
     */
    static byte[] encodeImage(BufferedImage source) throws IOException {
        if (source == null) {
            throw new IOException("Stream Deck key artwork is missing: no image was supplied.");
        }
        if (source.getWidth() != KEY_PIXELS || source.getHeight() != KEY_PIXELS) {
            throw new IOException("Stream Deck key artwork must be "
                    + KEY_PIXELS + "x" + KEY_PIXELS + " pixels, but was "
                    + source.getWidth() + "x" + source.getHeight() + ".");
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(orientForDevice(source), "bmp", output)) {
            throw new IOException("No BMP image writer is available for Stream Deck artwork.");
        }
        byte[] bitmap = output.toByteArray();
        // Preserve the existing 72-DPI fields; ImageIO defaults these to zero.
        ByteBuffer.wrap(bitmap).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(38, 2835).putInt(42, 2835);
        return bitmap;
    }

    /**
     * Splits one encoded key image into the complete sequence of output reports.
     *
     * @param key zero-based key index, 0-5
     * @param bitmap the BMP bytes from {@link #encodeImage(InputStream)}
     * @return every 1024-byte report, in transfer order
     */
    static List<byte[]> imageReports(int key, byte[] bitmap) {
        if (key < 0 || key >= KEY_COUNT) {
            throw new IllegalArgumentException("Stream Deck Mini key index must be 0-" + (KEY_COUNT - 1) + " but was " + key + ".");
        }
        if (bitmap == null) {
            throw new IllegalArgumentException("Encoded key image must not be null.");
        }

        List<byte[]> reports = new ArrayList<>();
        int offset = 0;
        int chunk = 0;
        while (offset < bitmap.length) {
            int length = Math.min(IMAGE_REPORT_PAYLOAD_LENGTH, bitmap.length - offset);
            boolean finalChunk = offset + length >= bitmap.length;

            byte[] report = new byte[IMAGE_REPORT_LENGTH];
            report[0] = IMAGE_REPORT_ID;
            report[1] = IMAGE_REPORT_COMMAND;
            report[2] = (byte) chunk;
            report[3] = 0;
            report[4] = (byte) (finalChunk ? 0x01 : 0x00);
            report[5] = (byte) (key + 1); // the device numbers output keys from one
            // bytes 6..15 are reserved and stay zero
            System.arraycopy(bitmap, offset, report, IMAGE_REPORT_HEADER_LENGTH, length);
            reports.add(report);

            offset += length;
            chunk++;
        }
        return reports;
    }

    /**
     * Applies the Mini's rotation and reflection onto a black background.
     * Transparent pixels therefore reach the device as black.
     */
    private static BufferedImage orientForDevice(BufferedImage source) {
        BufferedImage rotated = new BufferedImage(KEY_PIXELS, KEY_PIXELS, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rotated.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            graphics.setColor(Color.BLACK);
            graphics.fillRect(0, 0, KEY_PIXELS, KEY_PIXELS);
            // Equivalent to the reference driver's counterclockwise rotation then vertical flip.
            graphics.rotate(Math.PI / 2);
            graphics.scale(1, -1);
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return rotated;
    }

}
