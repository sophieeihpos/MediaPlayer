package com.company.sophie.mediaplayer;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.control.Button;
import javafx.scene.layout.*;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.util.Callback;
import javafx.util.Duration;

import javax.swing.*;
import java.io.*;
import java.net.URI;
import java.net.URL;

import java.text.SimpleDateFormat;
import java.util.*;


/**
 * Created by Sophie on 06-Jul-17.
 */
public class Controller implements Initializable {
    @FXML
    private BorderPane overallPane;
    @FXML
    private MediaView mediaView;
    @FXML
    private Label mediaStatusLabel;
    @FXML
    private Label progressLabel;
    @FXML
    private HBox playHBox;
    @FXML
    private Button playButton;
    @FXML
    private Slider seekSlider;
    @FXML
    private ListView<String> topListView;
    @FXML
    private ListView<String> bottomListView;

    private static final double DIVIDERRATIO = 0.7;

    private URI mediaURI = null;
    private MediaPlayer mediaPlayer;
    private Media media;
    private URI scriptsURI = null;
    private static final double BUTTONHEIGHT = 35;
    private ObservableList<String> topScriptsList;
    private ObservableList<String> bottomScriptsList;
    private boolean isShowScript=false;
    private ArrayList<Scripts> scriptsList;
    private int scriptIndex;
    private Thread updateListViewsThread;
    private double mediaDuration=0;
    private final int NUM_OF_LISTVIEW_ITEMS=10;
    private Runnable listViewsRunnable;

    @Override
    public void initialize(URL location, ResourceBundle resources) {


        mediaView.setPreserveRatio(true);

        mediaStatusLabel.setPadding(new Insets(5,10,5,10));
        progressLabel.setPadding(new Insets(5,10,5,10));
        progressLabel.setText(" ");
        mediaStatusLabel.setText(" ");
        playHBox.setPadding(new Insets(5,10,20,10));

        topScriptsList = FXCollections.observableArrayList();
        bottomScriptsList = FXCollections.observableArrayList();
        topListView.setItems(topScriptsList);
        bottomListView.setItems(bottomScriptsList);


        emptyListViews();
        topScriptsList.addListener(new ListChangeListener<String>() {
            @Override
            public void onChanged(Change<? extends String> c) {
                topListView.scrollTo(NUM_OF_LISTVIEW_ITEMS -1);
            }
        });
        bottomScriptsList.addListener(new ListChangeListener<String>() {
            @Override
            public void onChanged(Change<? extends String> c) {
                bottomListView.scrollTo(0);
                bottomListView.getSelectionModel().select(0);
            }
        });

        Callback<ListView<String>, ListCell<String>> callBack = new Callback<ListView<String>, ListCell<String>>(){
            @Override
            public ListCell<String> call(ListView<String> listView) {
                return new ListCell<String>(){
                    @Override
                    protected void updateItem(String item, boolean empty) {
                        super.updateItem(item, empty);
                        Text text = new Text(item);
                        text.wrappingWidthProperty().bind(getListView().widthProperty().subtract(50));
                        setPrefWidth(0);
                        setGraphic(text);
                        setPadding(new Insets(10,10,10,10));
                    }
                };
            }
        };
        topListView.setCellFactory(callBack);
        bottomListView.setCellFactory(callBack);



        listViewsRunnable = new Runnable() {
            final Object synchrObject = new Object();
            @Override
            public void run() {
                synchronized (synchrObject){
                    long sleepMilliSeconds ;
                    double currentTime;
                    while(scriptIndex<scriptsList.size()-1){
                        currentTime=mediaPlayer.getCurrentTime().toMillis();
                        sleepMilliSeconds= (long) (scriptsList.get(scriptIndex+1).getTime()- currentTime);
                        try {updateListViewsThread.sleep(sleepMilliSeconds);
                        } catch (InterruptedException e) {
                            return;
                        }
                        if(!isShowScript){
                            return;
                        }
                        Platform.runLater(new Runnable() {
                            @Override
                            public void run() {
                                topScriptsList.remove(0);
                                topScriptsList.add(bottomScriptsList.get(0));
                                topListView.refresh();
                                bottomScriptsList.remove(0);
                                int nextScriptIndex=scriptIndex+ NUM_OF_LISTVIEW_ITEMS;
                                String nextScript;
                                if (nextScriptIndex<=scriptsList.size()){
                                    nextScript = scriptsList.get(nextScriptIndex).getText();
                                }else {
                                    nextScript="";
                                }
                                bottomScriptsList.add(nextScript);
                                bottomListView.refresh();
                                if(!isShowScript){
                                    return;
                                }
                            }
                        });
                        scriptIndex++;
                    }
                    bottomScriptsList.set(NUM_OF_LISTVIEW_ITEMS -1,"");
                    topScriptsList.set(NUM_OF_LISTVIEW_ITEMS -1,"");
                }
            }
        };
    }

