package org.foxprod.rs232_remote;

import com.fazecast.jSerialComm.SerialPort;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.Tooltip;
import javafx.scene.input.ScrollEvent;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.ResourceBundle;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainViewController implements Initializable {

    SerialPort activePort;

    Properties properties = new Properties();
    Path configPath = Path.of("config.properties");

    Map<String, String> config = new LinkedHashMap<>();

    OutputStream portOutputStream;

    boolean awaitingReply;
    boolean heartbeatInFlight;
    boolean stopAfterReply;
    boolean reconnectRequired;
    int pendingCommand = -1;
    final BooleanProperty deviceAvailable = new SimpleBooleanProperty(false);
    Timeline heartbeatTimeline;
    long connectionGeneration;
    int missedHeartbeats;

    private static final int PING_COMMAND = 0x05;
    private static final int PONG_CODE = 0x07;

    Boolean isPortOpened = false,
            isTimerEngaged = false;

    ObjectProperty<java.time.Duration> remainingDuration = new SimpleObjectProperty<>();

    Timeline timerTimeline;

    @FXML
    Button stopBtn;
    @FXML
    Button ejectBtn, rewBtn, playBtn, ffBtn;
    @FXML
    Label timerLabel;
    @FXML
    Button timerStartButton;
    @FXML
    Spinner<Integer> timerSpinner;
    @FXML
    Button button1;
    @FXML
    Button button2;
    @FXML
    Button button3;
    @FXML
    Button button4;
    @FXML
    Button button5;
    @FXML
    CheckBox optional1;
    @FXML
    Button openPortButton;
    @FXML
    ComboBox<String> portsDropdown;
    @FXML
    Circle deviceStatusIndicator;
    @FXML
    CheckMenuItem showTimerItem, showCustomButtonsItem;

    /**
     * Getting all ports from system and adding them to dropdown menu
     */
    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        initializeDeviceStatus();
        loadConfig();

        SerialPort[] ports = SerialPort.getCommPorts();

        if (ports.length == 0) {
            setAlertWithParameters(Alert.AlertType.WARNING, "No COM ports detected!");
        } else {
            for (SerialPort port : ports) {
                portsDropdown.getItems().add(port.getSystemPortPath().substring(4));
            }
            portsDropdown.setValue(portsDropdown.getItems().getLast());

            // Loading buttons' names from config file
            button1.setText(config.get("button1_name"));
            button2.setText(config.get("button2_name"));
            button3.setText(config.get("button3_name"));
            button4.setText(config.get("button4_name"));
            button5.setText(config.get("button5_name"));
            optional1.setText(config.get("optional1_name"));

            if (config.get("optional1_engaged").equals("true")) {
                optional1.fire();
            }
        }
        // Initializing spinner with default value & setting up step
        SpinnerValueFactory<Integer> spinnerValueFactory = new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 360, 10, 1);
        timerSpinner.setValueFactory(spinnerValueFactory);

        deviceStatusIndicator.setVisible(false);
    }

    void initializeDeviceStatus() {
        deviceStatusIndicator.fillProperty().bind(Bindings.when(deviceAvailable)
                .then(Color.web("#2e7d32")).otherwise(Color.web("#d32f2f")));
        deviceStatusIndicator.accessibleTextProperty().bind(Bindings.when(deviceAvailable)
                .then("Device available").otherwise("Device unavailable"));
        Tooltip tooltip = new Tooltip();
        tooltip.textProperty().bind(deviceStatusIndicator.accessibleTextProperty());
        Tooltip.install(deviceStatusIndicator, tooltip);
    }


    @FXML
    public void onViewChanged() {
        try {
            String saved = Files.readString(configPath, StandardCharsets.ISO_8859_1);
            saved = updateViewPreference(saved, "show_timer", showTimerItem.isSelected());
            saved = updateViewPreference(saved, "show_user_buttons", showCustomButtonsItem.isSelected());
            Path temporary = Files.createTempFile(configPath.toAbsolutePath().getParent(), "config-", ".tmp");
            try {
                Files.writeString(temporary, saved, StandardCharsets.ISO_8859_1);
                Files.move(temporary, configPath, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException e) {
            setAlertWithParameters(Alert.AlertType.ERROR, "Error: could not save View preferences to config file.");
        }
        timerSpinner.getScene().getWindow().sizeToScene();
    }

    private static String updateViewPreference(String text, String key, boolean selected) {
        Matcher entry = Pattern.compile("(?m)^([\\t ]*" + key
                + "[\\t ]*[=:][\\t ]*)[^\\r\\n]*?([\\t ]*)(?=\\r?$)").matcher(text);
        if (entry.find()) {
            return entry.replaceAll("$1" + selected + "$2");
        }
        String newline = text.contains("\r\n") ? "\r\n" : "\n";
        return text + (text.isEmpty() || text.endsWith("\n") ? "" : newline)
                + key + "=" + selected + newline;
    }

    /**
     * Loading config into a HashMap
     */
    public void loadConfig() {
        try (InputStream fileInputStream = Files.newInputStream(configPath)) {
            properties.clear();
            properties.load(fileInputStream);
            config.clear();
            for (String key : properties.stringPropertyNames()) {
                config.put(key, properties.getProperty(key));
            }
            showTimerItem.setSelected(Boolean.parseBoolean(properties.getProperty("show_timer", "true").trim()));
            showCustomButtonsItem.setSelected(Boolean.parseBoolean(properties.getProperty("show_user_buttons", "true").trim()));
        } catch (IOException e) {
            setAlertWithParameters(Alert.AlertType.ERROR,
                    "Error: could not load config file. The application will close.");
            Platform.exit();
        }
    }

    /**
     * Sending a command from String
     *
     * @param command command (hex)
     */
    public void sendCommand(String command) {
        if (portOutputStream == null) {
            return;
        }
        if (reconnectRequired) {
            setAlertWithParameters(Alert.AlertType.ERROR, "Close and reopen the port before sending another command.");
            return;
        }
        final int value;
        try {
            value = Integer.parseInt(command, 16);
            if (value < 0 || value > 0xff) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            setAlertWithParameters(Alert.AlertType.ERROR, "Invalid command: " + command);
            return;
        }
        if (awaitingReply) {
            // A command pressed during a heartbeat runs once the ping finishes; the latest
            // press wins. A real command already in flight must complete first, so drop.
            if (heartbeatInFlight) {
                pendingCommand = value;
            }
            return;
        }
        sendWithAck(activePort, portOutputStream, value, false);
    }

    /**
     * Sending a command that was pressed while a heartbeat held the port, if any
     */
    private void flushPendingCommand() {
        if (pendingCommand < 0 || portOutputStream == null || reconnectRequired) {
            pendingCommand = -1;
            return;
        }
        int command = pendingCommand;
        pendingCommand = -1;
        sendWithAck(activePort, portOutputStream, command, false);
    }

    void pingDevice() {
        if (awaitingReply || portOutputStream == null || activePort == null || !activePort.isOpen()) {
            if (!awaitingReply) {
                deviceAvailable.set(false);
            }
            return;
        }
        sendWithAck(activePort, portOutputStream, PING_COMMAND, true);
    }

    private void setAwaitingReply(boolean waiting) {
        setAwaitingReply(waiting, true);
    }

    // Heartbeats hold the serial line but must not toggle the buttons, otherwise the
    // whole control row flickers disabled on every 3-second ping.
    private void setAwaitingReply(boolean waiting, boolean updateButtons) {
        awaitingReply = waiting;
        if (updateButtons) {
            for (Button button : new Button[]{openPortButton, ejectBtn, rewBtn, playBtn, ffBtn,
                    stopBtn, button1, button2, button3, button4, button5}) {
                button.setDisable(waiting);
            }
        }
    }

    private void sendWithAck(SerialPort port, OutputStream output, int command, boolean heartbeat) {
        long generation = connectionGeneration;
        heartbeatInFlight = heartbeat;
        setAwaitingReply(true, !heartbeat);
        Thread worker = new Thread(() -> {
            int received = exchange(port, output, command, heartbeat);
            Platform.runLater(() -> {
                if (generation != connectionGeneration) {
                    return;
                }
                setAwaitingReply(false, !heartbeat);
                heartbeatInFlight = false;
                boolean stopDue = stopAfterReply;
                stopAfterReply = false;
                if (heartbeat) {
                    deviceAvailable.set(received == PONG_CODE);
                    if (received == PONG_CODE) {
                        missedHeartbeats = 0;
                        if (stopDue) {
                            stopBtn.fire();
                        }
                        flushPendingCommand();
                    } else if (++missedHeartbeats >= 2) {
                        // Two misses in a row means the adapter is gone; close the port
                        // so the user reconnects with a fresh handle via Open.
                        disconnect();
                    }
                    return;
                }
                if (received == 0x06) {
                    if (stopDue) {
                        stopBtn.fire();
                    }
                    return;
                }
                if (received == 0x15) {
                    setAlertWithParameters(Alert.AlertType.WARNING, "The IR adapter rejected the command.");
                    if (stopDue) {
                        // The modal warning can run heartbeat callbacks while it is open.
                        if (awaitingReply) {
                            stopAfterReply = true;
                        } else {
                            stopBtn.fire();
                        }
                    }
                    return;
                }
                reconnectRequired = true;
                deviceAvailable.set(false);
                setAlertWithParameters(Alert.AlertType.ERROR,
                        "No valid reply. The command outcome is unknown. Close and reopen the port.");
            });
        }, heartbeat ? "serial-heartbeat" : "serial-command");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Writing a command byte and reading the reply, skipping stray PONGs on command reads
     *
     * @param command   command byte
     * @param heartbeat true for a ping, false for a remote command
     * @return reply byte, or -1 on timeout or failure
     */
    private int exchange(SerialPort port, OutputStream output, int command, boolean heartbeat) {
        int reply = -1;
        try {
            int timeout = heartbeat ? 50 : 5000;
            port.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, timeout, 0);
            output.write(command);
            InputStream input = port.getInputStream();
            long deadline = System.nanoTime() + timeout * 1_000_000L;
            reply = input.read();
            while (!heartbeat && reply == PONG_CODE) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return -1;
                }
                port.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING,
                        (int) Math.max(1, (remaining + 999_999) / 1_000_000), 0);
                reply = input.read();
            }
        } catch (IOException e) {
            // A failed exchange leaves the next reply uncorrelated.
            return -1;
        } finally {
            port.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, 5000, 0);
        }
        return reply;
    }

    public void closePortOnExit() {
        closePort(activePort, portOutputStream);
    }

    public void onEjectPressed() {
        openPort();
        sendCommand(config.get("eject"));
    }

    public void onRewPressed() {
        openPort();
        sendCommand(config.get("rew"));
    }

    public void onPlayPressed() {
        openPort();
        sendCommand(config.get("play"));
    }

    public void onFFPressed() {
        openPort();
        sendCommand(config.get("ff"));
    }

    public void onStopPressed() {
        openPort();
        sendCommand(config.get("stop"));
    }

    private void openPort() {
        if (!isPortOpened) {
            openPortButton.fire();
        }
    }

    /**
     * Opening port if not opened yet and changing button text accordingly
     */
    public void onOpenPortPressed() {
        if (awaitingReply) {
            return;
        }
        if (isPortOpened) {
            disconnect();
        } else {
            activePort = SerialPort.getCommPort(portsDropdown.getValue());

            portOutputStream = openPort(activePort);
            if (portOutputStream != null) {
                connectionGeneration++;
                deviceAvailable.set(false);
                reconnectRequired = false;
                missedHeartbeats = 0;
                isPortOpened = true;
                deviceStatusIndicator.setVisible(true);
                openPortButton.setText("Close");
                heartbeatTimeline = new Timeline(new KeyFrame(Duration.seconds(3), event -> pingDevice()));
                heartbeatTimeline.setCycleCount(Timeline.INDEFINITE);
                heartbeatTimeline.play();
                pingDevice();
            } else {
                deviceAvailable.set(false);
                setAlertWithParameters(Alert.AlertType.ERROR, "Error: could not open port");
            }
        }
    }

    /**
     * Optional checkbox enables RTS
     */
    public void onOptionalClicked() {
        openPort();
        if (activePort == null || !activePort.isOpen()) {
            return;
        }
        if (optional1.isSelected()) {
            activePort.setRTS();
        } else {
            activePort.clearRTS();
        }
    }

    /**
     * Closing the port and restoring the disconnected UI state
     */
    void disconnect() {
        closePort(activePort, portOutputStream);
        deviceStatusIndicator.setVisible(false);
        isPortOpened = false;
        openPortButton.setText("Open");
    }

    public OutputStream openPort(SerialPort serialPort) {
        if (serialPort != null) {
            serialPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, 5000, 0);
            serialPort.setComPortParameters(9600, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
            // Avoid resetting the Nano just before the first command is sent.
            serialPort.clearDTR();
            if (!serialPort.openPort()) {
                return null;
            }
            serialPort.flushIOBuffers();

            return serialPort.getOutputStream();
        } else {
            setAlertWithParameters(Alert.AlertType.ERROR, "Error: serial port is null");
        }
        return null;
    }

    public void closePort(SerialPort serialPort, OutputStream outputStream) {
        connectionGeneration++;
        if (heartbeatTimeline != null) {
            heartbeatTimeline.stop();
        }
        deviceAvailable.set(false);
        stopAfterReply = false;
        heartbeatInFlight = false;
        pendingCommand = -1;
        setAwaitingReply(false);
        portOutputStream = null;
        isPortOpened = false;
        if (serialPort != null && serialPort.isOpen()) {
            try {
                if (outputStream != null) {
                    outputStream.close();
                }
            } catch (IOException e) {
                setAlertWithParameters(Alert.AlertType.ERROR, "Error closing port");
            } finally {
                serialPort.clearDTR();
                serialPort.clearRTS();
                serialPort.closePort();
            }
        }
    }

    /**
     * Setting up a custom alert with parameters
     *
     * @param alertType alert type
     * @param text      displayed text
     */
    void setAlertWithParameters(Alert.AlertType alertType, String text) {
        Label content = new Label(text);
        content.setWrapText(true);

        Alert alert = new Alert(alertType);
        alert.initOwner(openPortButton.getScene().getWindow());
        alert.getDialogPane().setContent(content);
        alert.showAndWait();
    }

    /**
     * Setting up a timer from timerSpinner value
     */
    private void setUpTimer() {
        timerTimeline = new Timeline();
        remainingDuration.setValue(java.time.Duration.ofMinutes(timerSpinner.getValue()));
        timerLabel.textProperty().bind(Bindings.createStringBinding(() ->
                        String.format("%01d:%02d",
                                remainingDuration.get().toHours(),
                                remainingDuration.get().toMinutesPart()),
                remainingDuration));

        timerTimeline.getKeyFrames().add(new KeyFrame(Duration.minutes(1), event -> {
            remainingDuration.setValue(remainingDuration.get().minus(1, ChronoUnit.MINUTES));
            if (remainingDuration.get().isZero() || remainingDuration.get().isNegative()) {
                if (awaitingReply) {
                    stopAfterReply = true;
                } else {
                    stopBtn.fire();
                }
                timerStartButton.fire();
            }
        }));

        timerTimeline.setCycleCount(Timeline.INDEFINITE);
        timerTimeline.play();
    }

    public void onButton1Pressed() {
        openPort();
        sendCommand(config.get("button1_cmd"));
    }

    public void onButton2Pressed() {
        openPort();
        sendCommand(config.get("button2_cmd"));
    }

    public void onButton3Pressed() {
        openPort();
        sendCommand(config.get("button3_cmd"));
    }

    public void onButton4Pressed() {
        openPort();
        sendCommand(config.get("button4_cmd"));
    }

    public void onButton5Pressed() {
        openPort();
        sendCommand(config.get("button5_cmd"));
    }

    public void onTimerStartButtonPressed() {
        if (isTimerEngaged) {
            timerTimeline.stop();
            timerLabel.textProperty().unbind();
            timerLabel.setText("");

            isTimerEngaged = false;
            timerSpinner.setEditable(true);
            Platform.runLater(() -> {
                timerSpinner.lookupAll(".increment-arrow-button").forEach(node -> node.setDisable(false));
                timerSpinner.lookupAll(".decrement-arrow-button").forEach(node -> node.setDisable(false));
            });
            timerStartButton.setText("Start timer");
        } else {
            setUpTimer();
            Platform.runLater(() -> {
                timerSpinner.lookupAll(".increment-arrow-button").forEach(node -> node.setDisable(true));
                timerSpinner.lookupAll(".decrement-arrow-button").forEach(node -> node.setDisable(true));
            });
            isTimerEngaged = true;
            timerSpinner.setEditable(false);
            timerStartButton.setText("Stop timer");
        }
    }

    public void onTimerValueScrolled(ScrollEvent scrollEvent) {
        if (!isTimerEngaged) {
            if (scrollEvent.getDeltaY() > 0) {
                timerSpinner.increment();
            } else {
                timerSpinner.decrement();
            }
        }
    }
}
