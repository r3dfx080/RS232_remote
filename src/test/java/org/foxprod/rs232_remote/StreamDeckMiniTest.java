package org.foxprod.rs232_remote;

import org.hid4java.HidDevice;
import org.hid4java.HidServices;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyByte;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behaviour tests for the HID service. The device is mocked and fed a scripted
 * sequence of reads, so page switching, edge detection and reconnection are all
 * exercised without hardware.
 */
class StreamDeckMiniTest {

    private static final int WRITES_PER_PAGE = 6 * 20; // six 80x80 BMPs, 20 reports each
    private static final int REPORT_ID = 0x01;

    @Test
    void mapsBothPages() throws Exception {
        Deck deck = new Deck();
        try {
            StreamDeckMini service = deck.start();
            deck.awaitUploaded(WRITES_PER_PAGE);
            deck.seed();

            deck.press(0);
            deck.awaitActions(1);
            assertEquals(0, deck.actions.get(0), "page one key 0 is Eject");

            deck.release();
            deck.press(1);
            deck.awaitActions(2);
            assertEquals(1, deck.actions.get(1), "page one key 1 is Rewind");

            deck.release();
            int beforeSwitch = deck.writes.size();
            deck.press(StreamDeckMini.PAGE_KEY);
            deck.await(() -> deck.writes.size() > beforeSwitch, "page two upload");
            assertEquals(2, deck.actions.size(), "changing pages must not send a command");

            deck.release();
            deck.press(0);
            deck.awaitActions(3);
            assertEquals(5, deck.actions.get(2), "page two key 0 is Custom 1");

            deck.release();
            deck.press(4);
            deck.awaitActions(4);
            assertEquals(9, deck.actions.get(3), "page two key 4 is Custom 5");

            service.close();
        } finally {
            deck.close();
        }
    }

    @Test
    void heldKeysDoNotRepeat() throws Exception {
        Deck deck = new Deck();
        try {
            StreamDeckMini service = deck.start();
            deck.seed();
            deck.press(1);
            deck.awaitActions(1);

            deck.press(1); // identical report: still held, nothing may fire
            deck.press(2);
            deck.awaitActions(2);

            assertEquals(List.of(1, 2), List.copyOf(deck.actions));
            service.close();
        } finally {
            deck.close();
        }
    }

    @Test
    void navigationWinsSimultaneousPress() throws Exception {
        Deck deck = new Deck();
        try {
            StreamDeckMini service = deck.start();
            deck.awaitUploaded(WRITES_PER_PAGE);
            deck.seed();
            int beforeSwitch = deck.writes.size();

            deck.press(StreamDeckMini.PAGE_KEY, 0); // page key and key 0 rise together
            deck.await(() -> deck.writes.size() > beforeSwitch, "page two upload");
            assertEquals(0, deck.actions.size(), "a simultaneous transport press must be suppressed");

            deck.press(0);           // release navigation, but keep key 0 held
            deck.press(0);           // repeated held snapshot must not fire Custom 1
            deck.release();
            deck.press(0);
            deck.awaitActions(1);
            deck.awaitInput();
            assertEquals(List.of(5), List.copyOf(deck.actions), "the held key must not replay on page two");

            service.close();
        } finally {
            deck.close();
        }
    }

    @Test
    void rejectsInvalidImageList() throws Exception {
        HidServices services = mock(HidServices.class);
        List<byte[]> images = images();

        assertThrows(IllegalArgumentException.class,
                () -> new StreamDeckMini(action -> { }, services, Runnable::run, images.subList(0, 11)));
        assertThrows(IllegalArgumentException.class,
                () -> new StreamDeckMini(action -> { }, services, Runnable::run, null));

        List<byte[]> withHole = new ArrayList<>(images);
        withHole.set(4, null);
        assertThrows(IllegalArgumentException.class,
                () -> new StreamDeckMini(action -> { }, services, Runnable::run, withHole));
    }

