package org.foxprod.rs232_remote;

import com.fazecast.jSerialComm.SerialPort;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.input.ScrollEvent;
import javafx.util.Duration;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.ResourceBundle;

public class MainViewController implements Initializable {

    SerialPort activePort;

    Properties properties = new Properties();

    Map<String, String> config = new LinkedHashMap<>();

    OutputStream portOutputStream;

    boolean awaitingReply;
    boolean stopAfterReply;
    boolean reconnectRequired;

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

    /**
     * Getting all ports from system and adding them to dropdown menu
     */
    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {

        SerialPort[] ports = SerialPort.getCommPorts();

        if (ports.length == 0) {
            setAlertWithParameters(Alert.AlertType.WARNING, "No COM ports detected!");
        } else {
            for (SerialPort port : ports) {
                portsDropdown.getItems().add(port.getSystemPortPath().substring(4));
            }
            portsDropdown.setValue(portsDropdown.getItems().getLast());

            loadConfig();

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
    }


    /**
     * Loading config into a HashMap
     */
    public void loadConfig() {
        try (FileInputStream fileInputStream = new FileInputStream("config.properties")) {
            properties.load(fileInputStream);
            for (String key : properties.stringPropertyNames()) {
                config.put(key, properties.getProperty(key));
            }
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
        if (portOutputStream == null || awaitingReply) {
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
        sendWithAck(activePort, portOutputStream, value);
    }

    private void setAwaitingReply(boolean waiting) {
        awaitingReply = waiting;
        for (Button button : new Button[]{openPortButton, ejectBtn, rewBtn, playBtn, ffBtn,
                stopBtn, button1, button2, button3, button4, button5}) {
            button.setDisable(waiting);
        }
    }

    private void sendWithAck(SerialPort port, OutputStream output, int command) {
        setAwaitingReply(true);
        Thread worker = new Thread(() -> {
            int reply = -1;
            try {
                output.write(command);
                reply = port.getInputStream().read();
            } catch (IOException e) {
                // A failed exchange leaves the next reply uncorrelated.
            }
            int received = reply;
            Platform.runLater(() -> {
                setAwaitingReply(false);
                boolean stopDue = stopAfterReply;
                stopAfterReply = false;
                if (received == 0x06) {
                    if (stopDue) {
                        stopBtn.fire();
                    }
                    return;
                }
                if (received == 0x15) {
                    setAlertWithParameters(Alert.AlertType.WARNING, "The IR adapter rejected the command.");
                    if (stopDue) {
                        stopBtn.fire();
                    }
                    return;
                }
                reconnectRequired = true;
                setAlertWithParameters(Alert.AlertType.ERROR,
                        "No valid reply. The command outcome is unknown. Close and reopen the port.");
            });
        }, "serial-command");
        worker.setDaemon(true);
        worker.start();
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
            closePort(activePort, portOutputStream);

            isPortOpened = false;
            openPortButton.setText("Open");
        } else {
            activePort = SerialPort.getCommPort(portsDropdown.getValue());

            portOutputStream = openPort(activePort);
            if (portOutputStream != null) {
                reconnectRequired = false;
                isPortOpened = true;
                openPortButton.setText("Close");
            } else {
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
        if (serialPort != null && serialPort.isOpen()) {
            try {
                outputStream.close();
            } catch (IOException e) {
                setAlertWithParameters(Alert.AlertType.ERROR, "Error closing port");
                throw new RuntimeException(e);
            }
            portOutputStream = null;
            serialPort.clearDTR();
            serialPort.clearRTS();
            serialPort.closePort();
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
