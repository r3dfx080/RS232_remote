package org.foxprod.rs232_remote;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-format tests for the Stream Deck Mini protocol.
 *
 * <p>The expected bytes are taken from Elgato's Mini HID reference (report IDs,
 * 1024-byte image reports with a 16-byte header, 1-based output key index) and the
 * python-elgato-streamdeck Mini driver (chunk index at offset 0x02, show-image flag
 * at offset 0x04 set only on the final chunk).
 */
class StreamDeckMiniProtocolTest {

    private static final int REPORT_LENGTH = 1024;
    private static final int HEADER_LENGTH = 16;
    private static final int PAYLOAD_LENGTH = REPORT_LENGTH - HEADER_LENGTH;

    @Test
    void decodeKeysRejectsInvalidReports() {
        assertNull(StreamDeckMiniProtocol.decodeKeys(null, 0), "null report");

        for (int length = 0; length < 7; length++) {
            assertNull(StreamDeckMiniProtocol.decodeKeys(new byte[16], length), "length " + length);
        }

        byte[] wrongId = {0x02, 1, 1, 0, 1, 0, 0};
        assertNull(StreamDeckMiniProtocol.decodeKeys(wrongId, wrongId.length), "wrong report ID");

        byte[] badState = {0x01, 1, 1, 0, 1, 0, 2};
        assertNull(StreamDeckMiniProtocol.decodeKeys(badState, badState.length), "state outside 0/1");

        byte[] truncated = {0x01, 1, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0};
        assertNull(StreamDeckMiniProtocol.decodeKeys(truncated, 14), "length beyond the report");
    }

    @Test
    void decodeKeysIgnoresBytesBeyondLength() {
        byte[] report = {0x01, 1, 1, 0, 1, 0, 0, 9, 9, 9};

        boolean[] keys = StreamDeckMiniProtocol.decodeKeys(report, 7);

        assertNotNull(keys);
        assertArrayEquals(new boolean[]{true, true, false, true, false, false}, keys,
                "trailing bytes must not be read as key states");
    }

    @Test
    void decodeKeysPreservesSixStates() {
        // Report ID 0x01 followed by one state byte per key, as the device sends it.
        byte[] report = {0x01, 1, 0, 1, 0, 0, 1};

        boolean[] keys = StreamDeckMiniProtocol.decodeKeys(report, report.length);

        assertNotNull(keys);
        assertArrayEquals(new boolean[]{true, false, true, false, false, true}, keys);
    }

    @Test
    void imageReportsHaveExactFraming() {
        byte[] bmp = new byte[PAYLOAD_LENGTH + 1];
        for (int i = 0; i < bmp.length; i++) {
            bmp[i] = (byte) (i * 31 + 7);
        }

        List<byte[]> reports = StreamDeckMiniProtocol.imageReports(0, bmp);

        assertEquals(2, reports.size());
        assertEquals(REPORT_LENGTH, reports.get(0).length);
        assertEquals(REPORT_LENGTH, reports.get(1).length);

        assertArrayEquals(new byte[]{2, 1, 0, 0, 0, 1}, headOf(reports.get(0)),
                "first chunk: chunk 0, show flag 0, key 0 reported as 1");
        assertArrayEquals(new byte[]{2, 1, 1, 0, 1, 1}, headOf(reports.get(1)),
                "final chunk: chunk 1, show flag 1, key index unchanged");

        assertEquals(bmp[0], reports.get(0)[HEADER_LENGTH], "chunk data starts at byte 16");

        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        joined.write(reports.get(0), HEADER_LENGTH, PAYLOAD_LENGTH);
        joined.write(reports.get(1), HEADER_LENGTH, 1);
        assertArrayEquals(bmp, joined.toByteArray(), "payloads concatenate back to the source bytes");

        for (int i = HEADER_LENGTH + 1; i < REPORT_LENGTH; i++) {
            assertEquals(0, reports.get(1)[i], "final chunk is zero padded at " + i);
        }
        for (int i = 6; i < HEADER_LENGTH; i++) {
            assertEquals(0, reports.get(0)[i], "header padding at " + i);
        }
    }

    @Test
    void imageReportsUseOneBasedKeyIndex() {
        List<byte[]> reports = StreamDeckMiniProtocol.imageReports(5, new byte[10]);

        assertEquals(1, reports.size());
        assertEquals(6, reports.get(0)[5], "key 5 is sent as index 6");
        assertEquals(1, reports.get(0)[4], "single chunk is also the final chunk");
    }

    @Test
    void imageReportsRejectUnknownKeys() {
        assertThrows(IllegalArgumentException.class, () -> StreamDeckMiniProtocol.imageReports(-1, new byte[10]));
        assertThrows(IllegalArgumentException.class, () -> StreamDeckMiniProtocol.imageReports(6, new byte[10]));
    }

    @Test
    void encodesPreparedArtwork() throws IOException {
        BufferedImage artwork = new BufferedImage(80, 80, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = artwork.createGraphics();
        try {
            g.setColor(new Color(0, 0, 0, 0));
            g.fillRect(0, 0, 80, 80);
            g.setColor(new Color(255, 0, 0, 255));
            g.fillRect(0, 0, 10, 10);
        } finally {
            g.dispose();
        }

        byte[] bmp = StreamDeckMiniProtocol.encodeImage(toStream(artwork));

        assertNotNull(bmp);
        assertEquals('B', bmp[0] & 0xFF, "BMP signature");
        assertEquals('M', bmp[1] & 0xFF, "BMP signature");
        assertEquals(54 + 80 * 80 * 3, bmp.length, "80x80 24-bit BMP with no row padding");

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(bmp));
        assertNotNull(decoded, "encoded bytes must be a readable BMP");
        assertEquals(80, decoded.getWidth());
        assertEquals(80, decoded.getHeight());

        int red = 0x00FF0000;
        int black = 0x00000000;
        assertEquals(red, decoded.getRGB(79, 0) & 0x00FFFFFF, "source top-left marker rotates clockwise to the top-right");
        assertEquals(red, decoded.getRGB(79, 9) & 0x00FFFFFF);
        assertEquals(red, decoded.getRGB(70, 0) & 0x00FFFFFF);
        assertEquals(black, decoded.getRGB(0, 0) & 0x00FFFFFF, "transparent artwork is composited onto black");
        assertEquals(black, decoded.getRGB(79, 79) & 0x00FFFFFF, "orientation marker must not survive at the bottom-right");
    }

    @Test
    void rejectsInvalidArtwork() {
        assertThrows(IOException.class, () -> StreamDeckMiniProtocol.encodeImage((InputStream) null));

        assertThrows(IOException.class,
                () -> StreamDeckMiniProtocol.encodeImage(new ByteArrayInputStream(new byte[]{1, 2, 3})));

        BufferedImage wrongSize = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
        IOException error = assertThrows(IOException.class,
                () -> StreamDeckMiniProtocol.encodeImage(toStream(wrongSize)));
        assertTrue(error.getMessage().contains("512x512"), "message must name the actual size: " + error.getMessage());
        assertTrue(error.getMessage().contains("80x80"), "message must name the required size: " + error.getMessage());
    }

    private static byte[] headOf(byte[] report) {
        return Arrays.copyOfRange(report, 0, 6);
    }

    private static InputStream toStream(BufferedImage image) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return new ByteArrayInputStream(bytes.toByteArray());
    }
}