    @Test
    void unusableHidLeavesTheApplicationRunning() throws Exception {
        HidServices services = mock(HidServices.class);
        when(services.getHidDevice(anyInt(), anyInt(), any()))
                .thenThrow(new UnsatisfiedLinkError("no native HID library"));

        List<Integer> actions = Collections.synchronizedList(new ArrayList<>());
        StreamDeckMini service = new StreamDeckMini(actions::add, services, Runnable::run, images());
        service.start(); // must not propagate the native failure

        Thread.sleep(150);
        assertTrue(actions.isEmpty(), "no key can fire without a device");
        service.close();
        verify(services, times(1)).shutdown();
    }

    @Test
    void timeoutDoesNotReplayBuffer() throws Exception {
        Deck deck = new Deck();
        try {
            StreamDeckMini service = deck.start();
            deck.seed();
            deck.press(1);
            deck.awaitActions(1);

            deck.timeouts(5); // the buffer still holds "key 1 down"
            deck.press(2);
            deck.awaitActions(2);

            assertEquals(List.of(1, 2), List.copyOf(deck.actions), "a timeout must not re-fire key 1");
            service.close();
        } finally {
            deck.close();
        }
    }

    @Test
    void malformedReportDoesNotChangeHeldState() throws Exception {
        Deck deck = new Deck();
        try {
            StreamDeckMini service = deck.start();
            deck.seed();
            deck.press(1);
            deck.awaitActions(1);

            deck.raw(new byte[]{0x07, 1, 0, 0, 0, 0, 0}); // wrong report ID
            deck.release();
            deck.press(1);
            deck.awaitActions(2);

            assertEquals(List.of(1, 1), List.copyOf(deck.actions),
                    "a malformed report must leave the key held, so the release is not a new press");
            service.close();
        } finally {
            deck.close();
        }
    }

    @Test
    void disconnectResetsPageAndHeldState() throws Exception {
        Deck deck = new Deck();
        try {
            StreamDeckMini service = deck.start();
            deck.awaitUploaded(WRITES_PER_PAGE);
            deck.seed();

            deck.press(StreamDeckMini.PAGE_KEY);
            deck.awaitUploaded(2 * WRITES_PER_PAGE);
            deck.release();

            deck.disconnected(); // the deck is unplugged
            deck.awaitUploaded(3 * WRITES_PER_PAGE); // reconnect uploads page one again
            deck.seed();

            deck.press(0);
            deck.awaitActions(1);
            assertEquals(0, deck.actions.get(0), "a reconnected deck starts on page one");

            service.close();
        } finally {
            deck.close();
        }
    }

    @Test
    void firstReportAfterConnectSeedsHeldKeysWithoutActions() throws Exception {
        Deck deck = new Deck();
        try {
            StreamDeckMini service = deck.start();
            deck.awaitUploaded(WRITES_PER_PAGE);

            deck.press(0);   // already down when the first report arrives
            deck.release();
            deck.press(0);
            deck.awaitActions(1);

            Thread.sleep(100);
            assertEquals(1, deck.actions.size(), "the key held at connect time must not fire");
            assertEquals(0, deck.actions.get(0));
            service.close();
        } finally {
            deck.close();
        }
    }

    @Test
    void failedWriteClosesAndRetries() throws Exception {
        Deck deck = new Deck();
        try {
            deck.failWrites = true;
            StreamDeckMini service = deck.start();

            deck.await(() -> deck.closes.get() >= 1, "failed upload closes the device");
            verify(deck.services, times(1)).getHidDevice(anyInt(), anyInt(), any());
            Thread.sleep(StreamDeckMini.REDISCOVERY_DELAY_MS / 4);
            verify(deck.services, times(1)).getHidDevice(anyInt(), anyInt(), any());

            deck.failWrites = false;
            deck.await(() -> deck.writes.size() > 0, "recovered on a later attempt");

            service.close();
        } finally {
            deck.close();
        }
    }

