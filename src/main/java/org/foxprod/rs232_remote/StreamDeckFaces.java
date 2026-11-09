package org.foxprod.rs232_remote;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds the twelve key faces shown on the Stream Deck Mini, in display order.
 *
 * <p>Seven faces come from prepared artwork under {@code streamdeck/}: the five
 * transport keys, the page-2 navigation key and the page-2 back key. Artwork is
 * used exactly as supplied - it must already be an upright 80x80 image, and its
 * colours are left untouched. Transparent pixels reach the device as black.
 *
 * <p>The five custom keys are labelled with the user's configured button titles
 * ({@code button1_name} ... {@code button5_name}), because those names are not
 * known in advance. The bundled font keeps that layout identical on every
 * machine.
 */
final class StreamDeckFaces {

    static final int FACE_COUNT = 2 * StreamDeckMiniProtocol.KEY_COUNT;

    static final String RESOURCE_ROOT = "/org/foxprod/rs232_remote/streamdeck/";

    /** Artwork filenames, in the order the keys appear on page 1, followed by "back". */
    static final List<String> ARTWORK = List.of("eject", "rew", "play", "ff", "stop", "page2", "back");

    private static final int SIZE = StreamDeckMiniProtocol.KEY_PIXELS;
    private static final int PADDING = 5;
    private static final int MAX_LINES = 3;
    private static final int MAX_FONT_SIZE = 24;
    private static final int MIN_FONT_SIZE = 10;

    private static final Color INK = Color.WHITE;
    private static final Color BACKDROP = Color.BLACK;
    private static final String FONT_RESOURCE = RESOURCE_ROOT + "DejaVuSans-Bold.ttf";

    private static Font baseFont;

    private StreamDeckFaces() {
    }

    /** Supplies one piece of prepared artwork by key name, without its extension. */
    @FunctionalInterface
    interface Artwork {
        /** @return the image, or {@code null} when the artwork is absent */
        BufferedImage load(String name) throws IOException;
    }

    /** Builds every face from the bundled artwork. */
    static List<BufferedImage> build(Map<String, String> config) throws IOException {
        return build(config, StreamDeckFaces::bundled);
    }

    /**
     * Builds every face, taking page-1 artwork from the supplied source.
     *
     * @param config   the loaded configuration, used for the custom button names
     * @param artwork  the page-1 artwork source; lets tests run without image files
     */
    static List<BufferedImage> build(Map<String, String> config, Artwork artwork) throws IOException {
        List<BufferedImage> faces = new ArrayList<>(FACE_COUNT);
        for (String name : ARTWORK.subList(0, 6)) {
            faces.add(artworkFace(artwork, name));
        }
        for (int button = 1; button <= 5; button++) {
            faces.add(textFace(customName(config, button)));
        }
        faces.add(artworkFace(artwork, "back"));
        return faces;
    }

    /** The configured title of a custom button, or a stable fallback when unset. */
    static String customName(Map<String, String> config, int button) {
        String name = config == null ? null : config.get("button" + button + "_name");
        if (name == null || name.isBlank()) {
            return "BTN " + button;
        }
        return name.trim();
    }

    /**
     * Loads one prepared key image. The image is returned untouched: no scaling,
     * no tinting, no cropping. Incorrect dimensions are rejected rather than
     * silently corrected, so a wrong export cannot ship unnoticed.
     */
    private static BufferedImage artworkFace(Artwork artwork, String name) throws IOException {
        BufferedImage image = artwork.load(name);
        if (image == null) {
            throw new IOException("Missing Stream Deck key artwork: " + RESOURCE_ROOT + name + ".png");
        }
        if (image.getWidth() != SIZE || image.getHeight() != SIZE) {
            throw new IOException("Stream Deck key artwork " + name + ".png must be "
                    + SIZE + "x" + SIZE + " pixels, but was "
                    + image.getWidth() + "x" + image.getHeight() + ".");
        }
        return image;
    }

