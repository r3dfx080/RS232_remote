package org.foxprod.rs232_remote;

import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import com.fazecast.jSerialComm.SerialPort;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.util.*;

public class MainViewController implements Initializable {

    SerialPort activePort;

    Properties properties = new Properties();

    Map<String, String> codes = new LinkedHashMap<>();

    OutputStream portOutputStream;

    Boolean isPortOpened = false;

    @FXML
    private Button openPortButton;

    @FXML
    private ComboBox<String> portsDropdown;

    // Getting all ports from system and adding them to dropdown menu
    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {

        SerialPort[] ports = SerialPort.getCommPorts();

        for (SerialPort port : ports) {
            portsDropdown.getItems().add(port.getSystemPortPath().substring(4));
        }
        portsDropdown.setValue(portsDropdown.getItems().getLast());

        loadCodes();
    }

    // Loading codes from config inti HashMap
    public void loadCodes() {
        try(FileInputStream fileInputStream = new FileInputStream("config.properties")) {
            properties.load(fileInputStream);
            for (String key : properties.stringPropertyNames()) {
                codes.put(key, properties.getProperty(key));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // Sending a command from String
    public void sendCommand(String command) {
        try {
            portOutputStream.write(new byte[] { (byte) Integer.parseInt(command, 16) });
            System.out.println("Sent " + Arrays.toString(new byte[]{(byte) Integer.parseInt(command, 16)}));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void closePortOnExit() {
        closePort(activePort, portOutputStream);
    }

    public void onEjectPressed(ActionEvent actionEvent) {
        if (!isPortOpened) {openPortButton.fire();}
        sendCommand(codes.get("eject"));
    }

    public void onRewPressed(ActionEvent actionEvent) {
        if (!isPortOpened) {openPortButton.fire();}
        sendCommand(codes.get("rew"));
    }

    public void onPlayPressed(ActionEvent actionEvent) {
        if (!isPortOpened) {openPortButton.fire();}
        sendCommand(codes.get("play"));
    }

    public void onFFPressed(ActionEvent actionEvent) {
        if (!isPortOpened) {openPortButton.fire();}
        sendCommand(codes.get("ff"));
    }

    public void onStopPressed(ActionEvent actionEvent) {
        if (!isPortOpened) {openPortButton.fire();}
        sendCommand(codes.get("stop"));
    }

    // Opening port if not open yet and changing button text accordingly
    public void onOpenPortPressed(ActionEvent actionEvent) {
        if (isPortOpened){
            closePort(activePort, portOutputStream);

            isPortOpened = false;
            openPortButton.setText("Open");
        }
        else {
            activePort = SerialPort.getCommPort(portsDropdown.getValue());

            portOutputStream = openPort(activePort);

            isPortOpened = true;
            openPortButton.setText("Close");
        }
    }

    public OutputStream openPort(SerialPort serialPort) {
        if (serialPort != null) {
        serialPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, 0, 0);

        serialPort.openPort();

        return serialPort.getOutputStream();
        }
        return null;
    }

    public void closePort(SerialPort serialPort, OutputStream outputStream) {
        if (serialPort != null && serialPort.isOpen()) {
            try {
                outputStream.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            serialPort.clearDTR();
            serialPort.clearRTS();
            serialPort.closePort();
        }
    }
}