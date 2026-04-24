package org.foxprod.rs232_remote;

import com.fazecast.jSerialComm.SerialPort;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.event.ActionEvent;
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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.ResourceBundle;

public class MainViewController implements Initializable {

    SerialPort activePort;

    Properties properties = new Properties();

    Map<String, String> config = new LinkedHashMap<>();

    OutputStream portOutputStream;

    Boolean isPortOpened = false,
            isTimerEngaged = false;

    ObjectProperty<java.time.Duration> remainingDuration = new SimpleObjectProperty<>();

    Timeline timerTimeline;

    @FXML
    private Button stopBtn;
    @FXML
    private Label timerLabel;
    @FXML
    private Button timerStartButton;
    @FXML
    private Spinner<Integer> timerSpinner;
    @FXML
    private Button button1;
    @FXML
    private Button button2;
    @FXML
    private Button button3;
    @FXML
    private Button button4;
    @FXML
    private Button button5;
    @FXML
    private CheckBox optional1;
    @FXML
    private Button openPortButton;
    @FXML
    private ComboBox<String> portsDropdown;

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
        } catch (Exception e) {
            setAlertWithParameters(Alert.AlertType.ERROR, "Error: could not load config file");
            throw new RuntimeException(e);
        }
    }

    /**
     * Sending a command from String
     *
     * @param command command (hex)
     */
    public void sendCommand(String command) {
        try {
            portOutputStream.write(new byte[]{(byte) Integer.parseInt(command, 16)});
            System.out.println("Sent " + Arrays.toString(new byte[]{(byte) Integer.parseInt(command, 16)}));
        } catch (IOException e) {
            setAlertWithParameters(Alert.AlertType.ERROR, "Error sending command: " + command);
            throw new RuntimeException(e);
        }
    }

    public void closePortOnExit() {
        closePort(activePort, portOutputStream);
    }

    public void onEjectPressed(ActionEvent actionEvent) {
        openPort();
        sendCommand(config.get("eject"));
    }

    public void onRewPressed(ActionEvent actionEvent) {
        openPort();
        sendCommand(config.get("rew"));
    }

    public void onPlayPressed(ActionEvent actionEvent) {
        openPort();
        sendCommand(config.get("play"));
    }

    public void onFFPressed(ActionEvent actionEvent) {
        openPort();
        sendCommand(config.get("ff"));
    }

    public void onStopPressed(ActionEvent actionEvent) {
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
    public void onOpenPortPressed(ActionEvent actionEvent) {
        if (isPortOpened) {
            closePort(activePort, portOutputStream);

            isPortOpened = false;
            openPortButton.setText("Open");
        } else {
            activePort = SerialPort.getCommPort(portsDropdown.getValue());

            portOutputStream = openPort(activePort);
            if (portOutputStream != null) {
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
    public void onOptionalClicked(ActionEvent actionEvent) {
        openPort();
        if (optional1.isSelected()) {
            activePort.setRTS();
        } else {
            activePort.clearRTS();
        }
    }

    public OutputStream openPort(SerialPort serialPort) {
        if (serialPort != null) {
            serialPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, 0, 0);

            serialPort.openPort();

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
        Alert alert = new Alert(alertType);
        alert.setContentText(text);
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
            if ((remainingDuration.get() == java.time.Duration.ofSeconds(-1)) || (remainingDuration.get() == java.time.Duration.ZERO)) {

                stopBtn.fire();
                timerStartButton.fire();
            }
        }));

        timerTimeline.setCycleCount(Timeline.INDEFINITE);
        timerTimeline.play();
    }

    public void onButton1Pressed(ActionEvent actionEvent) {
        openPort();
        sendCommand(config.get("button1_cmd"));
    }

    public void onButton2Pressed(ActionEvent actionEvent) {
        openPort();
        sendCommand(config.get("button2_cmd"));
    }

    public void onButton3Pressed(ActionEvent actionEvent) {
        openPort();
        sendCommand(config.get("button3_cmd"));
    }

    public void onButton4Pressed(ActionEvent actionEvent) {
        openPort();
        sendCommand(config.get("button4_cmd"));
    }

    public void onButton5Pressed(ActionEvent actionEvent) {
        openPort();
        sendCommand(config.get("button5_cmd"));
    }

    public void onTimerStartButtonPressed(ActionEvent actionEvent) {
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