    public void initMediaView(Stage stage){
        mediaView.fitWidthProperty().bind(stage.widthProperty().multiply(DIVIDERRATIO));
        topListView.prefWidthProperty().bind(stage.widthProperty().multiply(1-DIVIDERRATIO));
        stage.setMaximized(true);
    }

    public void playButtonAction() {
        if(mediaURI!=null){
            MediaPlayer.Status status = mediaPlayer.getStatus();
            if (status==MediaPlayer.Status.PLAYING){
                pause();
            }else {
                play();
            }
        }

    }

    public void play(){
        MediaPlayer.Status status = mediaPlayer.getStatus();
        String playButtonText = playButton.getText();
        if (status==MediaPlayer.Status.PLAYING
                || status == MediaPlayer.Status.STOPPED
                || status == MediaPlayer.Status.PAUSED
                || status == MediaPlayer.Status.READY) {
            if (playButtonText.compareTo(">") == 0) {
                mediaPlayer.play();
                mediaStatusLabel.setText("Status: playing");
                playButton.setText("||");
                if (scriptsURI != null) {
                    startListViewThread();
                }
            }
        }else{
            mediaStatusLabel.setText("Status: unknown");
        }
    }

    public void pause(){
        MediaPlayer.Status status = mediaPlayer.getStatus();
        String playButtonText = playButton.getText();
        if (status==MediaPlayer.Status.PLAYING) {
            if (playButtonText.compareTo("||") == 0) {
                if (updateListViewsThread.isAlive()) {
                    isShowScript = false;
                    updateListViewsThread.interrupt();
                }
                mediaView.getMediaPlayer().pause();
                playButton.setText(">");
                mediaStatusLabel.setText("Status: paused");
            }
        }
    }

    public void stopButtonAction() {
        if (mediaURI != null) {
            mediaView.getMediaPlayer().stop();
            playButton.setText(">");
            mediaStatusLabel.setText("Status: stopped");
            if ( updateListViewsThread.isAlive()){
                isShowScript=false;
                updateListViewsThread.interrupt();
                emptyListViews();
            }

        }
    }

    public void openMediaFile() {
        JFileChooser fileChooser = new JFileChooser();
        int isApproved = fileChooser.showOpenDialog(null);

        if (isApproved == JFileChooser.APPROVE_OPTION) {
            mediaURI = fileChooser.getSelectedFile().toURI();
        }
        if (mediaURI != null) {
            media = new Media(mediaURI.toString());
            mediaPlayer = new MediaPlayer(media);
            mediaView.setMediaPlayer(mediaPlayer);
            mediaStatusLabel.setText("Status: loading...");
            mediaPlayer.setOnReady(new Runnable(){
                @Override
                public void run() {
                    mediaStatusLabel.setText("Status: ready");
                    mediaDuration = mediaPlayer.getMedia().getDuration().toSeconds();
                    seekSlider.setMax(mediaDuration);
                    mediaPlayer.currentTimeProperty().addListener(new ChangeListener<Duration>() {
                        @Override
                        public void changed(ObservableValue<? extends Duration> observable, Duration oldValue, Duration newValue) {
                            seekSlider.setValue(newValue.toSeconds());
                            String timeProgress = formatSeconds(newValue.toSeconds());
                            String timeTotal = formatSeconds(mediaDuration);
                            progressLabel.setText(timeProgress+" / "+ timeTotal);
                        }
                    });
                }
            });

            matchScriptsFile();

            mediaPlayer.setOnEndOfMedia(new Runnable() {
                @Override
                public void run() {
                    mediaStatusLabel.setText("Status: finished");
                }
            });

            final double MINIMUM_CHANGE = 0.5;
            seekSlider.valueProperty().addListener(new ChangeListener<Number>() {
                @Override
                synchronized public void changed(ObservableValue<? extends Number> observable, Number oldValue, Number newValue) {
                    double change = Math.abs((double) newValue - (double) oldValue) ;
                    if(change> MINIMUM_CHANGE){
                        mediaPlayer.seek(Duration.seconds(((double)newValue)));
                        if (updateListViewsThread.isAlive()){
                            isShowScript=false;
                            updateListViewsThread.interrupt();
                            startListViewThread();
                        }
                    }
                }
            });
        }

    }