    /** Reads artwork from the application's own resources. */
    private static BufferedImage bundled(String name) throws IOException {
        String resource = RESOURCE_ROOT + name + ".png";
        try (InputStream stream = StreamDeckFaces.class.getResourceAsStream(resource)) {
            return stream == null ? null : ImageIO.read(stream);
        }
    }

    /** Renders a label centred on a black key face, wrapped and sized to fit. */
    static BufferedImage textFace(String label) {
        String text = label == null || label.isBlank() ? "?" : label.trim();
        BufferedImage face = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);

        Graphics2D graphics = face.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setColor(BACKDROP);
            graphics.fillRect(0, 0, SIZE, SIZE);

            Fitted fitted = fit(text);
            FontMetrics metrics = graphics.getFontMetrics(fitted.font);
            int lineHeight = metrics.getHeight();
            int baseline = (SIZE - lineHeight * fitted.lines.size()) / 2 + metrics.getAscent();

            graphics.setFont(fitted.font);
            graphics.setColor(INK);
            for (String line : fitted.lines) {
                graphics.drawString(line, (SIZE - metrics.stringWidth(line)) / 2, baseline);
                baseline += lineHeight;
            }
        } finally {
            graphics.dispose();
        }
        return face;
    }

    /** Chooses the largest font size whose wrapped label still fits the key. */
    private static Fitted fit(String text) {
        int maxWidth = SIZE - 2 * PADDING;
        int maxHeight = SIZE - 2 * PADDING;

        for (int size = MAX_FONT_SIZE; size >= MIN_FONT_SIZE; size -= 2) {
            Font font = font(size);
            FontMetrics metrics = metrics(font);
            List<String> lines = wrap(text, metrics, maxWidth);
            if (lines != null && lines.size() <= MAX_LINES
                    && metrics.getHeight() * lines.size() <= maxHeight) {
                return new Fitted(font, lines);
            }
        }

        Font font = font(MIN_FONT_SIZE);
        FontMetrics metrics = metrics(font);
        String shortened = truncate(text, metrics, maxWidth);
        List<String> lines = wrap(shortened, metrics, maxWidth);
        if (lines == null) {
            lines = List.of(shortened);
        }
        if (lines.size() > MAX_LINES) {
            lines = lines.subList(0, MAX_LINES);
        }
        return new Fitted(font, List.copyOf(lines));
    }

    /** Greedy word wrap; returns null when a single word cannot fit the width. */
    private static List<String> wrap(String text, FontMetrics metrics, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            if (metrics.stringWidth(word) > maxWidth) {
                return null;
            }
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (metrics.stringWidth(candidate) <= maxWidth) {
                line.setLength(0);
                line.append(candidate);
            } else {
                lines.add(line.toString());
                line.setLength(0);
                line.append(word);
            }
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }

    private static String truncate(String text, FontMetrics metrics, int maxWidth) {
        if (metrics.stringWidth(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "\u2026";
        int end = text.length();
        while (end > 0 && metrics.stringWidth(text.substring(0, end) + ellipsis) > maxWidth) {
            end--;
        }
        return end == 0 ? ellipsis : text.substring(0, end).trim() + ellipsis;
    }

    private static synchronized Font font(int size) {
        if (baseFont == null) {
            try (InputStream stream = StreamDeckFaces.class.getResourceAsStream(FONT_RESOURCE)) {
                if (stream == null) {
                    throw new IOException("Missing classpath resource: " + FONT_RESOURCE);
                }
                baseFont = Font.createFont(Font.TRUETYPE_FONT, stream);
            } catch (IOException | FontFormatException e) {
                throw new IllegalStateException("Could not load the bundled key face font " + FONT_RESOURCE, e);
            }
        }
        return baseFont.deriveFont((float) size);
    }

    private static FontMetrics metrics(Font font) {
        BufferedImage scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = scratch.createGraphics();
        try {
            return graphics.getFontMetrics(font);
        } finally {
            graphics.dispose();
        }
    }

    /** A font size and the lines that fit inside a key. */
    private record Fitted(Font font, List<String> lines) {
    }
}
