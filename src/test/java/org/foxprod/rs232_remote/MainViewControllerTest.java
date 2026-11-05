package org.foxprod.rs232_remote;

import com.fazecast.jSerialComm.SerialPort;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.MenuBar;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MainViewController} logic, with JavaFX and the serial
 * port mocked out. The controller is spied so the alert method (which needs a
 * live JavaFX toolkit) can be stubbed to a no-op.
 */
class MainViewControllerTest {

    private MainViewController controller;

    @Test
    @DisplayName("Top View menu independently toggles timer and custom buttons without reserving hidden space")
    void viewMenuTogglesSectionsIndependently() throws Exception {
        FutureTask<Void> check = new FutureTask<>(() -> {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("MainView.fxml"));
            loader.setControllerFactory(type -> new MainViewController() {
                @Override
                public void initialize(java.net.URL url, java.util.ResourceBundle resources) {
                    // Skip serial-port discovery and config loading for this layout test.
                }
            });
            VBox root = loader.load();
            assertTrue(root.getChildren().getFirst() instanceof MenuBar, "The menu bar must be at the top");
            MenuBar menuBar = (MenuBar) root.getChildren().getFirst();
            CheckMenuItem timerItem = (CheckMenuItem) menuBar.getMenus().getFirst().getItems().get(0);
            CheckMenuItem customItem = (CheckMenuItem) menuBar.getMenus().getFirst().getItems().get(1);
            Node timer = (Node) loader.getNamespace().get("timerSection");
            Node custom = (Node) loader.getNamespace().get("customButtonsSection");
            assertTrue(timer.isVisible());
            assertTrue(timer.isManaged());
            assertFalse(custom.isVisible());
            assertFalse(custom.isManaged());

            customItem.setSelected(true);
            assertTrue(timer.isVisible());
            assertTrue(timer.isManaged());
            assertTrue(custom.isVisible());
            assertTrue(custom.isManaged());

            timerItem.setSelected(false);
            assertFalse(timer.isVisible());
            assertFalse(timer.isManaged());
            assertTrue(custom.isVisible());
            assertTrue(custom.isManaged());

            customItem.setSelected(false);
            assertFalse(timer.isVisible());
            assertFalse(timer.isManaged());
            assertFalse(custom.isVisible());
            assertFalse(custom.isManaged());

            timerItem.setSelected(true);
            assertTrue(timer.isVisible());
            assertTrue(timer.isManaged());
            assertFalse(custom.isVisible());
            assertFalse(custom.isManaged());

            Platform.setImplicitExit(false);
            Stage stage = new Stage();
            stage.setResizable(false);
            stage.setScene(new Scene(root));
            try {
                stage.show();
                double timerOnlyHeight = stage.getHeight();
                customItem.setSelected(true);
                customItem.fire();
                root.layout();
                assertTrue(stage.getHeight() > timerOnlyHeight, "Window must grow when another section is shown");
                Node stop = (Node) loader.getNamespace().get("stopBtn");
                double buttonBottom = stop.localToScene(stop.getBoundsInLocal()).getMaxY();
                assertTrue(stage.getScene().getHeight() - buttonBottom >= 14,
                        "Transport buttons must keep their bottom margin with all sections visible");
                customItem.setSelected(false);
                customItem.fire();
                assertEquals(timerOnlyHeight, stage.getHeight(), 1.0,
                        "Window must shrink again when the section is hidden");
            } finally {
                stage.hide();
            }
            return null;
        });
        Platform.runLater(check);
        check.get(5, TimeUnit.SECONDS);
    }

    /** Start the JavaFX platform once so Platform.runLater(...) executes. */
    @BeforeAll
    static void initToolkit() {
        try {
            Platform.startup(() -> { });
        } catch (IllegalStateException alreadyStarted) {
            // Toolkit already running (e.g. another test started it); fine.
        }
    }

    /** Block until all currently-queued JavaFX runnables have executed. */
    private static void drainFxQueue() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(latch::countDown);
        if (!latch.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("Timed out waiting for JavaFX event queue to drain");
        }
    }

    /**
     * Wait for an in-flight sendWithAck exchange to finish. The worker thread
     * does its serial I/O, then posts the result handler to the FX queue which
     * clears awaitingReply. We poll until that flag drops (worker done +
     * handler ran), then drain once more so the handler's effects are visible.
     */
    private void awaitExchangeComplete() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (controller.awaitingReply && System.currentTimeMillis() < deadline) {
            drainFxQueue();
            Thread.sleep(5);
        }
        drainFxQueue();
        if (controller.awaitingReply) {
            throw new AssertionError("Exchange did not complete within timeout");
        }
    }

    @BeforeEach
    void setUp() {
        controller = spy(new MainViewController());
        // Alert.showAndWait() needs the JavaFX toolkit; stub it away.
        doNothing().when(controller).setAlertWithParameters(any(), any());
    }

    /** Wire up the @FXML Button fields with mocks so .fire()/.setDisable() are no-ops. */
    private void stubButtons() {
        controller.openPortButton = mock(Button.class);
        controller.ejectBtn = mock(Button.class);
        controller.rewBtn = mock(Button.class);
        controller.playBtn = mock(Button.class);
        controller.ffBtn = mock(Button.class);
        controller.stopBtn = mock(Button.class);
        controller.button1 = mock(Button.class);
        controller.button2 = mock(Button.class);
        controller.button3 = mock(Button.class);
        controller.button4 = mock(Button.class);
        controller.button5 = mock(Button.class);
    }

    @Nested
    @DisplayName("sendCommand hex validation")
    class SendCommandValidation {

        @BeforeEach
        void openOutputStream() {
            stubButtons();
            // Non-null output stream so sendCommand gets past the early return.
            controller.portOutputStream = new ByteArrayOutputStream();
        }

        @Test
        @DisplayName("returns early and sends nothing when no output stream")
        void noOutputStreamDoesNothing() {
            controller.portOutputStream = null;
            controller.sendCommand("55");
            // No alert, no button disabling: nothing happened.
            verify(controller, never()).setAlertWithParameters(any(), any());
            verify(controller.stopBtn, never()).setDisable(anyBoolean());
        }

        @Test
        @DisplayName("rejects non-hex input with an alert")
        void nonHexRejected() {
            controller.sendCommand("zz");
            verify(controller).setAlertWithParameters(eq(Alert.AlertType.ERROR),
                    eq("Invalid command: zz"));
        }

        @Test
        @DisplayName("rejects values above 0xFF")
        void aboveByteRangeRejected() {
            controller.sendCommand("100"); // 256
            verify(controller).setAlertWithParameters(eq(Alert.AlertType.ERROR),
                    eq("Invalid command: 100"));
        }

        @Test
        @DisplayName("rejects negative values")
        void negativeRejected() {
            controller.sendCommand("-1");
            verify(controller).setAlertWithParameters(eq(Alert.AlertType.ERROR),
                    eq("Invalid command: -1"));
        }

        @Test
        @DisplayName("accepts a valid byte and proceeds to send (disables buttons)")
        void validByteProceeds() throws Exception {
            // activePort is needed by sendWithAck; mock it with a readable stream.
            SerialPort port = mock(SerialPort.class);
            when(port.getInputStream()).thenReturn(new java.io.ByteArrayInputStream(new byte[]{0x06}));
            controller.activePort = port;

            controller.sendCommand("06");

            // Valid path never raises the "Invalid command" alert.
            verify(controller, never()).setAlertWithParameters(eq(Alert.AlertType.ERROR),
                    eq("Invalid command: 06"));
            // setAwaitingReply(true) ran synchronously, disabling buttons.
            verify(controller.stopBtn).setDisable(true);
        }

        @Test
        @DisplayName("blocks sending while awaiting a reply")
        void blockedWhileAwaiting() throws Exception {
            // A read that blocks forever keeps awaitingReply true for the test's duration,
            // so the second send is deterministically rejected (no race with the worker).
            SerialPort port = mock(SerialPort.class);
            when(port.getInputStream()).thenReturn(new InputStream() {
                public int read() throws IOException {
                    try {
                        Thread.sleep(10_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    throw new IOException("interrupted test read");
                }
            });
            controller.activePort = port;

            controller.sendCommand("06"); // sets awaitingReply = true on this thread
            // A second command while awaiting must be ignored (no new button disabling).
            controller.sendCommand("07");
            verify(controller.stopBtn, times(1)).setDisable(true);
        }

        @Test
        @DisplayName("blocks sending and alerts when a reconnect is required")
        void blockedWhenReconnectRequired() {
            controller.reconnectRequired = true;
            controller.sendCommand("06");
            verify(controller).setAlertWithParameters(eq(Alert.AlertType.ERROR),
                    eq("Close and reopen the port before sending another command."));
        }
    }

    @Nested
    @DisplayName("sendWithAck reply handling")
    class ReplyHandling {

        private SerialPort portReplying(int replyByte) throws IOException {
            SerialPort port = mock(SerialPort.class);
            when(port.getInputStream())
                    .thenReturn(new java.io.ByteArrayInputStream(new byte[]{(byte) replyByte}));
            return port;
        }

        @BeforeEach
        void prep() {
            stubButtons();
            controller.portOutputStream = new ByteArrayOutputStream();
        }

        @Test
        @DisplayName("ACK (0x06) re-enables buttons and sets no reconnect flag")
        void ackReenablesButtons() throws Exception {
            controller.activePort = portReplying(0x06);
            controller.sendCommand("06");
            awaitExchangeComplete();

            assertFalse(controller.awaitingReply);
            assertFalse(controller.reconnectRequired);
            verify(controller.stopBtn).setDisable(false); // re-enabled
        }

        @Test
        @DisplayName("ACK with a pending timer-stop fires the stop button")
        void ackFiresPendingStop() throws Exception {
            controller.activePort = portReplying(0x06);
            controller.stopAfterReply = true;
            controller.sendCommand("06");
            awaitExchangeComplete();

            verify(controller.stopBtn).fire();
            assertFalse(controller.stopAfterReply); // consumed
        }

        @Test
        @DisplayName("NAK (0x15) warns and does not require reconnect")
        void nakWarns() throws Exception {
            controller.activePort = portReplying(0x15);
            controller.sendCommand("06");
            awaitExchangeComplete();

            verify(controller).setAlertWithParameters(eq(Alert.AlertType.WARNING),
                    eq("The IR adapter rejected the command."));
            assertFalse(controller.reconnectRequired);
        }

        @Test
        @DisplayName("NAK with a pending timer-stop still fires the stop button")
        void nakFiresPendingStop() throws Exception {
            controller.activePort = portReplying(0x15);
            controller.stopAfterReply = true;
            controller.sendCommand("06");
            awaitExchangeComplete();

            verify(controller.stopBtn).fire();
        }

        @Test
        @DisplayName("unexpected reply sets reconnectRequired and errors")
        void unexpectedReplyRequiresReconnect() throws Exception {
            controller.activePort = portReplying(0x00);
            controller.sendCommand("06");
            awaitExchangeComplete();

            assertTrue(controller.reconnectRequired);
            verify(controller).setAlertWithParameters(eq(Alert.AlertType.ERROR), any());
        }

        @Test
        @DisplayName("no reply (read returns -1) is treated as unexpected -> reconnect")
        void noReplyRequiresReconnect() throws Exception {
            SerialPort port = mock(SerialPort.class);
            when(port.getInputStream()).thenReturn(new InputStream() {
                public int read() {
                    return -1; // end of stream
                }
            });
            controller.activePort = port;
            controller.sendCommand("06");
            awaitExchangeComplete();

            assertTrue(controller.reconnectRequired);
        }

        @Test
        @DisplayName("IOException during exchange is treated as unexpected -> reconnect")
        void ioExceptionRequiresReconnect() throws Exception {
            SerialPort port = mock(SerialPort.class);
            OutputStream throwing = new OutputStream() {
                public void write(int b) throws IOException {
                    throw new IOException("boom");
                }
            };
            controller.portOutputStream = throwing;
            controller.activePort = port;
            controller.sendCommand("06");
            awaitExchangeComplete();

            assertTrue(controller.reconnectRequired);
        }
    }

    @Nested
    @DisplayName("openPort configuration")
    class OpenPort {

        @BeforeEach
        void prep() {
            stubButtons();
        }

        @Test
        @DisplayName("returns null and does not expose a stream when the port fails to open")
        void failedOpenReturnsNull() {
            SerialPort port = mock(SerialPort.class);
            when(port.openPort()).thenReturn(false);
            assertNull(controller.openPort(port));
            verify(port, never()).getOutputStream();
        }

        @Test
        @DisplayName("configures 9600/8N1 and returns the stream on success")
        void successConfiguresAndReturnsStream() {
            SerialPort port = mock(SerialPort.class);
            when(port.openPort()).thenReturn(true);
            OutputStream stream = new ByteArrayOutputStream();
            when(port.getOutputStream()).thenReturn(stream);

            assertEquals(stream, controller.openPort(port));
            verify(port).setComPortParameters(9600, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
            verify(port).flushIOBuffers();
        }

        @Test
        @DisplayName("clears DTR before opening to avoid resetting the Nano")
        void clearsDtrBeforeOpening() {
            SerialPort port = mock(SerialPort.class);
            when(port.openPort()).thenReturn(true);
            when(port.getOutputStream()).thenReturn(new ByteArrayOutputStream());

            controller.openPort(port);

            var order = org.mockito.Mockito.inOrder(port);
            order.verify(port).clearDTR();
            order.verify(port).openPort();
            order.verify(port).getOutputStream();
        }

        @Test
        @DisplayName("null port alerts and returns null")
        void nullPortAlerts() {
            assertNull(controller.openPort(null));
            verify(controller).setAlertWithParameters(eq(Alert.AlertType.ERROR),
                    eq("Error: serial port is null"));
        }
    }

    @Nested
    @DisplayName("onOptionalClicked guards")
    class OptionalClicked {

        @BeforeEach
        void prep() {
            stubButtons();
            controller.optional1 = mock(javafx.scene.control.CheckBox.class);
        }

        @Test
        @DisplayName("does nothing when the port is null (no NPE)")
        void nullPortIsSafe() {
            controller.isPortOpened = true; // so openPort() won't try to fire
            controller.activePort = null;
            controller.onOptionalClicked(); // must not throw
        }

        @Test
        @DisplayName("sets RTS when selected and port is open")
        void setsRtsWhenSelected() {
            SerialPort port = mock(SerialPort.class);
            when(port.isOpen()).thenReturn(true);
            controller.activePort = port;
            controller.isPortOpened = true;
            when(controller.optional1.isSelected()).thenReturn(true);

            controller.onOptionalClicked();
            verify(port).setRTS();
        }

        @Test
        @DisplayName("clears RTS when not selected and port is open")
        void clearsRtsWhenNotSelected() {
            SerialPort port = mock(SerialPort.class);
            when(port.isOpen()).thenReturn(true);
            controller.activePort = port;
            controller.isPortOpened = true;
            when(controller.optional1.isSelected()).thenReturn(false);

            controller.onOptionalClicked();
            verify(port).clearRTS();
        }
    }
}
