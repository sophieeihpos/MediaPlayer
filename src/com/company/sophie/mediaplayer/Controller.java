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
import javafx.geometry.Rectangle2D;
import javafx.scene.control.*;
import javafx.scene.control.Button;
import javafx.scene.layout.*;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.scene.text.Text;
import javafx.stage.Screen;
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

    /* @DIVIDERATIO was created for a split panel at the beginning. The divider position changed when the stage was
    resized by user. I tried thinking of binding the divider position to an observable property but realised that I
    can not bind a 'double' to an 'observable'. My temporary solution was to replace the split panel with a border
    panel. Now the constant is used to reserve a certain part of the stage for the media player window.*/
    private static final double DIVIDERRATIO = 0.7;

    private URI mediaURI = null;
    private MediaPlayer mediaPlayer;
    private Media media;
    private URI scriptsURI = null;
    private static final double BUTTON_HEIGHT = 35;

    /* The scripts are usually late for the talking on stage. My intention for using two list views is to start the
    first line from the center of the view, so that I can refer to the lines that have been said before and after the
    current line later. If I use one list view, I will have to find the centre of the screen and the corresponding
    item at that location, which needs more calculation in each loop as well as more listeners for detecting stage
    dimensions change.*/
    private ObservableList<String> topScriptsList;
    private ObservableList<String> bottomScriptsList;

    private boolean isShowScript = false;

    /*The whole script file is read and stored as an array list.

    When the button on the slider is relocated, the scripts
    shown are required to jump to the corresponding new section. I cannot imagine a binary search in the srt file.
    The file can have long scripts spanning through many lines. Reading line by line in a binary search is not reliable
    and takes long. The best solution I can think of is by using a binary search in a better structure.
    The 'collection' class has binary search method already written. The 'HashMap' class shall be a good option.
    However, what I need to search for is which section in the script the current hhmmss is falling in rather than an
    identical. On overriding the 'comparable method' I will need to have the key for the current and next index. This
    will need each set of 'hash map' to store both the start and end time, which takes twice storage space.

    However, this is a big array list.
    I need to carry out research on if there is a better approach for data storage.*/
    private ArrayList<Scripts> scriptsList;
    /*Index of the current text matching progress of the media player*/
    private int scriptIndex;
    private Thread updateListViewsThread;
    private double mediaDuration = 0;
    private int numOfListviewItems;
    private Runnable listViewsRunnable;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        /*Initialise the maximum number of items in the list.

        The text in the item is wrapped so the height of a list cell depends on the length of the text. It makes
        sense to make a list with fixed amount of items. The maximum number of items required would be given by
        assuming each text takes one line only. */
        Screen screen = Screen.getPrimary();
        Rectangle2D bounds = screen.getBounds();
        double screenHeight = bounds.getHeight();
        numOfListviewItems = (int) (screenHeight / BUTTON_HEIGHT / 2);
        mediaView.setPreserveRatio(true);
        mediaStatusLabel.setPadding(new Insets(5, 10, 5, 10));
        progressLabel.setPadding(new Insets(5, 10, 5, 10));

        /*Set text in the labels, otherwise the rest of views move around when this text first appears.*/
        progressLabel.setText(" ");
        mediaStatusLabel.setText(" ");
        playHBox.setPadding(new Insets(5, 10, 20, 10));

        /*Assign observable array lists to the list views. When the items in these lists change the view shall be
        automatically updated.*/
        topScriptsList = FXCollections.observableArrayList();
        bottomScriptsList = FXCollections.observableArrayList();
        topListView.setItems(topScriptsList);
        bottomListView.setItems(bottomScriptsList);

        /*Initialise list views with empty items.*/
        emptyListViews();

        /*The list views are used for showing scripts. Currently, it is forced to focus on the row at the centre. I
        need some research on how to disable the scroll bar and selection function. */
        topScriptsList.addListener(new ListChangeListener<String>() {
            @Override
            public void onChanged(Change<? extends String> c) {
                topListView.scrollTo(numOfListviewItems - 1);
            }
        });
        bottomScriptsList.addListener(new ListChangeListener<String>() {
            @Override
            public void onChanged(Change<? extends String> c) {
                bottomListView.scrollTo(0);
                bottomListView.getSelectionModel().select(0);
            }
        });

        /*Wrap text in the list cell.*/
        Callback<ListView<String>, ListCell<String>> callBack = new Callback<ListView<String>, ListCell<String>>() {
            @Override
            public ListCell<String> call(ListView<String> listView) {
                return new ListCell<String>() {
                    @Override
                    protected void updateItem(String item, boolean empty) {
                        super.updateItem(item, empty);
                        Text text = new Text(item);
                        text.wrappingWidthProperty().bind(getListView().widthProperty().subtract(50));
                        setPrefWidth(0);
                        setGraphic(text);
                        setPadding(new Insets(10, 10, 10, 10));
                    }
                };
            }
        };
        topListView.setCellFactory(callBack);
        bottomListView.setCellFactory(callBack);

        /*The list views are updated every a few seconds to synchronize with the media player. A new thread is
        required for sleeping or scheduled functions. I learnt that the FXML can only be changed via 'control'
        thread, so Platform.runlater() is used to update the views.*/
        listViewsRunnable = new Runnable() {
            final Object synchrObject = new Object();

            @Override
            public void run() {
            /*Synchronised to ensure global variables stay consistent while updating the list views.*/
                synchronized (synchrObject) {
                    long sleepMilliSeconds;
                    double currentTime;
                    while (scriptIndex < scriptsList.size() - 1) {
                        currentTime = mediaPlayer.getCurrentTime().toMillis();
                        sleepMilliSeconds = (long) (scriptsList.get(scriptIndex + 1).getTime() - currentTime);
                        try {
                            updateListViewsThread.sleep(sleepMilliSeconds);
                        } catch (InterruptedException e) {
                            return;
                        }
                        if (!isShowScript) {
                            return;
                        }
                        Platform.runLater(new Runnable() {
                            @Override
                            public void run() {
                                topScriptsList.remove(0);
                                topScriptsList.add(bottomScriptsList.get(0));
                                topListView.refresh();
                                bottomScriptsList.remove(0);
                                int nextScriptIndex = scriptIndex + numOfListviewItems;
                                String nextScript;
                                if (nextScriptIndex <= scriptsList.size()) {
                                    nextScript = scriptsList.get(nextScriptIndex).getText();
                                } else {
                                    nextScript = "";
                                }
                                bottomScriptsList.add(nextScript);
                                bottomListView.refresh();
                                if (!isShowScript) {
                                    return;
                                }
                            }
                        });
                        scriptIndex++;
                    }

                    /*Last section of scripts does not need to call sleep, so assign to empty.*/
                    bottomScriptsList.set(numOfListviewItems - 1, "");
                    topScriptsList.set(numOfListviewItems - 1, "");
                }
            }
        };
    }

    public void initMediaView(Stage stage) {
        /*Scale media size and list view size when the stage is resized by user*/
        mediaView.fitWidthProperty().bind(stage.widthProperty().multiply(DIVIDERRATIO));
        topListView.prefWidthProperty().bind(stage.widthProperty().multiply(1 - DIVIDERRATIO));
        stage.setMaximized(true);
    }

    public void openMediaFile() {
        JFileChooser fileChooser = new JFileChooser();
        int isApproved = fileChooser.showOpenDialog(null);

        if (isApproved == JFileChooser.APPROVE_OPTION) {
            mediaURI = fileChooser.getSelectedFile().toURI();
        }
        if (mediaURI != null) {
            try {
                media = new Media(mediaURI.toString());
            } catch (Exception e) {
                mediaURI = null;
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("Information Dialog");
                alert.setHeaderText(null);
                alert.setContentText("Sorry, the media file selected is not supported !");
                alert.showAndWait();
            }
        }

        if (mediaURI != null) {
            mediaPlayer = new MediaPlayer(media);
            mediaView.setMediaPlayer(mediaPlayer);
            mediaStatusLabel.setText("Status: loading...");

            /*I have learnt that the media player takes time to load the file and become ready, and before that the
            file will not be played.*/
            mediaPlayer.setOnReady(new Runnable() {
                @Override
                public void run() {
                    mediaStatusLabel.setText("Status: ready");
                    mediaDuration = mediaPlayer.getMedia().getDuration().toSeconds();
                    /*Setup the slider. Update the slider progress to be in line with the media player by adding a
                    listener.*/
                    seekSlider.setMax(mediaDuration);
                    mediaPlayer.currentTimeProperty().addListener(new ChangeListener<Duration>() {
                        @Override
                        public void changed(ObservableValue<? extends Duration> observable, Duration oldValue,
                                            Duration newValue) {
                            seekSlider.setValue(newValue.toSeconds());
                            String timeProgress = formatSeconds(newValue.toSeconds());
                            String timeTotal = formatSeconds(mediaDuration);
                            progressLabel.setText(timeProgress + " / " + timeTotal);
                        }
                    });
                }
            });
            /*Auto load the scripts file that has the same file name as the media file.*/
            matchScriptsFile();

            mediaPlayer.setOnEndOfMedia(new Runnable() {
                @Override
                public void run() {
                    mediaStatusLabel.setText("Status: finished");
                }
            });

            /*To make the media file listen to the slider button position, I first tried slider onclick listener as
            it would make sense to detect a click and adjust the media player. However, when I tested the solution,
            it only worked when a user clicked twice. The 'get value' method did not pick up a first click, and I
            suppose it is because it listens to the media player and putting them this way just makes a loop on which
            one starts first.

            So I have changed the listener to be on value property. The media player will only followed if the change
            is greater than 0.5 second. I shall say 0.5 second is a good guess. It is not a perfect solution, but it
            works for now. */
            final double MINIMUM_CHANGE = 0.5;
            seekSlider.valueProperty().addListener(new ChangeListener<Number>() {
                @Override
                synchronized public void changed(ObservableValue<? extends Number> observable, Number oldValue,
                                                 Number newValue) {
                    double change = Math.abs((double) newValue - (double) oldValue);
                    if (change > MINIMUM_CHANGE) {
                        mediaPlayer.seek(Duration.seconds(((double) newValue)));
                        /*I've learnt that a thread can not be restarted, instead only interrupted and die, as they
                        have a life cycle. I can make it wait, but if the thread gets interrupted for any other
                        reason, it is dead. So killing the previous and creating a new thread every time I need to
                        restart updating the list views.*/
                        if (updateListViewsThread.isAlive()) {
                            isShowScript = false;
                            updateListViewsThread.interrupt();
                            startListViewThread();
                        }
                    }
                }
            });
        }

    }

    /*Let the user open srt file. Assign uri. */
    public void openScriptsFile() {
        JFileChooser fileChooser = new JFileChooser();
        int isApproved = fileChooser.showOpenDialog(null);
        if (isApproved == fileChooser.APPROVE_OPTION) {
            String path = fileChooser.getSelectedFile().getPath();
            String fileFormat = path.substring(path.lastIndexOf("."),path.length()) ;
            if (fileFormat==".srt"){
                scriptsURI = fileChooser.getSelectedFile().toURI();
                isShowScript = true;
                try {
                    loadScripts();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }else {
                scriptsURI=null;
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("Information Dialog");
                alert.setHeaderText(null);
                alert.setContentText(" Sorry, this file format is not supported ! Please choose an 'srt' file.");
                alert.showAndWait();
            }

        }

    }
    public void matchScriptsFile() {
        String mediaPath = mediaURI.getPath();
        String scriptsPath = mediaPath.substring(0, mediaPath.lastIndexOf(".")) + ".srt";
        File scriptFile = new File(scriptsPath);
        if (scriptFile.exists()) {
            scriptsURI = URI.create(scriptsPath);
        }
        isShowScript = true;
        try {
            loadScripts();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void playButtonAction() {
        if (mediaURI != null) {
            MediaPlayer.Status status = mediaPlayer.getStatus();
            if (status == MediaPlayer.Status.PLAYING) {
                pause();
            } else {
                play();
            }
        }

    }

    public void play() {
        MediaPlayer.Status status = mediaPlayer.getStatus();
        String playButtonText = playButton.getText();
        if (status == MediaPlayer.Status.PLAYING
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
        } else {
            mediaStatusLabel.setText("Status: unknown");
        }
    }

    public void pause() {
        MediaPlayer.Status status = mediaPlayer.getStatus();
        String playButtonText = playButton.getText();
        if (status == MediaPlayer.Status.PLAYING) {
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
            if (updateListViewsThread.isAlive()) {
                isShowScript = false;
                updateListViewsThread.interrupt();
                emptyListViews();
            }

        }
    }

    public void loadScripts() throws Exception {
        File file;
        Scanner scanner;
        scriptsList = new ArrayList<>();
        double startTime = 0;
        String text = "";
        String currentLine = "";
        String[] timeLine;
        Date tempTime;
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("hh:mm:ss");
        Date refTime = simpleDateFormat.parse("00:00:00");
        file = new File(scriptsURI.getPath());
        scanner = new Scanner(file, "Unicode");
        while (scanner.hasNextLine()) {
            if (currentLine.isEmpty()) {
                currentLine = scanner.nextLine();
            }

            if (isInt(currentLine)) {
                currentLine = scanner.nextLine();
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
                        if (scanner.hasNextLine()) {
                            currentLine = scanner.nextLine();
                        }
                    }
                }
                scriptsList.add(new Scripts(startTime, text));
                text = "";
            }
        }

        if (scanner != null) {
            scanner.close();
        }
        /*Add starting and ending points for binary search used later.*/
        scriptsList.add(0, new Scripts(0, ""));
        scriptsList.add(new Scripts(mediaDuration, ""));
    }

    /*Tells if a string is integer as part of reading a script file*/
    public static boolean isInt(String str) {
        try {
            Integer.parseInt(str);
        } catch (NumberFormatException nfe) {
            return false;
        }
        return true;
    }

    public void startListViewThread() {
        isShowScript = true;
        double seekValue = seekSlider.getValue();
        /*Search for current position in the scripts, -1 is returned if not found.*/
        scriptIndex = findScriptIndex(seekValue);
        if (scriptIndex == -1) {
            return;
        } else {
            initialiseBottomListView(scriptIndex);
            if (updateListViewsThread != null) {
                if (updateListViewsThread.isAlive()) {
                    try {
                        updateListViewsThread.join();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
            updateListViewsThread = new Thread(listViewsRunnable);
            updateListViewsThread.setDaemon(true);
            updateListViewsThread.start();
        }
    }

    public int findScriptIndex(double currentTime) {

        boolean found = false;

        int i = 0;
        int size = scriptsList.size();
        int upperIndex = size - 1;
        int lowerIndex = 1;
        int pointer;
        double pointerStartTime;
        double pointerFinishTime;

        int result = -1;
        currentTime = currentTime * 1000;
        if (currentTime < scriptsList.get(1).getTime()) {
            result = 0;
            found = true;
        } else if (currentTime == mediaDuration * 1000) {
            /*This is when the seeker is at the ending point. There is no more script to show.*/
            result = -1;
        } else {
            while (found == false || i <= Math.ceil(Math.log(size - 1) / Math.log(2))) {
                pointer = (int) Math.floor((lowerIndex + upperIndex) * 0.5);
                pointerStartTime = scriptsList.get(pointer).getTime();
                pointerFinishTime = scriptsList.get(pointer + 1).getTime();
                if (currentTime >= pointerStartTime && currentTime < pointerFinishTime) {
                    result = pointer;
                    found = true;
                } else if (currentTime < pointerStartTime) {
                    upperIndex = pointer;
                } else if (currentTime >= pointerFinishTime) {
                    lowerIndex = pointer + 1;
                } else {
                    result = -1;
                }
                i++;
            }
        }
        return result;
    }

    public void initialiseBottomListView(int startIndex) {
        emptyListViews();
        int size = scriptsList.size() - startIndex;
        if (size >= bottomScriptsList.size()) {
            size = bottomScriptsList.size();
        }
        for (int i = 0; i < size; i++) {
            bottomScriptsList.set(i, scriptsList.get(i + startIndex).getText());
        }
        bottomListView.refresh();
    }

    public void emptyListViews() {
        topScriptsList.clear();
        bottomScriptsList.clear();
        for (int i = 0; i < numOfListviewItems; i++) {
            topScriptsList.add("");
            bottomScriptsList.add("");
        }
    }

    public String formatSeconds(double timeInSeconds) {
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
//2. When a user resizes the screen, part of the layout will mess up.
//3. Add encoding detector (currently using only "Unicode".
//4. I want to make a function to make all voice to play at a consistent loudness. ( The media has high and low
// voices when the volume is set.)
//5. I want to add an auto caption function, and if possible it shall adjust the auto-caption contents ( with text
// errors) and scripts ( with timing and text errors). This will need 'machine learning'.