    @Test
    void staleCallbackAfterReconnectIsDiscarded() throws Exception {
        Deck deck = new Deck();
        List<Runnable> queued = Collections.synchronizedList(new ArrayList<>());
        Executor deferred = queued::add;

        try {
            StreamDeckMini service = new StreamDeckMini(deck.actions::add, deck.services, deferred, deck.images);
            service.start();
            deck.awaitUploaded(WRITES_PER_PAGE);
            deck.seed();

            deck.press(0);
            deck.await(() -> !queued.isEmpty(), "action queued for the UI");

            deck.disconnected();
            deck.awaitUploaded(2 * WRITES_PER_PAGE); // reconnect happened in between

            for (Runnable task : List.copyOf(queued)) {
                task.run();
            }
            assertTrue(deck.actions.isEmpty(), "a callback from the old connection must be discarded");

            service.close();
        } finally {
            deck.close();
        }
    }

    @Test
    void closeStopsDiscoveryAndClosesOnce() throws Exception {
        Deck deck = new Deck();
        try {
            StreamDeckMini service = deck.start();
            deck.awaitUploaded(WRITES_PER_PAGE);

            service.close();
            verify(deck.device, times(1)).close();
            verify(deck.services, times(1)).shutdown();

            int writesAfterClose = deck.writes.size();
            Thread.sleep(120);
            assertEquals(writesAfterClose, deck.writes.size(), "the worker must stop writing");

            service.close(); // idempotent
            verify(deck.device, times(1)).close();
            verify(deck.services, times(1)).shutdown();
        } finally {
            deck.close();
        }
    }

    @Test
    void closeDuringOpenLeavesCleanupToTheWorker() throws Exception {
        try (Deck deck = new Deck()) {
            CountDownLatch opening = new CountDownLatch(1);
            CountDownLatch resume = new CountDownLatch(1);
            when(deck.device.open()).thenAnswer(invocation -> {
                opening.countDown();
                // Native calls need not respond to Java interruption.
                boolean waiting = true;
                while (waiting) {
                    try {
                        resume.await();
                        waiting = false;
                    } catch (InterruptedException ignored) {
                    }
                }
                return true;
            });
            StreamDeckMini service = deck.start();
            try {
                assertTrue(opening.await(5, TimeUnit.SECONDS), "worker reached open");
                service.close();
                verify(deck.services, never()).shutdown();
                verify(deck.device, never()).close();
            } finally {
                resume.countDown();
            }
            verify(deck.services, timeout(5000).times(1)).shutdown();
            verify(deck.device, times(1)).close();
            assertTrue(deck.writes.isEmpty(), "no image may be uploaded after shutdown");
        }
    }

    @Test
    void wireReportsCarryTheReportIdExactlyOnce() throws Exception {
        Deck deck = new Deck();
        try {
            StreamDeckMini service = deck.start();
            deck.awaitUploaded(WRITES_PER_PAGE);

            byte[] expected = StreamDeckMiniProtocol.imageReports(0, deck.images.get(0)).get(0);
            assertArrayEquals(expected, deck.writes.get(0),
                    "the first upload must be the page-one eject face, report ID included once");

            service.close();
        } finally {
            deck.close();
        }
    }

    /** A scripted Stream Deck: mocked HID services plus a queue of read results. */
    private static final class Deck implements AutoCloseable {

        final HidDevice device = mock(HidDevice.class);
        final HidServices services = mock(HidServices.class);
        final List<Integer> actions = Collections.synchronizedList(new ArrayList<>());
        final List<byte[]> writes = Collections.synchronizedList(new ArrayList<>());
        final List<byte[]> images = images();
        final AtomicInteger closes = new AtomicInteger();

        private final BlockingQueue<Object> reads = new LinkedBlockingQueue<>();
        private final List<StreamDeckMini> started = new ArrayList<>();

        volatile boolean failWrites;

