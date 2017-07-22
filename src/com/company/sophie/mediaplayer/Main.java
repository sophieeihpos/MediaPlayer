package com.company.sophie.mediaplayer;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class Main extends Application {

    @Override
    public void start(Stage primaryStage) throws Exception {

        FXMLLoader loader = new FXMLLoader(getClass().getResource("mainwindow.fxml"));
        Parent root = loader.load();
        Controller controller = loader.getController();
        controller.initMediaView(primaryStage);
        Scene scene=new Scene(root);
        primaryStage.setScene(scene);
        primaryStage.setTitle("Media Player");
        primaryStage.show();



    }

    public static void main(String[] args) {
        launch(args);
    }

}
