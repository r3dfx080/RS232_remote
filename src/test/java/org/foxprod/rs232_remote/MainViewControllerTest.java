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
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

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
    void streamDeckDispatchMatchesDesktopButtons() throws Exception {
        onFx(() -> {
            var buttons = loadDeckButtons();
            var fired = new java.util.ArrayList<Integer>();
            for (int i = 0; i < buttons.size(); i++) {
                int index = i;
                buttons.get(i).setOnAction(event -> fired.add(index));
            }
            for (int action = 0; action < 10; action++) {
                Main.fireStreamDeckAction(controller, action);
            }
            assertEquals(java.util.List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), fired);
        });
    }

    @Test
    void streamDeckHonorsDisabledButtons() throws Exception {
        onFx(() -> {
            var buttons = loadDeckButtons();
            var fired = new java.util.ArrayList<Button>();
            for (Button button : buttons) {
                button.setOnAction(event -> fired.add(button));
                button.setDisable(true);
            }
            for (int action = 0; action < 10; action++) {
                Main.fireStreamDeckAction(controller, action);
            }
            assertTrue(fired.isEmpty());
        });
    }

    @Test
    void invalidDeckActionDoesNothing() throws Exception {
        onFx(() -> {
            var fired = new java.util.ArrayList<Button>();
            for (Button button : loadDeckButtons()) {
                button.setOnAction(event -> fired.add(button));
            }
            Main.fireStreamDeckAction(controller, -1);
            Main.fireStreamDeckAction(controller, 10);
            assertTrue(fired.isEmpty());
        });
    }

    private java.util.List<Button> loadDeckButtons() {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("MainView.fxml"));
        loader.setControllerFactory(type -> new MainViewController() {
            @Override
            public void initialize(java.net.URL url, java.util.ResourceBundle resources) {
                // Load real FXML controls without touching serial hardware or user config.
            }
        });
        try {
            loader.load();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        controller = loader.getController();
        return java.util.List.of(controller.ejectBtn, controller.rewBtn, controller.playBtn,
                controller.ffBtn, controller.stopBtn, controller.button1, controller.button2,
                controller.button3, controller.button4, controller.button5);
    }

    @Test
    void openWithoutSelectionWarnsWithoutLookingUpPort() throws Exception {
        onFx(() -> {
            controller.portsDropdown = new javafx.scene.control.ComboBox<>();
            try (var serial = org.mockito.Mockito.mockStatic(SerialPort.class)) {
                controller.onOpenPortPressed();
                serial.verifyNoInteractions();
                verify(controller).setAlertWithParameters(Alert.AlertType.WARNING, "Select a COM port first.");
                assertFalse(controller.isPortOpened);
            }
        });
    }

    @Test
    void startupWithoutPortsLoadsCustomSettings(@TempDir Path directory) throws Exception {
        Path preferences = directory.resolve("config.properties");
        Files.writeString(preferences, "button1_name=Custom\noptional1_name=Dolby\noptional1_engaged=true\n");
        onFx(() -> {
            try (var serial = org.mockito.Mockito.mockStatic(SerialPort.class)) {
                serial.when(SerialPort::getCommPorts).thenReturn(new SerialPort[0]);
                FXMLLoader loader = new FXMLLoader(getClass().getResource("MainView.fxml"));
                controller.configPath = preferences;
                loader.setControllerFactory(type -> controller);
                try {
                    loader.load();
                } catch (IOException e) {
                    throw new AssertionError(e);
                }
                assertEquals("Custom", controller.button1.getText());
                assertEquals("Dolby", controller.optional1.getText());
                assertTrue(controller.optional1.isSelected());
                assertFalse(controller.isPortOpened);
                serial.verify(() -> SerialPort.getCommPort(any()), never());
            }
        });
    }

    @Test
    void rescanReplacesPortsAndPreservesAvailableSelection() throws Exception {
        onFx(() -> {
            controller.portsDropdown = new javafx.scene.control.ComboBox<>();
            SerialPort first = mock(SerialPort.class);
            SerialPort second = mock(SerialPort.class);
            when(first.getSystemPortName()).thenReturn("COM1");
            when(second.getSystemPortName()).thenReturn("COM2");
            try (var serial = org.mockito.Mockito.mockStatic(SerialPort.class)) {
                serial.when(SerialPort::getCommPorts).thenReturn(new SerialPort[]{first, second});
                controller.onRescanPressed();
                assertEquals(java.util.List.of("COM1", "COM2"), controller.portsDropdown.getItems());
                assertEquals("COM2", controller.portsDropdown.getValue());
                controller.properties.setProperty("last_used_com", "COM1");
                controller.portsDropdown.setValue(null);
                controller.onRescanPressed();
                assertEquals("COM1", controller.portsDropdown.getValue());
                controller.portsDropdown.setValue("COM1");
                controller.onRescanPressed();
                assertEquals(2, controller.portsDropdown.getItems().size());
                assertEquals("COM1", controller.portsDropdown.getValue());
                serial.when(SerialPort::getCommPorts).thenReturn(new SerialPort[]{second});
                controller.onRescanPressed();
                assertEquals("COM2", controller.portsDropdown.getValue());
                serial.when(SerialPort::getCommPorts).thenReturn(new SerialPort[0]);
                controller.onRescanPressed();
                assertTrue(controller.portsDropdown.getItems().isEmpty());
                assertNull(controller.portsDropdown.getValue());
                verify(controller).setAlertWithParameters(Alert.AlertType.WARNING, "No COM ports detected!");
            }
        });
    }

    @Test
    void lastUsedPortIsSavedWithoutChangingOtherProperties(@TempDir Path directory) throws Exception {
        controller.configPath = directory.resolve("config.properties");
        String original = "# keep this comment\r\nplay=3A\r\nshow_timer=false\r\n";
        Files.writeString(controller.configPath, original);
        controller.saveLastUsedPort("COM4");
        assertEquals(original + "last_used_com=COM4\r\n", Files.readString(controller.configPath));
        controller.saveLastUsedPort("COM7");
        assertEquals(original + "last_used_com=COM7\r\n", Files.readString(controller.configPath));
        assertEquals("COM7", controller.properties.getProperty("last_used_com"));
    }

    @Test
    void deviceStatusDotTracksAvailability() throws Exception {
        onFx(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("MainView.fxml"));
                loader.setControllerFactory(type -> new MainViewController() {
                    @Override
                    public void initialize(java.net.URL url, java.util.ResourceBundle resources) {
                        initializeDeviceStatus();
                    }
                });
                loader.load();
                MainViewController view = loader.getController();
                javafx.scene.shape.Circle dot = (javafx.scene.shape.Circle) loader.getNamespace().get("deviceStatusIndicator");
                assertEquals(5, dot.getRadius());
                var siblings = ((javafx.scene.layout.HBox) dot.getParent()).getChildren();
                assertEquals(siblings.indexOf(view.openPortButton) + 1, siblings.indexOf(dot));
                for (boolean available : new boolean[]{false, true, false}) {
                    view.deviceAvailable.set(available);
                    assertEquals(javafx.scene.paint.Color.web(available ? "#2e7d32" : "#d32f2f"), dot.getFill());
                    String text = available ? "Device available" : "Device unavailable";
                    assertEquals(text, dot.getAccessibleText());
                    javafx.scene.control.Tooltip tooltip = (javafx.scene.control.Tooltip)
                            dot.getProperties().get("javafx.scene.control.Tooltip");
                    assertEquals(text, tooltip.getText());
                }
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        });
    }

    @Test
    @DisplayName("Top View menu independently toggles timer and custom buttons without reserving hidden space")
    void viewMenuTogglesSectionsIndependently(@TempDir Path directory) throws Exception {
        Path preferences = directory.resolve("config.properties");
        Files.writeString(preferences, "show_timer=true\nshow_user_buttons=false\nplay=3A\n");
        FutureTask<Void> check = new FutureTask<>(() -> {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("MainView.fxml"));
            loader.setControllerFactory(type -> new MainViewController() {
                @Override
                public void initialize(java.net.URL url, java.util.ResourceBundle resources) {
                    configPath = preferences;
                    loadConfig();
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
                String original = "# custom buttons and their commands\r\nbutton3_name=SH >>\r\n\r\n"
                        + "# basic buttons' commands\r\nplay=4B\r\n"
                        + "# enable/disable modules showing\r\nshow_timer = true  \r\nshow_user_buttons=false\r\n";
                Files.writeString(preferences, original);
                customItem.fire();
                assertEquals(original.replace("show_user_buttons=false", "show_user_buttons=true"),
                        Files.readString(preferences), "Saving must preserve comments, order, whitespace and line endings");
                Properties saved = new Properties();
                try (InputStream input = Files.newInputStream(preferences)) {
                    saved.load(input);
                }
                assertEquals("true  ", saved.getProperty("show_timer"));
                assertEquals("true", saved.getProperty("show_user_buttons"));
                assertEquals("4B", saved.getProperty("play"), "Saving visibility must preserve settings edited since startup");
                root.layout();
                assertTrue(stage.getHeight() > timerOnlyHeight, "Window must grow when another section is shown");
                Node stop = (Node) loader.getNamespace().get("stopBtn");
                double buttonBottom = stop.localToScene(stop.getBoundsInLocal()).getMaxY();
                assertTrue(stage.getScene().getHeight() - buttonBottom >= 14,
                        "Transport buttons must keep their bottom margin with all sections visible");
                customItem.setSelected(false);
                customItem.fire();
                assertEquals(original, Files.readString(preferences));
                assertEquals(timerOnlyHeight, stage.getHeight(), 1.0,
                        "Window must shrink again when the section is hidden");
                timerItem.setSelected(false);
                timerItem.fire();
                FXMLLoader reopened = new FXMLLoader(getClass().getResource("MainView.fxml"));
                reopened.setControllerFactory(loader.getControllerFactory());
                reopened.load();
                assertFalse(((Node) reopened.getNamespace().get("timerSection")).isVisible());
                assertFalse(((Node) reopened.getNamespace().get("customButtonsSection")).isVisible());
                Files.writeString(preferences, "play=3A\n");
                ((MainViewController) reopened.getController()).loadConfig();
                assertTrue(((Node) reopened.getNamespace().get("timerSection")).isVisible(),
                        "Timer defaults to visible when the preference is missing");
                assertTrue(((Node) reopened.getNamespace().get("customButtonsSection")).isVisible(),
                        "Custom buttons default to visible when the preference is missing");
                customItem.fire();
                assertEquals("play=3A\nshow_timer=false\nshow_user_buttons=false\n", Files.readString(preferences),
                        "Missing preferences must be appended without rewriting existing settings");
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
    @DisplayName("adapter heartbeat")
    class Heartbeat {
        private SerialPort port;
        private ByteArrayOutputStream output;

        @BeforeEach
        void prep() throws Exception {
            stubButtons();
            port = mock(SerialPort.class);
            when(port.isOpen()).thenReturn(true);
            output = new ByteArrayOutputStream();
            controller.activePort = port;
            controller.portOutputStream = output;
        }

        private void reply(int... bytes) throws Exception {
            byte[] data = new byte[bytes.length];
            for (int i = 0; i < bytes.length; i++) data[i] = (byte) bytes[i];
            when(port.getInputStream()).thenReturn(new java.io.ByteArrayInputStream(data));
        }

        private void ping() throws Exception {
            onFx(controller::pingDevice);
            awaitExchangeComplete();
        }

        private void failRead() {
            when(port.getInputStream()).thenReturn(new InputStream() {
                public int read() throws IOException {
                    throw new IOException("unplugged");
                }
            });
        }

        @Test
        void pingWritesEnquiryAndAcceptsOnlyPong() throws Exception {
            for (int response : new int[]{0x07, 0x06, 0x15, -1}) {
                output.reset();
                controller.missedHeartbeats = 0;
                if (response == -1) reply(); else reply(response);
                ping();
                org.junit.jupiter.api.Assertions.assertArrayEquals(new byte[]{0x05}, output.toByteArray());
                assertEquals(response == 0x07, controller.deviceAvailable.get());
                assertFalse(controller.reconnectRequired);
            }
            controller.missedHeartbeats = 0;
            failRead();
            ping();
            assertFalse(controller.deviceAvailable.get());
            assertFalse(controller.reconnectRequired);
            verify(controller, never()).setAlertWithParameters(any(), any());
        }

        @Test
        void failedPingCanRecover() throws Exception {
            reply();
            ping();
            assertFalse(controller.deviceAvailable.get());
            reply(0x07);
            ping();
            assertTrue(controller.deviceAvailable.get());
        }

        @Test
        void twoMissedHeartbeatsDisconnect() throws Exception {
            controller.deviceStatusIndicator = new javafx.scene.shape.Circle();
            controller.isPortOpened = true;
            // One miss stays connected.
            reply();
            ping();
            assertTrue(controller.isPortOpened);
            assertEquals(1, controller.missedHeartbeats);
            // A PONG in between clears the count, so misses must be consecutive.
            reply(0x07);
            ping();
            assertEquals(0, controller.missedHeartbeats);
            // Two misses in a row close the port.
            reply();
            ping();
            reply();
            ping();
            assertFalse(controller.isPortOpened);
            verify(port).closePort();
        }

        @Test
        void remoteFailureTurnsDotRedButAllowsHeartbeat() throws Exception {
            controller.deviceAvailable.set(true);
            reply();
            onFx(() -> controller.sendCommand("3A"));
            awaitExchangeComplete();
            assertFalse(controller.deviceAvailable.get());
            assertTrue(controller.reconnectRequired);
            reply(0x07);
            ping();
            assertTrue(controller.deviceAvailable.get());
            assertTrue(controller.reconnectRequired, "Heartbeat must not clear an unknown command outcome");
        }

        @Test
        void pingSkipsBusyAndClosedPorts() throws Exception {
            controller.awaitingReply = true;
            onFx(controller::pingDevice);
            controller.awaitingReply = false;
            controller.portOutputStream = null;
            onFx(controller::pingDevice);
            controller.portOutputStream = output;
            when(port.isOpen()).thenReturn(false);
            onFx(controller::pingDevice);
            assertEquals(0, output.size());
            when(port.isOpen()).thenReturn(true);
            reply(0x07);
            ping();
            assertEquals(1, output.size());
        }

        @Test
        void pingUsesShortTimeoutAndRestoresCommandTimeout() throws Exception {
            reply(0x07);
            ping();
            var order = org.mockito.Mockito.inOrder(port);
            order.verify(port).setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, 50, 0);
            order.verify(port).getInputStream();
            order.verify(port).setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, 5000, 0);
            org.mockito.Mockito.clearInvocations(port);
            failRead();
            ping();
            verify(port).setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, 5000, 0);
        }

        @Test
        void latePongDoesNotConsumeCommandAck() throws Exception {
            reply(0x07, 0x06);
            onFx(() -> controller.sendCommand("3A"));
            awaitExchangeComplete();
            assertFalse(controller.reconnectRequired);
            verify(controller, never()).setAlertWithParameters(any(), any());
            reply(0x07);
            onFx(() -> controller.sendCommand("3A"));
            awaitExchangeComplete();
            assertTrue(controller.reconnectRequired);
            verify(controller).setAlertWithParameters(eq(Alert.AlertType.ERROR), any());
        }

        @Test
        void commandPressedDuringHeartbeatRunsAfterIt() throws Exception {
            CountDownLatch reading = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            InputStream heldPong = new InputStream() {
                public int read() throws IOException {
                    reading.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("test timeout");
                    } catch (InterruptedException e) {
                        throw new IOException(e);
                    }
                    return 0x07;
                }
            };
            when(port.getInputStream()).thenReturn(
                    heldPong, new java.io.ByteArrayInputStream(new byte[]{0x06}));
            onFx(controller::pingDevice);
            assertTrue(reading.await(5, TimeUnit.SECONDS));
            onFx(() -> {
                assertTrue(controller.heartbeatInFlight);
                controller.sendCommand("3A");
                assertEquals(0x3A, controller.pendingCommand);
                // Only the PING byte is out so far; the command must wait for the ping to finish.
                assertEquals(1, output.size(), "Command must wait until the ping finishes");
            });
            release.countDown();
            awaitExchangeComplete();
            org.junit.jupiter.api.Assertions.assertArrayEquals(
                    new byte[]{0x05, 0x3A}, output.toByteArray());
            assertEquals(-1, controller.pendingCommand);
            assertFalse(controller.reconnectRequired);
        }

        @Test
        void latestPressWinsWhenQueuedDuringHeartbeat() throws Exception {
            CountDownLatch reading = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            InputStream heldPong = new InputStream() {
                public int read() throws IOException {
                    reading.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("test timeout");
                    } catch (InterruptedException e) {
                        throw new IOException(e);
                    }
                    return 0x07;
                }
            };
            when(port.getInputStream()).thenReturn(
                    heldPong, new java.io.ByteArrayInputStream(new byte[]{0x06}));
            onFx(controller::pingDevice);
            assertTrue(reading.await(5, TimeUnit.SECONDS));
            onFx(() -> {
                controller.sendCommand("3A");
                controller.sendCommand("4B");
                assertEquals(0x4B, controller.pendingCommand);
            });
            release.countDown();
            awaitExchangeComplete();
            org.junit.jupiter.api.Assertions.assertArrayEquals(
                    new byte[]{0x05, 0x4B}, output.toByteArray());
        }

        @Test
        void commandDuringRealCommandIsNotQueued() throws Exception {
            CountDownLatch reading = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            InputStream heldAck = new InputStream() {
                public int read() throws IOException {
                    reading.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("test timeout");
                    } catch (InterruptedException e) {
                        throw new IOException(e);
                    }
                    return 0x06;
                }
            };
            when(port.getInputStream()).thenReturn(heldAck);
            onFx(() -> controller.sendCommand("3A"));
            assertTrue(reading.await(5, TimeUnit.SECONDS));
            onFx(() -> {
                assertFalse(controller.heartbeatInFlight);
                controller.sendCommand("4B");
                assertEquals(-1, controller.pendingCommand, "A real command in flight must not be queued over");
            });
            release.countDown();
            awaitExchangeComplete();
            org.junit.jupiter.api.Assertions.assertArrayEquals(
                    new byte[]{0x3A}, output.toByteArray());
        }

        @Test
        void closeDiscardsPendingCommand() throws Exception {
            controller.pendingCommand = 0x3A;
            onFx(() -> controller.closePort(port, output));
            assertEquals(-1, controller.pendingCommand);
        }

        @Test
        void closeInvalidatesOldHeartbeat() throws Exception {
            CountDownLatch reading = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            var worker = new java.util.concurrent.atomic.AtomicReference<Thread>();
            when(port.getInputStream()).thenReturn(new InputStream() {
                public int read() throws IOException {
                    assertFalse(Platform.isFxApplicationThread());
                    worker.set(Thread.currentThread());
                    reading.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("test timeout");
                    } catch (InterruptedException e) {
                        throw new IOException(e);
                    }
                    return 0x07;
                }
            });
            controller.deviceAvailable.addListener((observable, before, after) -> {
                if (after) throw new AssertionError("Old connection must not turn green");
            });
            onFx(controller::pingDevice);
            assertTrue(reading.await(5, TimeUnit.SECONDS));
            try {
                when(port.isOpen()).thenReturn(false);
                onFx(() -> controller.closePort(port, output));
                assertNull(controller.portOutputStream);
                assertFalse(controller.awaitingReply);
                assertFalse(controller.deviceAvailable.get());
            } finally {
                release.countDown();
            }
            worker.get().join(5000);
            assertFalse(worker.get().isAlive());
            drainFxQueue();
            assertFalse(controller.deviceAvailable.get());
        }

        @Test
        void heartbeatLifecycle() throws Exception {
            onFx(() -> {
                controller.optional1 = mock(javafx.scene.control.CheckBox.class);
                when(controller.optional1.isSelected()).thenReturn(true);
                doNothing().when(controller).saveLastUsedPort(any());
                controller.portsDropdown = mock(javafx.scene.control.ComboBox.class);
                when(controller.portsDropdown.getValue()).thenReturn("COM1");
                try (var serial = org.mockito.Mockito.mockStatic(SerialPort.class)) {
                    serial.when(() -> SerialPort.getCommPort("COM1")).thenReturn(port);
                    when(port.openPort()).thenReturn(false);
                    controller.onOpenPortPressed();
                    assertNull(controller.heartbeatTimeline);
                    verify(controller, never()).saveLastUsedPort(any());
                    assertFalse(controller.deviceAvailable.get());
                    when(port.openPort()).thenReturn(true);
                    when(port.getOutputStream()).thenReturn(output);
                    when(port.isOpen()).thenReturn(true);
                    when(port.getInputStream()).thenReturn(new java.io.ByteArrayInputStream(new byte[]{0x07}));
                    controller.deviceStatusIndicator = new javafx.scene.shape.Circle();
                    controller.onOpenPortPressed();
                    verify(controller).saveLastUsedPort("COM1");
                    verify(port).setRTS();
                    try {
                        assertEquals(javafx.animation.Animation.Status.RUNNING, controller.heartbeatTimeline.getStatus());
                        assertEquals(3000, controller.heartbeatTimeline.getKeyFrames().getFirst().getTime().toMillis());
                        assertEquals(javafx.animation.Timeline.INDEFINITE, controller.heartbeatTimeline.getCycleCount());
                        controller.deviceAvailable.set(true);
                    } finally {
                        controller.closePortOnExit();
                    }
                    assertEquals(javafx.animation.Animation.Status.STOPPED, controller.heartbeatTimeline.getStatus());
                    assertFalse(controller.deviceAvailable.get());
                }
            });
        }

        @Test
        void pendingTimerStopSurvivesHeartbeat() throws Exception {
            reply(0x07);
            controller.stopAfterReply = true;
            ping();
            verify(controller.stopBtn).fire();
            assertFalse(controller.stopAfterReply);
        }

        @Test
        void pendingTimerStopSurvivesPingDuringRejectionDialog() throws Exception {
            CountDownLatch reading = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            var stops = new java.util.concurrent.atomic.AtomicInteger();
            InputStream heldPong = new InputStream() {
                public int read() throws IOException {
                    reading.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("test timeout");
                    } catch (InterruptedException e) {
                        throw new IOException(e);
                    }
                    return 0x07;
                }
            };
            when(port.getInputStream()).thenReturn(
                    new java.io.ByteArrayInputStream(new byte[]{0x15}), heldPong);
            org.mockito.Mockito.doAnswer(invocation -> {
                controller.pingDevice();
                return null;
            }).when(controller).setAlertWithParameters(eq(Alert.AlertType.WARNING), any());
            onFx(() -> {
                controller.stopBtn = new Button();
                controller.stopBtn.setOnAction(event -> stops.incrementAndGet());
                controller.stopAfterReply = true;
                controller.sendCommand("3A");
            });
            try {
                assertTrue(reading.await(5, TimeUnit.SECONDS));
                onFx(() -> {
                    // Heartbeats no longer disable buttons, but a STOP still must wait for the
                    // in-flight ping rather than firing twice.
                    assertTrue(controller.stopAfterReply, "STOP must wait for the heartbeat to finish");
                    assertEquals(0, stops.get());
                });
            } finally {
                release.countDown();
                awaitExchangeComplete();
            }
            assertEquals(1, stops.get());
            assertFalse(controller.stopAfterReply);
        }
    }

    private static void onFx(Runnable action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(action, null);
        Platform.runLater(task);
        task.get(5, TimeUnit.SECONDS);
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
