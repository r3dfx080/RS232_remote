module org.foxprod.rs232_remote {
    requires javafx.controls;
    requires javafx.fxml;

    requires com.fazecast.jSerialComm;

    opens org.foxprod.rs232_remote to javafx.fxml;
    exports org.foxprod.rs232_remote;
}