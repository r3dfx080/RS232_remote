package org.foxprod.rs232_remote;

import javafx.application.Platform;
import org.hid4java.HidDevice;
import org.hid4java.HidServices;
import org.hid4java.HidServicesSpecification;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Drives one Stream Deck Mini directly over USB HID.
 *
 * <p>A single daemon worker owns the device: it discovers the deck, reads key
 * reports with a short timeout, uploads the six faces of the current page and
 * writes nothing on the JavaFX thread. Key presses are reported to
 * {@code onAction} as indices {@code 0-4} on page one and {@code 5-9} on page
 * two; the sixth key only switches pages and never produces an action.
 *
 * <p>Only a new press is dispatched - a held key does not repeat, and a key that
 * is already down when the deck connects is treated as already held. Every
 * connection has a generation number, so a callback queued just before a
 * disconnect cannot fire after the deck comes back.
 *
 * <p>Absent, busy or failing hardware never stops the worker or the desktop
 * remote: discovery is retried once a second and failures are logged as
 * transitions rather than repeatedly.
 */
final class StreamDeckMini implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(StreamDeckMini.class.getName());

    static final int VENDOR_ID = 0x0FD9;
    static final int[] PRODUCT_IDS = {0x0063, 0x0090, 0x00B3, 0x00B8};

    static final int KEY_COUNT = StreamDeckMiniProtocol.KEY_COUNT;
    static final int PAGE_KEY = 5;
    static final int ACTION_COUNT = 10;

    /** Matches Elgato's recommended polling period. */
    static final int POLL_TIMEOUT_MS = 50;
    static final int REDISCOVERY_DELAY_MS = 1000;

    /** Input reports are a report ID plus 64 state bytes. */
    private static final int REPORT_BUFFER_BYTES = 1 + 64;

    private static final byte LOGO_REPORT_ID = 0x0B;
    private static final byte LOGO_COMMAND = 0x63;

    private final IntConsumer onAction;
    private final Executor callbacks;
    private final Supplier<HidServices> serviceFactory;

    /** {@code [page][key][report]} - the complete output reports for one key face. */
    private final List<List<List<byte[]>>> pageReports;

    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    private final byte[] readBuffer = new byte[REPORT_BUFFER_BYTES];
    private final boolean[] held = new boolean[KEY_COUNT];

    private HidServices services;
    private HidDevice device;
    private volatile boolean running = true;
    private volatile int generation;
    private int page;
    private boolean seeded;
    private boolean reportedUnavailable;
    private Thread worker;

    /**
     * Production constructor.
     *
     * @param onAction receives action indices 0-9; it is called on the JavaFX thread
     * @param images   the twelve encoded key images, page one first
     */
    StreamDeckMini(IntConsumer onAction, List<byte[]> images) {
        this(onAction, Platform::runLater, StreamDeckMini::createServices, images);
    }

    /**
     * Test constructor: supplies the HID services and the callback executor.
     */
    StreamDeckMini(IntConsumer onAction, HidServices services, Executor callbacks, List<byte[]> images) {
        this(onAction, callbacks, () -> services, images);
    }

    private StreamDeckMini(IntConsumer onAction, Executor callbacks,
                           Supplier<HidServices> serviceFactory, List<byte[]> images) {
        this.onAction = onAction;
        this.callbacks = callbacks;
        this.serviceFactory = serviceFactory;
        this.pageReports = toPageReports(images);
    }

    /** Validates the twelve faces and turns them into complete report sequences. */
    private static List<List<List<byte[]>>> toPageReports(List<byte[]> images) {
        if (images == null || images.size() != 2 * KEY_COUNT) {
            throw new IllegalArgumentException("Stream Deck needs exactly " + (2 * KEY_COUNT)
                    + " key images but got " + (images == null ? "none" : images.size()) + ".");
        }
        List<List<List<byte[]>>> pages = new ArrayList<>(2);
        for (int page = 0; page < 2; page++) {
            List<List<byte[]>> keys = new ArrayList<>(KEY_COUNT);
            for (int key = 0; key < KEY_COUNT; key++) {
                byte[] image = images.get(page * KEY_COUNT + key);
                if (image == null || image.length == 0) {
                    throw new IllegalArgumentException("Stream Deck key image " + (page * KEY_COUNT + key)
                            + " is missing.");
                }
                keys.add(StreamDeckMiniProtocol.imageReports(key, image));
            }
            pages.add(keys);
        }
        return pages;
    }

    /** Starts the worker. Calling this more than once has no effect. */
    synchronized void start() {
        if (closed.get() || !started.compareAndSet(false, true)) {
            return;
        }
        worker = new Thread(this::run, "stream-deck-mini");
        worker.setDaemon(true);
        worker.start();
    }

    private void run() {
        try {
            services = serviceFactory.get();
        } catch (Throwable failure) {
            // A missing native library must not take the desktop remote with it.
            LOG.log(Level.WARNING, "Stream Deck HID unavailable; keys are disabled.", failure);
            return;
        }

        try {
            while (running) {
                if (device == null && !connect()) {
                    pause(REDISCOVERY_DELAY_MS);
                    continue;
                }
                pollOnce();
            }
        } catch (Throwable failure) {
            LOG.log(Level.WARNING, "Stream Deck worker stopped unexpectedly.", failure);
        } finally {
            releaseDevice();
            if (services != null) {
                try {
                    services.shutdown();
                } catch (Throwable failure) {
                    LOG.log(Level.FINE, "Stream Deck HID shutdown failed.", failure);
                }
            }
        }
    }

    /** One read; {@code >0} is a report, {@code 0} a timeout, {@code <0} a disconnect. */
    private void pollOnce() {
        HidDevice current = device;
        if (current == null) {
            return;
        }

        int read;
        try {
            read = current.read(readBuffer, POLL_TIMEOUT_MS);
        } catch (RuntimeException failure) {
            disconnect();
            return;
        }

        if (read < 0) {
            disconnect();
            return;
        }
        if (read == 0) {
            // Timeout: the buffer still holds the previous report, so it must not be decoded.
            return;
        }

        boolean[] keys = StreamDeckMiniProtocol.decodeKeys(readBuffer, read);
        if (keys == null) {
            // Malformed reports leave the held state untouched.
            return;
        }
        if (!seeded) {
            // A key that is already down at connect time must not fire.
            System.arraycopy(keys, 0, held, 0, KEY_COUNT);
            seeded = true;
            return;
        }
        handle(keys);
    }

    private void handle(boolean[] keys) {
        if (keys[PAGE_KEY] && !held[PAGE_KEY]) {
            // Navigation wins: other keys rising in the same report are suppressed,
            // and anything still held stays held on the new page until released.
            System.arraycopy(keys, 0, held, 0, KEY_COUNT);
            page = 1 - page;
            uploadPage(page);
            return;
        }

        int pageOffset = page * 5;
        for (int key = 0; key < PAGE_KEY; key++) {
            if (keys[key] && !held[key]) {
                dispatch(pageOffset + key);
            }
        }
        System.arraycopy(keys, 0, held, 0, KEY_COUNT);
    }

    /** Queues an action, discarding it if the deck was reconnected in the meantime. */
    private void dispatch(int action) {
        int expectedGeneration = generation;
        callbacks.execute(() -> {
            if (generation == expectedGeneration && running) {
                onAction.accept(action);
            }
        });
    }

    private boolean connect() {
        HidServices active = services;
        if (active == null) {
            return false;
        }

        for (int productId : PRODUCT_IDS) {
            if (!running) {
                return false;
            }
            HidDevice found;
            try {
                found = active.getHidDevice(VENDOR_ID, productId, null);
            } catch (Throwable failure) {
                reportUnavailable(failure);
                return false;
            }
            if (found == null) {
                continue;
            }

            boolean opened;
            try {
                opened = found.open();
            } catch (Throwable failure) {
                reportUnavailable(failure);
                closeQuietly(found);
                return false;
            }
            if (!opened) {
                closeQuietly(found);
                continue;
            }

            if (!running) {
                closeQuietly(found);
                return false;
            }
            device = found;
            generation++;
            page = 0;
            seeded = false;
            Arrays.fill(held, false);
            reportedUnavailable = false;
            uploadPage(0);
            return device != null;
        }
        return false;
    }

    private void reportUnavailable(Throwable failure) {
        if (!reportedUnavailable) {
            reportedUnavailable = true;
            LOG.log(Level.WARNING, "Stream Deck Mini could not be opened; retrying.", failure);
        }
    }

    private void uploadPage(int pageIndex) {
        for (List<byte[]> keyReports : pageReports.get(pageIndex)) {
            for (byte[] report : keyReports) {
                if (!running || !write(report)) {
                    return; // stop this transfer; the device will be reconnected
                }
            }
        }
    }

    /**
     * Sends one complete wire report. hid4java prepends the report ID itself, so
     * the ID is passed separately and never appears twice on the wire.
     */
    private boolean write(byte[] wireReport) {
        HidDevice current = device;
        if (current == null) {
            return false;
        }

        byte reportId = wireReport[0];
        byte[] payload = Arrays.copyOfRange(wireReport, 1, wireReport.length);
        int written;
        try {
            written = current.write(payload, payload.length, reportId);
        } catch (RuntimeException failure) {
            disconnect();
            return false;
        }

        if (written < 0) {
            disconnect();
            return false;
        }
        return true;
    }

    private void disconnect() {
        HidDevice current = device;
        device = null;
        generation++;
        page = 0;
        seeded = false;
        Arrays.fill(held, false);
        closeQuietly(current);
    }

    /** Restores the logo, then closes the device. */
    private void releaseDevice() {
        HidDevice current = device;
        device = null;
        if (current == null) {
            return;
        }
        restoreLogo(current);
        closeQuietly(current);
    }

    private void restoreLogo(HidDevice current) {
        try {
            byte[] payload = new byte[16];
            payload[0] = LOGO_COMMAND;
            current.sendFeatureReport(payload, LOGO_REPORT_ID);
        } catch (Throwable ignored) {
            // Restoring the logo is cosmetic; never let it block cleanup.
        }
    }

    private void closeQuietly(HidDevice current) {
        if (current == null) {
            return;
        }
        try {
            current.close();
        } catch (Throwable ignored) {
            // Already gone, or the native handle died with the device.
        }
    }

    private void pause(long millis) {
        if (!running) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Stops discovery, releases the device and shuts down the HID services.
     * If a native call outlasts the exit wait, the worker cleans up when it returns.
     * Calling this more than once has no effect.
     */
    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        running = false;

        Thread current = worker;
        if (current != null && current != Thread.currentThread()) {
            current.interrupt();
            try {
                current.join(REDISCOVERY_DELAY_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static HidServices createServices() {
        HidServicesSpecification specification = new HidServicesSpecification();
        // The worker reads the device itself; automatic reads would compete for it.
        specification.setAutoDataRead(false);
        specification.setAutoStart(true);
        return new HidServices(specification);
    }
}