    public void openScriptsFile(){
        JFileChooser fileChooser = new JFileChooser();
        int isApproved =  fileChooser.showOpenDialog(null);
        if(isApproved==fileChooser.APPROVE_OPTION){
            scriptsURI = fileChooser.getSelectedFile().toURI();
            isShowScript=true;
            try {
                loadScripts();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

    }

    public void startListViewThread(){
        isShowScript = true;
        double seekValue = seekSlider.getValue();
        scriptIndex = findScriptIndex(seekValue);
        if (scriptIndex==-1){
            return;
        }else {
            initialiseBottomListView(scriptIndex);
            if(updateListViewsThread!=null){
                if(updateListViewsThread.isAlive()){
                    try {
                        updateListViewsThread.join();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
            updateListViewsThread = new Thread (listViewsRunnable);
            updateListViewsThread.setDaemon(true);
            updateListViewsThread.start();
        }
    }

    public void matchScriptsFile(){
        String mediaPath = mediaURI.getPath();
        String scriptsPath = mediaPath.substring(0,mediaPath.lastIndexOf(".")) + ".srt";
        File scriptFile = new File( scriptsPath);
        if(scriptFile.exists()){
            scriptsURI=URI.create(scriptsPath);
        }
        isShowScript=true;
        try {
            loadScripts();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void loadScripts() throws Exception {
        File file;
        Scanner scanner;
        scriptsList  = new ArrayList<>();
        double startTime=0;
        String text="";
        String currentLine="";
        String[] timeLine;
        Date tempTime;
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("hh:mm:ss");
        Date refTime = simpleDateFormat.parse("00:00:00");
        file = new File(scriptsURI.getPath());
        scanner = new Scanner(file, "Unicode");
        while (scanner.hasNextLine()) {
            if (currentLine.isEmpty()){
                currentLine = scanner.nextLine();
            }

            if (isInt(currentLine)){
                currentLine=scanner.nextLine();
                if (currentLine.contains(" --> ")) {
                    timeLine = currentLine.split("-->");
                    tempTime = simpleDateFormat.parse(timeLine[0]);
                    startTime = tempTime.getTime() - refTime.getTime();
                    currentLine = scanner.nextLine();
                    while (!currentLine.isEmpty()) {
                        if (text.isEmpty()) {
                            text = currentLine;
                        } else {
                            text = text + " " + currentLine;
                        }
                        if(scanner.hasNextLine()){
                            currentLine=scanner.nextLine();
                        }
                    }
                }
            }
            scriptsList.add(new Scripts(startTime,text));
            text="";
        }

        if (scanner!=null){
            scanner.close();
        }
        scriptsList.add(0,new Scripts(0,""));
        scriptsList.add(new Scripts(mediaDuration,""));

    }

    public int findScriptIndex(double currentTime){

        boolean found=false;

        int i=0;
        int size = scriptsList.size();
        int upperIndex=size-1;
        int lowerIndex=1;
        int pointer;
        double pointerStartTime;
        double pointerFinishTime;

        int result=-1;
        currentTime=currentTime*1000;
        if (currentTime<scriptsList.get(1).getTime()){
            result= 0;
            found=true;
        } else if(currentTime==mediaDuration*1000){
            result=-1;
        } else{ while(found==false||i<=Math.ceil(Math.log(size-1)/Math.log(2))){
            pointer=(int) Math.floor((lowerIndex+upperIndex)*0.5);
            pointerStartTime=scriptsList.get(pointer).getTime();
            pointerFinishTime=scriptsList.get(pointer+1).getTime();
            if(currentTime>=pointerStartTime && currentTime<pointerFinishTime){
                result= pointer;
                found=true;
            }else if (currentTime<pointerStartTime){
                upperIndex=pointer;
            }else if (currentTime>=pointerFinishTime){
                lowerIndex=pointer+1;
            }else{
                result= -1;
            }
            i++;
        }
        }
        return result;
    }

    public void initialiseBottomListView(int startIndex){
        emptyListViews();
        int size=scriptsList.size()-startIndex;
        if (size>=bottomScriptsList.size()) {
            size = bottomScriptsList.size();
        }
        for(int i =0;i<size;i++){
            bottomScriptsList.set(i,scriptsList.get(i+startIndex).getText());
        }
        bottomListView.refresh();
    }

    public void updateListViews(){

    }
    public void emptyListViews() {
        topScriptsList.clear();
        bottomScriptsList.clear();
        for (int i = 0; i< NUM_OF_LISTVIEW_ITEMS; i++){
            topScriptsList.add("");
            bottomScriptsList.add("");
        }
    }

    public static boolean isInt(String str)
    {
        try {
            Integer.parseInt(str);
        }
        catch(NumberFormatException nfe)
        {
            return false;
        }
        return true;
    }


    public String formatSeconds(double timeInSeconds){
        int secondsLeft = (int) Math.floor(timeInSeconds % 3600 % 60);
        int minutes = (int) Math.floor(timeInSeconds % 3600 / 60);
        int hours = (int) Math.floor(timeInSeconds / 3600);

        String HH = hours < 10 ? "0" + hours : String.valueOf(hours);
        String MM = minutes < 10 ? "0" + minutes : String.valueOf(minutes);
        String SS = secondsLeft < 10 ? "0" + secondsLeft : String.valueOf(secondsLeft);

        return HH + ":" + MM + ":" + SS;
    }

}


//TODO:
//1. The supported media formats are very limited.
//3. When a user resizes the screen, part of the layout will mess up.
//4. Add catch exceptions.
//5. Add volume unify function.
//6. Add encoding detector.