        Deck() {
            when(services.getHidDevice(anyInt(), anyInt(), any())).thenReturn(device);
            when(device.open()).thenReturn(true);
            doAnswer(invocation -> {
                closes.incrementAndGet();
                return null;
            }).when(device).close();
            when(device.read(any(byte[].class), anyInt())).thenAnswer(this::read);
            when(device.write(any(byte[].class), anyInt(), anyByte())).thenAnswer(this::write);
        }

        StreamDeckMini start() {
            StreamDeckMini service = new StreamDeckMini(actions::add, services, Runnable::run, images);
            started.add(service);
            service.start();
            return service;
        }

        /** Feed one report with the given keys pressed. */
        void press(int... keys) {
            byte[] report = new byte[7];
            report[0] = REPORT_ID;
            for (int key : keys) {
                report[key + 1] = 1;
            }
            reads.add(report);
        }

        /** Feed one report with every key released. */
        void release() {
            byte[] report = new byte[7];
            report[0] = REPORT_ID;
            reads.add(report);
        }

        /** A negative read means the deck was removed. */
        void disconnected() {
            reads.add(-1);
        }

        /** Feed one report with every key released; the first report after connect only seeds state. */
        void seed() {
            release();
        }

        void raw(byte[] report) {
            reads.add(report);
        }

        void timeouts(int count) {
            for (int i = 0; i < count; i++) {
                reads.add(0);
            }
        }

        private int read(org.mockito.invocation.InvocationOnMock invocation) throws Exception {
            byte[] buffer = invocation.getArgument(0);
            Object next = reads.poll(5, TimeUnit.MILLISECONDS);
            if (next == null) {
                return 0;
            }
            if (next instanceof CountDownLatch processed) {
                processed.countDown();
                return 0;
            }
            if (next instanceof Integer code) {
                return code;
            }
            byte[] payload = (byte[]) next;
            System.arraycopy(payload, 0, buffer, 0, payload.length);
            return payload.length;
        }

        private int write(org.mockito.invocation.InvocationOnMock invocation) {
            if (failWrites) {
                return -1;
            }
            byte[] payload = invocation.getArgument(0);
            int length = invocation.getArgument(1);
            byte reportId = invocation.getArgument(2);

            // Mirror hid4java: the report ID is prepended to the payload.
            byte[] wire = new byte[length + 1];
            wire[0] = reportId;
            System.arraycopy(payload, 0, wire, 1, length);
            writes.add(wire);
            return wire.length;
        }

        void awaitInput() throws InterruptedException {
            CountDownLatch processed = new CountDownLatch(1);
            reads.add(processed);
            assertTrue(processed.await(5, TimeUnit.SECONDS), "queued reports were processed");
        }

        void awaitUploaded(int expectedWrites) throws InterruptedException {
            await(() -> writes.size() >= expectedWrites, expectedWrites + " uploaded reports");
        }

        void awaitActions(int expected) throws InterruptedException {
            await(() -> actions.size() >= expected, expected + " actions");
        }

        void await(BooleanSupplier condition, String description) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                if (condition.getAsBoolean()) {
                    return;
                }
                Thread.sleep(5);
            }
            fail("timed out waiting for " + description);
        }

        @Override
        public void close() {
            for (StreamDeckMini service : started) {
                service.close();
            }
        }
    }

    /** Twelve distinct solid faces, so uploads are distinguishable. */
    private static List<byte[]> images() {
        List<byte[]> encoded = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            BufferedImage face = new BufferedImage(StreamDeckMiniProtocol.KEY_PIXELS,
                    StreamDeckMiniProtocol.KEY_PIXELS, BufferedImage.TYPE_INT_RGB);
            Color colour = Color.getHSBColor(i / 12f, 0.8f, 0.9f);
            for (int y = 0; y < face.getHeight(); y++) {
                for (int x = 0; x < face.getWidth(); x++) {
                    face.setRGB(x, y, colour.getRGB());
                }
            }
            try {
                encoded.add(StreamDeckMiniProtocol.encodeImage(face));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        return encoded;
    }
}
