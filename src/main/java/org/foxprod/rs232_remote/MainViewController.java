package org.foxprod.rs232_remote;

import javafx.beans.property.Property;
import javafx.fxml.FXML;
import com.fazecast.jSerialComm.SerialPort;
import javafx.fxml.Initializable;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Properties;

import static java.util.Map.entry;

public class MainViewController implements Initializable {

    Properties properties = new Properties();

    Map<String, String> codes = new LinkedHashMap<>();

    @FXML
    private Menu portMenu;

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {

        SerialPort[] ports = SerialPort.getCommPorts();

        for (SerialPort port : ports) {
            portMenu.getItems().add(new MenuItem(port.getSystemPortPath().substring(4)));
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
}