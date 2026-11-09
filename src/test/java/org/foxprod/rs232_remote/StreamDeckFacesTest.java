package org.foxprod.rs232_remote;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Layout and ordering tests for the twelve key faces. Page-1 artwork is injected,
 * so these tests neither need image files nor depend on the artwork that ships.
 */
class StreamDeckFacesTest {

    private static final int SIZE = StreamDeckMiniProtocol.KEY_PIXELS;

    @Test
    void buildsTwelveFacesInDisplayOrder() throws IOException {
        List<BufferedImage> faces = StreamDeckFaces.build(config(), StreamDeckFacesTest::fixture);

        assertEquals(12, faces.size());
        assertEquals(StreamDeckFaces.FACE_COUNT, faces.size());
        for (int i = 0; i < faces.size(); i++) {
            assertEquals(SIZE, faces.get(i).getWidth(), "face " + i + " width");
            assertEquals(SIZE, faces.get(i).getHeight(), "face " + i + " height");
        }

        // Page 1: eject, stop, page2, rew, play, ff. Back sits last, at page 2 bottom-right.
        List<String> artworkOrder = List.of("eject", "stop", "page2", "rew", "play", "ff");
        for (int i = 0; i < artworkOrder.size(); i++) {
            assertEquals(fixture(artworkOrder.get(i)).getRGB(0, 0), faces.get(i).getRGB(0, 0),
                    "face " + i + " must be the " + artworkOrder.get(i) + " artwork");
        }
        assertEquals(fixture("back").getRGB(0, 0), faces.get(11).getRGB(0, 0),
                "face 11 must be the back artwork");
    }

    @Test
    void artworkIsUsedWithoutTintingOrResizing() throws IOException {
        BufferedImage marked = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        marked.setRGB(3, 7, 0xFF3366CC);
        marked.setRGB(70, 12, 0x80FF00FF);

        List<BufferedImage> faces = StreamDeckFaces.build(config(),
                name -> "eject".equals(name) ? marked : fixture(name));

        assertEquals(0xFF3366CC, faces.get(0).getRGB(3, 7), "colours must survive untouched");
        assertEquals(0x80FF00FF, faces.get(0).getRGB(70, 12), "alpha must survive untouched");
    }

    @Test
    void customButtonNamesComeFromConfig() throws IOException {
        BufferedImage first = StreamDeckFaces.build(config("REW SCAN", "", "", "", ""), StreamDeckFacesTest::fixture).get(6);
        BufferedImage second = StreamDeckFaces.build(config("FF SCAN", "", "", "", ""), StreamDeckFacesTest::fixture).get(6);

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
    void missingArtworkNamesTheExpectedFile() {
        IOException error = assertThrows(IOException.class, () -> StreamDeckFaces.build(config(),
                name -> "ff".equals(name) ? null : fixture(name)));

        assertTrue(error.getMessage().contains("ff.png"), "message must name the file: " + error.getMessage());
    }

    @Test
    void wronglySizedArtworkIsRejectedRatherThanScaled() {
        IOException error = assertThrows(IOException.class, () -> StreamDeckFaces.build(config(),
                name -> new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB)));

        assertTrue(error.getMessage().contains("80x80"), "message must name the required size: " + error.getMessage());
        assertTrue(error.getMessage().contains("512x512"), "message must name the actual size: " + error.getMessage());
    }

    @Test
    void longLabelsWrapInsteadOfOverflowing() {
        for (String label : List.of("REW SCAN", "SH >>", "FF SCAN", "PAGE 2", "BACK", "BTN 1")) {
            BufferedImage face = StreamDeckFaces.textFace(label);
            assertTrue(whitePixels(face) > 20, "\"" + label + "\" must render visible ink");
            assertEquals(0, litPixelsInBorder(face, 4), "\"" + label + "\" must stay inside its margins");
        }
    }

    @Test
    void unwrappableWordIsShortenedRatherThanClippedAtTheEdge() {
        BufferedImage face = StreamDeckFaces.textFace("SUPERCALIFRAGILISTIC");

        assertTrue(whitePixels(face) > 20, "a long single word must still draw something");
        assertEquals(0, litPixelsInBorder(face, 4), "the shortened word must stay inside its margins");
    }

    @Test
    void emptyLabelStillProducesAFace() {
        BufferedImage face = StreamDeckFaces.textFace("   ");

        assertEquals(SIZE, face.getWidth());
        assertTrue(whitePixels(face) > 0, "an empty label falls back to a placeholder glyph");
    }

    private static Map<String, String> config(String... names) {
        Map<String, String> config = new HashMap<>();
        String[] defaults = {"REW SCAN", "SH <<", "SH >>", "FF SCAN", "RESET"};
        for (int i = 0; i < 5; i++) {
            String name = i < names.length ? names[i] : defaults[i];
            config.put("button" + (i + 1) + "_name", name);
        }
        return config;
    }

    private static Map<String, String> config() {
        return config(new String[0]);
    }

    /** A distinct flat colour per artwork name, so ordering is observable. */
    private static BufferedImage fixture(String name) {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Color colour = Color.getHSBColor((Math.abs(name.hashCode()) % 360) / 360f, 0.7f, 0.8f);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                image.setRGB(x, y, colour.getRGB());
            }
        }
        return image;
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
    private static int litPixelsInBorder(BufferedImage face, int border) {
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
