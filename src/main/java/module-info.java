module org.foxprod.rs232_remote {
    requires javafx.controls;
    requires javafx.fxml;

    requires com.fazecast.jSerialComm;
    requires java.desktop;
    requires java.logging;
    requires hid4java;

    opens org.foxprod.rs232_remote to javafx.fxml;
    exports org.foxprod.rs232_remote;
}