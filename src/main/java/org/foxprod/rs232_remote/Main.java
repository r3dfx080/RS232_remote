package org.foxprod.rs232_remote;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.io.IOException;

public class Main extends Application {

    private MainViewController controller;

    @Override
    public void start(Stage stage) throws IOException {
        FXMLLoader fxmlLoader = new FXMLLoader(Main.class.getResource("MainView.fxml"));
        Scene scene = new Scene(fxmlLoader.load(), 640, 240);

        controller = fxmlLoader.getController();

        stage.setResizable(false);
        stage.setTitle("RS-232 remote");
        stage.getIcons().add(new Image("file:icon.png"));
        stage.setScene(scene);
        stage.show();
    }

    public static void main(String[] args) {
        launch();
    }

    @Override
    public void stop() throws Exception {
        if (controller != null){
            controller.closePortOnExit();
        }
        super.stop();
    }
}