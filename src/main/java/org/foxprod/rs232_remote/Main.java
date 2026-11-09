package org.foxprod.rs232_remote;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Main extends Application {

    private MainViewController controller;
    private StreamDeckMini streamDeck;

    @Override
    public void start(Stage stage) throws IOException {
        FXMLLoader fxmlLoader = new FXMLLoader(Main.class.getResource("MainView.fxml"));
        Scene scene = new Scene(fxmlLoader.load());

        controller = fxmlLoader.getController();

        stage.setResizable(false);
        stage.setTitle("RS-232 remote");
        stage.getIcons().add(new Image(Objects.requireNonNull(Main.class.getResourceAsStream("/org/foxprod/rs232_remote/icon.png"))));
        stage.setScene(scene);
        stage.setAlwaysOnTop(true);
        stage.show();

        try {
            var images = new ArrayList<byte[]>();
            for (var face : StreamDeckFaces.build(controller.config)) {
                images.add(StreamDeckMiniProtocol.encodeImage(face));
            }
            streamDeck = new StreamDeckMini(action -> fireStreamDeckAction(controller, action), images);
            streamDeck.start();
        } catch (IOException | RuntimeException | LinkageError failure) {
            Logger.getLogger(Main.class.getName()).log(Level.WARNING,
                    "Stream Deck Mini disabled; desktop controls remain available.", failure);
        }
    }

    static void fireStreamDeckAction(MainViewController controller, int action) {
        Button[] buttons = {controller.ejectBtn, controller.rewBtn, controller.playBtn,
                controller.ffBtn, controller.stopBtn, controller.button1, controller.button2,
                controller.button3, controller.button4, controller.button5};
        if (action >= 0 && action < buttons.length) {
            buttons[action].fire();
        }
    }

    @Override
    public void stop() throws Exception {
        try {
            if (streamDeck != null) {
                streamDeck.close();
            }
        } finally {
            if (controller != null) {
                controller.closePortOnExit();
            }
            super.stop();
        }
    }
}
