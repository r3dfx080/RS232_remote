package org.foxprod.rs232_remote;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Layout tests for the twelve key faces. Everything here is deterministic because
 * the label font is bundled with the application.
 */
class StreamDeckFacesTest {

    private static final int SIZE = StreamDeckMiniProtocol.KEY_PIXELS;

    @Test
    void buildsTwelveEightyPixelFaces() throws IOException {
        List<BufferedImage> faces = StreamDeckFaces.build(config("REW SCAN", "SH <<", "SH >>", "FF SCAN", "RESET"));

        assertEquals(StreamDeckFaces.FACE_COUNT, faces.size());
        assertEquals(12, faces.size());
        for (int i = 0; i < faces.size(); i++) {
            assertEquals(SIZE, faces.get(i).getWidth(), "face " + i + " width");
            assertEquals(SIZE, faces.get(i).getHeight(), "face " + i + " height");
        }
    }

    @Test
    void pageOneIconsAreNormalisedToWhiteOnBlack() throws IOException {
        List<BufferedImage> faces = StreamDeckFaces.build(Map.of());

        // The desktop artwork mixes white glyphs (eject, stop) with black ones
        // (rew, play, ff); every key face must still show a visible white glyph.
        for (int i = 0; i < 5; i++) {
            int ink = whitePixels(faces.get(i));
            assertTrue(ink > 100, "face " + i + " must draw a white glyph");
            assertTrue(ink < SIZE * SIZE, "face " + i + " must not be a solid white tile");
        }
    }

    @Test
    void customButtonNamesComeFromConfig() throws IOException {
        BufferedImage first = StreamDeckFaces.build(config("REW SCAN", "", "", "", "")).get(6);
        BufferedImage second = StreamDeckFaces.build(config("FF SCAN", "", "", "", "")).get(6);

        assertNotEquals(pixelsOf(first), pixelsOf(second),
                "a different configured name must produce a different face");
    }

    @Test
    void missingNamesFallBackToTheButtonNumber() {
        Map<String, String> partial = new HashMap<>();
        partial.put("button1_name", "  ");
        partial.put("button2_name", "SH <<");

        assertEquals("BTN 1", StreamDeckFaces.customName(partial, 1), "blank name");
        assertEquals("SH <<", StreamDeckFaces.customName(partial, 2), "configured name");
        assertEquals("BTN 5", StreamDeckFaces.customName(partial, 5), "absent name");
        assertEquals("BTN 3", StreamDeckFaces.customName(null, 3), "no configuration at all");
    }

    @Test
    void longLabelsWrapInsteadOfOverflowing() {
        for (String label : List.of("REW SCAN", "SH >>", "FF SCAN", "PAGE 2", "BACK", "BTN 1")) {
            BufferedImage face = StreamDeckFaces.textFace(label);
            assertTrue(whitePixels(face) > 20, "\"" + label + "\" must render visible ink");
            assertEquals(0, nonBlackPixels(face, 4), "\"" + label + "\" must stay inside its margins");
        }
    }

    @Test
    void unwrappableWordIsShortenedRatherThanClippedAtTheEdge() {
        BufferedImage face = StreamDeckFaces.textFace("SUPERCALIFRAGILISTIC");

        assertTrue(whitePixels(face) > 20, "a long single word must still draw something");
        assertEquals(0, nonBlackPixels(face, 4), "the shortened word must stay inside its margins");
    }

    @Test
    void emptyLabelStillProducesAFace() {
        BufferedImage face = StreamDeckFaces.textFace("   ");

        assertEquals(SIZE, face.getWidth());
        assertTrue(whitePixels(face) > 0, "an empty label falls back to a placeholder glyph");
    }

    @Test
    void unknownIconIsReported() {
        assertThrows(IOException.class, () -> StreamDeckFaces.iconFace("no-such-icon"));
    }

    private static Map<String, String> config(String b1, String b2, String b3, String b4, String b5) {
        Map<String, String> config = new HashMap<>();
        config.put("button1_name", b1);
        config.put("button2_name", b2);
        config.put("button3_name", b3);
        config.put("button4_name", b4);
        config.put("button5_name", b5);
        return config;
    }

    private static int whitePixels(BufferedImage face) {
        int count = 0;
        for (int y = 0; y < face.getHeight(); y++) {
            for (int x = 0; x < face.getWidth(); x++) {
                if (luminance(face, x, y) > 200) {
                    count++;
                }
            }
        }
        return count;
    }

    /** Counts lit pixels inside a border of the given thickness. */
    private static int nonBlackPixels(BufferedImage face, int border) {
        int count = 0;
        for (int y = 0; y < face.getHeight(); y++) {
            for (int x = 0; x < face.getWidth(); x++) {
                boolean inBorder = x < border || y < border
                        || x >= face.getWidth() - border || y >= face.getHeight() - border;
                if (inBorder && luminance(face, x, y) > 20) {
                    count++;
                }
            }
        }
        return count;
    }

    private static int luminance(BufferedImage image, int x, int y) {
        int rgb = image.getRGB(x, y);
        int alpha = (rgb >>> 24) & 0xFF;
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        // Text faces are opaque; icon faces keep alpha, so weight by coverage.
        return ((red + green + blue) / 3) * alpha / 255;
    }

    private static String pixelsOf(BufferedImage face) {
        StringBuilder signature = new StringBuilder();
        for (int y = 0; y < face.getHeight(); y += 4) {
            for (int x = 0; x < face.getWidth(); x += 4) {
                signature.append(luminance(face, x, y) > 128 ? '#' : '.');
            }
            signature.append('\n');
        }
        return signature.toString();
    }
}
