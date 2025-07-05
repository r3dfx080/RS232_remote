package org.foxprod.rs232_remote;

import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import com.fazecast.jSerialComm.SerialPort;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;

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
    private Menu portMenu;

    @FXML
    private Button openPortButton;

    @FXML
    private ComboBox<String> portsDropdown;

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {

        SerialPort[] ports = SerialPort.getCommPorts();

        for (SerialPort port : ports) {
            MenuItem item = new MenuItem(port.getSystemPortPath().substring(4));
            item.setOnAction(event -> {
                if (activePort != null) {
                    try {
                        portOutputStream.close();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    activePort.clearDTR();
                    activePort.clearRTS();
                    activePort.closePort();}
                activePort = SerialPort.getCommPort(port.getSystemPortPath());
                if (activePort.openPort()){
                    System.out.println("Opened port" + activePort.getSystemPortPath());
                }
                portOutputStream = activePort.getOutputStream();
            });
            portMenu.getItems().add(item);
        }
        loadCodes();
    }

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

    public void sendCommand(String command) {
        try {
            portOutputStream.write(new byte[] { (byte) Integer.parseInt(command, 16) });
            System.out.println("Sent " + Arrays.toString(new byte[]{(byte) Integer.parseInt(command, 16)}));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void closePortOnExit() {
        if (activePort != null) {
            try {
                portOutputStream.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            activePort.clearDTR();
            activePort.clearRTS();
            activePort.closePort();
        }
    }

    public void onEjectPressed(ActionEvent actionEvent) {
        sendCommand(codes.get("eject"));
    }

    public void onRewPressed(ActionEvent actionEvent) {
        sendCommand(codes.get("rew"));
    }

    public void onPlayPressed(ActionEvent actionEvent) {
        sendCommand(codes.get("play"));
    }

    public void onFFPressed(ActionEvent actionEvent) {
        sendCommand(codes.get("ff"));
    }

    public void onStopPressed(ActionEvent actionEvent) {
        sendCommand(codes.get("stop"));
    }

    public void onOpenPortPressed(ActionEvent actionEvent) {
        if (isPortOpened){
            openPortButton.setText("Close");
            try {
                portOutputStream.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            activePort.closePort();
        }
        else {
            try {
                activePort = SerialPort.getCommPort(portsDropdown.getValue());

                portOutputStream = activePort.getOutputStream();
            }
        }
    }
}