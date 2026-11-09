package org.foxprod.rs232_remote;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
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
 * Builds the twelve key faces shown on the Stream Deck Mini.
 *
 * <p>Page one reuses the desktop transport icons as alpha masks, tinted white on
 * a black face, so all six keys look alike regardless of the colour each icon was
 * drawn in. Page two labels the user's custom buttons with the names from
 * {@code config.properties}, laid out and sized to fit an 80x80 key; the
 * page-one navigation key and the page-two back key are labelled the same way.
 *
 * <p>Text uses a bundled font so the layout does not depend on the host's
 * installed fonts.
 */
final class StreamDeckFaces {

    static final int FACE_COUNT = 2 * StreamDeckMiniProtocol.KEY_COUNT;

    private static final String RESOURCE_ROOT = "/org/foxprod/rs232_remote/";
    private static final String FONT_RESOURCE = RESOURCE_ROOT + "streamdeck/DejaVuSans-Bold.ttf";

    /** Desktop artwork, reused as the five transport key faces. */
    private static final String[] TRANSPORT_ICONS = {"eject", "rew", "play", "ff", "stop"};

    private static final String PAGE_TWO_LABEL = "PAGE 2";
    private static final String BACK_LABEL = "BACK";

    private static final int SIZE = StreamDeckMiniProtocol.KEY_PIXELS;
    private static final int PADDING = 5;
    private static final int MAX_LINES = 3;
    private static final int MAX_FONT_SIZE = 24;
    private static final int MIN_FONT_SIZE = 10;

    private static final Color INK = Color.WHITE;
    private static final Color BACKDROP = Color.BLACK;

    private static Font baseFont;

    private StreamDeckFaces() {
    }

    /**
     * Builds every key face in display order: page one (eject, rewind, play,
     * fast-forward, stop, page two) followed by page two (custom 1-5, back).
     *
     * @param config the loaded configuration, used for the custom button names;
     *               may be {@code null}
     */
    static List<BufferedImage> build(Map<String, String> config) throws IOException {
        List<BufferedImage> faces = new ArrayList<>(FACE_COUNT);
        for (String icon : TRANSPORT_ICONS) {
            faces.add(iconFace(icon));
        }
        faces.add(textFace(PAGE_TWO_LABEL));
        for (int button = 1; button <= 5; button++) {
            faces.add(textFace(customName(config, button)));
        }
        faces.add(textFace(BACK_LABEL));
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

    /** Loads a desktop icon and renders it as a white glyph on black. */
    static BufferedImage iconFace(String name) throws IOException {
        BufferedImage source = ImageIO.read(open(RESOURCE_ROOT + name + ".png"));
        if (source == null) {
            throw new IOException("Stream Deck icon is unreadable: " + name + ".png");
        }
        return tintWhite(downScale(source));
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

    /** Progressive halving keeps glyph edges clean when 512 px artwork becomes 80 px. */
    private static BufferedImage downScale(BufferedImage source) {
        BufferedImage current = source;
        int width = source.getWidth();
        int height = source.getHeight();
        while (width > SIZE * 2) {
            width = Math.max(SIZE, width / 2);
            height = Math.max(SIZE, height / 2);
            current = drawScaled(current, width, height);
        }
        return drawScaled(current, SIZE, SIZE);
    }

    private static BufferedImage drawScaled(BufferedImage source, int width, int height) {
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    /** Replaces the artwork's colour with white, keeping its shape from the alpha channel. */
    private static BufferedImage tintWhite(BufferedImage source) {
        BufferedImage tinted = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int alpha = source.getRGB(x, y) >>> 24;
                tinted.setRGB(x, y, (alpha << 24) | 0x00FFFFFF);
            }
        }
        return tinted;
    }

    private static synchronized Font font(int size) {
        if (baseFont == null) {
            try (InputStream stream = open(FONT_RESOURCE)) {
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

    private static InputStream open(String resource) throws IOException {
        InputStream stream = StreamDeckFaces.class.getResourceAsStream(resource);
        if (stream == null) {
            throw new IOException("Missing classpath resource: " + resource);
        }
        return stream;
    }

    /** A font size and the lines that fit inside a key. */
    private record Fitted(Font font, List<String> lines) {
    }
}
