package com.eyone.ffmpegstudio;

import com.eyone.ffmpegstudio.model.Job;
import com.eyone.ffmpegstudio.model.Preset;
import com.eyone.ffmpegstudio.service.FFmpegRunner;
import com.eyone.ffmpegstudio.service.JobQueueService;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.util.List;

/**
 * UI minimale mais fonctionnelle. Volontairement en code (pas FXML) pour que
 * tout tienne dans un fichier que tu peux lire d'un coup. A decouper en FXML
 * + controller quand ca grossit.
 *
 * Design en couches (le coeur du projet) :
 *  - choix du preset en facade (cas simple, un clic)
 *  - champ "arguments bruts" pour les flags exotiques (acces complet a FFmpeg)
 *  - apercu live de la commande generee (lecture seule)
 */
public class App extends Application {

    // TODO : a remplacer par les binaires embarques via jpackage.
    // Sous Windows : "ffmpeg.exe" / "ffprobe.exe" si dans le PATH ou embarques.
    private static final String FFMPEG = "ffmpeg";
    private static final String FFPROBE = "ffprobe";

    private JobQueueService queue;
    private File selectedFile;

    private final Label fileLabel = new Label("Aucun fichier selectionne");
    private final ComboBox<Preset> presetBox = new ComboBox<>();
    private final TextField extraArgsField = new TextField();
    private final TextArea commandPreview = new TextArea();
    private final TableView<Job> table = new TableView<>();

    @Override
    public void start(Stage stage) {
        queue = new JobQueueService(new FFmpegRunner(FFMPEG, FFPROBE));

        // --- Selection de fichier ---
        Button pickBtn = new Button("Choisir un fichier...");
        pickBtn.setOnAction(e -> pickFile(stage));
        HBox fileRow = new HBox(10, pickBtn, fileLabel);
        fileRow.setAlignment(Pos.CENTER_LEFT);

        // --- Preset ---
        presetBox.getItems().setAll(Preset.values());
        presetBox.getSelectionModel().selectFirst();
        presetBox.valueProperty().addListener((o, a, b) -> updatePreview());

        // --- Arguments bruts (acces "max" a FFmpeg) ---
        extraArgsField.setPromptText("Flags FFmpeg supplementaires (optionnel), ex : -ss 00:00:10 -t 30");
        extraArgsField.textProperty().addListener((o, a, b) -> updatePreview());

        // --- Apercu de la commande ---
        commandPreview.setEditable(false);
        commandPreview.setPrefRowCount(3);
        commandPreview.setWrapText(true);
        commandPreview.setStyle("-fx-font-family: 'monospace';");

        Button addBtn = new Button("Ajouter a la file");
        addBtn.setOnAction(e -> addJob());

        VBox controls = new VBox(8,
                fileRow,
                new Label("Preset :"), presetBox,
                new Label("Options avancees :"), extraArgsField,
                new Label("Commande generee :"), commandPreview,
                addBtn);
        controls.setPadding(new Insets(12));

        // --- Table de la file ---
        buildTable();

        SplitPane root = new SplitPane(controls, table);
        root.setDividerPositions(0.45);

        stage.setScene(new Scene(root, 900, 560));
        stage.setTitle("FFmpeg Studio");
        stage.setOnCloseRequest(e -> queue.shutdown());
        stage.show();
        updatePreview();
    }

    private void pickFile(Stage stage) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Choisir un fichier media");
        File f = fc.showOpenDialog(stage);
        if (f != null) {
            selectedFile = f;
            fileLabel.setText(f.getName());
            updatePreview();
        }
    }

    private File deriveOutput(File source, Preset preset) {
        String name = source.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = preset == Preset.EXTRACT_AUDIO_MP3 ? ".mp3" : ".mp4";
        return new File(source.getParentFile(), base + "_out" + ext);
    }

    private void updatePreview() {
        Preset preset = presetBox.getValue();
        if (selectedFile == null || preset == null) {
            commandPreview.setText("(selectionne un fichier pour voir la commande)");
            return;
        }
        File output = deriveOutput(selectedFile, preset);
        List<String> cmd = preset.buildArgs(FFMPEG, selectedFile, output, extraArgsField.getText());
        commandPreview.setText(String.join(" ", cmd));
    }

    private void addJob() {
        Preset preset = presetBox.getValue();
        if (selectedFile == null || preset == null) {
            new Alert(Alert.AlertType.WARNING, "Choisis d'abord un fichier.").showAndWait();
            return;
        }
        File output = deriveOutput(selectedFile, preset);
        Job job = new Job(selectedFile, output, preset, extraArgsField.getText());
        queue.submit(job);
    }

    @SuppressWarnings("unchecked")
    private void buildTable() {
        TableColumn<Job, String> nameCol = new TableColumn<>("Fichier");
        nameCol.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(c.getValue().getSource().getName()));
        nameCol.setPrefWidth(200);

        TableColumn<Job, Job.Status> statusCol = new TableColumn<>("Statut");
        statusCol.setCellValueFactory(c -> c.getValue().statusProperty());
        statusCol.setPrefWidth(110);

        TableColumn<Job, Number> progCol = new TableColumn<>("Progression");
        progCol.setCellValueFactory(c -> c.getValue().progressProperty());
        progCol.setCellFactory(col -> new TableCell<>() {
            private final ProgressBar bar = new ProgressBar(0);
            @Override protected void updateItem(Number value, boolean empty) {
                super.updateItem(value, empty);
                if (empty || value == null) { setGraphic(null); }
                else { bar.setProgress(value.doubleValue()); setGraphic(bar); }
            }
        });
        progCol.setPrefWidth(140);

        TableColumn<Job, Void> actionCol = new TableColumn<>("");
        actionCol.setCellFactory(col -> new TableCell<>() {
            private final Button cancel = new Button("Annuler");
            {
                cancel.setOnAction(e -> queue.cancel(getTableView().getItems().get(getIndex())));
            }
            @Override protected void updateItem(Void v, boolean empty) {
                super.updateItem(v, empty);
                setGraphic(empty ? null : cancel);
            }
        });
        actionCol.setPrefWidth(90);

        table.getColumns().addAll(nameCol, statusCol, progCol, actionCol);
        table.setItems(queue.getJobs());
    }

    public static void main(String[] args) {
        launch(args);
    }
}
