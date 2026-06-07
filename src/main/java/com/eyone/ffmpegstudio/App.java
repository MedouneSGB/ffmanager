package com.eyone.ffmpegstudio;

import com.eyone.ffmpegstudio.model.Job;
import com.eyone.ffmpegstudio.model.Preset;
import com.eyone.ffmpegstudio.service.FFmpegRunner;
import com.eyone.ffmpegstudio.service.JobQueueService;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.File;
import java.util.List;
import java.util.prefs.Preferences;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import java.io.OutputStream;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * Interface utilisateur enrichie, esthétique et performante.
 * 
 * Version refondue avec un design sombre premium, options avancées dynamiques,
 * détection d'environnement FFmpeg et panneau de contrôle interactif.
 * Supporte le téléchargement de flux réseau ainsi que la LECTURE DIRECTE (Streaming) intégrée.
 */
public class App extends Application {

    private final Preferences prefs = Preferences.userNodeForPackage(App.class);
    private java.awt.TrayIcon trayIcon;
    private String ffmpegPath;
    private String ffprobePath;

    private JobQueueService queue;
    private FFmpegRunner runner;
    private File selectedFile;
    private File customOutputFile;
    private String suggestedTitleFromExtension = null;

    // Éléments UI Source
    private final RadioButton fileSourceRadio = new RadioButton("Fichier local");
    private final RadioButton urlSourceRadio = new RadioButton("Flux réseau / URL");
    private final Label fileLabel = new Label("Aucun fichier sélectionné");
    private final TextField urlField = new TextField();
    private final Button detectBtn = new Button("🔍 Détecter");
    private final Label customOutputLabel = new Label("Fichier de destination : Non spécifié");

    private final ComboBox<Preset> presetBox = new ComboBox<>();
    
    // Nouveaux contrôles pour les options avancées
    private final ComboBox<String> videoCodecBox = new ComboBox<>();
    private final ComboBox<String> resolutionBox = new ComboBox<>();
    private final ComboBox<String> audioBitrateBox = new ComboBox<>();
    private final Slider crfSlider = new Slider(0, 51, 23);
    private final Label crfValueLabel = new Label("CRF: 23 (Qualité standard)");
    
    private final TextField extraArgsField = new TextField();
    private final TextArea commandPreview = new TextArea();
    private final TableView<Job> table = new TableView<>();
    private final Label ffmpegStatusLabel = new Label();

    // Référence du lecteur de streaming
    private MediaPlayer mediaPlayer;
    private HttpServer localServer;
    private Stage primaryStage;
    private Stage activePlayerStage;

    // Transmuxing local HLS / MP4
    private java.io.File tempHlsDir;
    private java.io.File tempPlayFile;
    private Process activeTransmuxProcess;
    private javafx.concurrent.Task<?> activePrepTask;
    private String activePrepUrl;

    @Override
    public void start(Stage stage) {
        if (checkAndNotifyExistingInstance()) {
            Platform.exit();
            System.exit(0);
            return;
        }
        this.primaryStage = stage;
        // Chargement des chemins FFmpeg
        initFFmpegPaths();
        
        runner = new FFmpegRunner(ffmpegPath, ffprobePath);
        queue = new JobQueueService(runner);
        
        // Démarrer le serveur HTTP local pour l'extension Chrome
        startLocalServer();

        // --- En-tête statut ---
        Label appTitle = new Label("FFmpeg Studio");
        appTitle.getStyleClass().add("title-label");
        try {
            javafx.scene.image.ImageView logoView = new javafx.scene.image.ImageView(
                new javafx.scene.image.Image(getClass().getResourceAsStream("/icon.png"))
            );
            logoView.setFitWidth(28);
            logoView.setFitHeight(28);
            logoView.setPreserveRatio(true);
            appTitle.setGraphic(logoView);
            appTitle.setGraphicTextGap(10);
        } catch (Exception e) {
            appTitle.setText("FFmpeg Studio 🎥");
        }
        
        ffmpegStatusLabel.getStyleClass().add("label");
        Button configBtn = new Button("Configurer...");
        configBtn.getStyleClass().add("btn-secondary");
        configBtn.setOnAction(e -> showPathConfigDialog(stage));
        
        Button themeToggleBtn = new Button("\u2600");
        themeToggleBtn.getStyleClass().add("btn-secondary");
        themeToggleBtn.setOnAction(e -> {
            Parent rootNode = stage.getScene().getRoot();
            if (rootNode.getStyleClass().contains("light-theme")) {
                rootNode.getStyleClass().remove("light-theme");
                themeToggleBtn.setText("\u2600");
            } else {
                rootNode.getStyleClass().add("light-theme");
                themeToggleBtn.setText("\uD83C\uDF19");
            }
        });

        Button logsBtn = new Button("Logs 📋");
        logsBtn.getStyleClass().add("btn-secondary");
        logsBtn.setOnAction(e -> openLogFile());
        
        HBox statusBox = new HBox(12, ffmpegStatusLabel, themeToggleBtn, logsBtn, configBtn);
        statusBox.setAlignment(Pos.CENTER_RIGHT);
        
        BorderPane header = new BorderPane();
        header.setLeft(appTitle);
        header.setRight(statusBox);
        header.setPadding(new Insets(10, 15, 10, 15));
        header.setStyle("-fx-background-color: bg-header; -fx-border-color: transparent transparent border-header transparent; -fx-border-width: 1px;");
        
        updateFFmpegStatusUI();

        // --- Colonne de Gauche (Configuration) ---
        VBox controlsContainer = new VBox(15);
        controlsContainer.setPadding(new Insets(15));
        controlsContainer.setStyle("-fx-background-color: transparent;");
        
        // Card 1 : Fichier Source / URL de Flux
        VBox srcCard = new VBox(8);
        srcCard.getStyleClass().add("card-panel");
        Label srcLabel = new Label("1. SOURCE ET DESTINATION");
        srcLabel.getStyleClass().add("section-label");

        ToggleGroup sourceGroup = new ToggleGroup();
        fileSourceRadio.setToggleGroup(sourceGroup);
        fileSourceRadio.setStyle("-fx-text-fill: text-secondary; -fx-font-weight: bold;");
        urlSourceRadio.setToggleGroup(sourceGroup);
        urlSourceRadio.setSelected(true);
        urlSourceRadio.setStyle("-fx-text-fill: text-secondary; -fx-font-weight: bold;");
        HBox sourceToggleRow = new HBox(15, urlSourceRadio, fileSourceRadio);
        sourceToggleRow.setPadding(new Insets(0, 0, 5, 0));

        // Zone Fichier Local
        Button pickBtn = new Button("Choisir un fichier...");
        pickBtn.getStyleClass().add("btn-secondary");
        pickBtn.setOnAction(e -> pickFile(stage));
        
        Button playBtn = new Button("▶ Lire");
        playBtn.getStyleClass().add("btn-secondary");
        playBtn.setOnAction(e -> playActiveStream(stage));
        
        fileLabel.setStyle("-fx-font-weight: bold;");
        HBox fileRow = new HBox(12, pickBtn, playBtn, fileLabel);
        fileRow.setAlignment(Pos.CENTER_LEFT);
        // Masquage initial de la zone fichier local (car flux réseau est sélectionné par défaut)
        fileRow.setVisible(false);
        fileRow.setManaged(false);

        // Zone URL de Flux
        urlField.setPromptText("Saisir l'URL du flux (m3u8) ou d'une page Web à analyser...");
        urlField.textProperty().addListener((o, a, b) -> {
            suggestedTitleFromExtension = null;
            updateCustomOutputLabel(presetBox.getValue());
            updatePreview();
        });
        
        detectBtn.getStyleClass().add("btn-secondary");
        detectBtn.setOnAction(e -> handleStreamDetection(stage));
        
        HBox urlInputRow = new HBox(8, urlField, detectBtn);
        HBox.setHgrow(urlField, Priority.ALWAYS);
        
        Button saveDestBtn = new Button("Enregistrer sous...");
        saveDestBtn.getStyleClass().add("btn-secondary");
        saveDestBtn.setOnAction(e -> pickCustomOutput(stage));
        
        Button playUrlBtn = new Button("▶ Lire le flux");
        playUrlBtn.getStyleClass().add("btn-secondary");
        playUrlBtn.setOnAction(e -> playActiveStream(stage));
        
        customOutputLabel.setStyle("-fx-font-weight: normal; -fx-text-fill: text-muted;");
        HBox urlOutputRow = new HBox(12, saveDestBtn, playUrlBtn, customOutputLabel);
        urlOutputRow.setAlignment(Pos.CENTER_LEFT);
        VBox urlRow = new VBox(8, urlInputRow, urlOutputRow);

        // Gestion du basculement d'affichage
        sourceGroup.selectedToggleProperty().addListener((o, a, b) -> {
            boolean isFile = (sourceGroup.getSelectedToggle() == fileSourceRadio);
            fileRow.setVisible(isFile);
            fileRow.setManaged(isFile);
            urlRow.setVisible(!isFile);
            urlRow.setManaged(!isFile);
            updateCustomOutputLabel(presetBox.getValue());
            updatePreview();
        });

        srcCard.getChildren().addAll(srcLabel, sourceToggleRow, fileRow, urlRow);
        
        // Card 2 : Preset de Conversion
        VBox presetCard = new VBox(8);
        presetCard.getStyleClass().add("card-panel");
        Label prsLabel = new Label("2. PROFIL DE CONVERSION");
        prsLabel.getStyleClass().add("section-label");
        presetBox.getItems().setAll(Preset.values());
        presetBox.getSelectionModel().selectFirst();
        presetBox.setMaxWidth(Double.MAX_VALUE);
        presetBox.valueProperty().addListener((o, a, b) -> {
            // Déclenchement automatique de la vue URL si l'utilisateur choisit le téléchargement
            if (b == Preset.DOWNLOAD_STREAM) {
                urlSourceRadio.setSelected(true);
            }
            updateControlsState();
            updateCustomOutputLabel(b);
            updatePreview();
        });
        presetCard.getChildren().addAll(prsLabel, presetBox);
        
        // Card 3 : Options Avancées
        VBox advCard = new VBox(10);
        advCard.getStyleClass().add("card-panel");
        Label advLabel = new Label("3. PARAMÈTRES AVANCÉS (SURCHARGE)");
        advLabel.getStyleClass().add("section-label");
        
        GridPane grid = new GridPane();
        grid.setHgap(15);
        grid.setVgap(10);
        
        videoCodecBox.getItems().addAll("Auto", "libx264 (H.264 CPU)", "h264_qsv (H.264 GPU Intel)", "libx265 (H.265 CPU)", "copy (Pas de réencodage)");
        videoCodecBox.getSelectionModel().selectFirst();
        videoCodecBox.setMaxWidth(Double.MAX_VALUE);
        videoCodecBox.valueProperty().addListener((o, a, b) -> {
            updateControlsState();
            updatePreview();
        });
        
        resolutionBox.getItems().addAll("Auto", "1080p (Full HD)", "720p (HD)", "480p (SD)", "360p (Mobile)");
        resolutionBox.getSelectionModel().selectFirst();
        resolutionBox.setMaxWidth(Double.MAX_VALUE);
        resolutionBox.valueProperty().addListener((o, a, b) -> updatePreview());
        
        audioBitrateBox.getItems().addAll("Auto", "320k (Très haute)", "192k (Haute)", "128k (Standard)", "96k (Basse)", "Copy (Pas de réencodage)", "Mute (Couper le son)");
        audioBitrateBox.getSelectionModel().selectFirst();
        audioBitrateBox.setMaxWidth(Double.MAX_VALUE);
        audioBitrateBox.valueProperty().addListener((o, a, b) -> updatePreview());
        
        grid.add(new Label("Codec Vidéo :"), 0, 0);
        grid.add(videoCodecBox, 1, 0);
        grid.add(new Label("Résolution :"), 0, 1);
        grid.add(resolutionBox, 1, 1);
        grid.add(new Label("Audio :"), 0, 2);
        grid.add(audioBitrateBox, 1, 2);
        
        ColumnConstraints col1 = new ColumnConstraints();
        col1.setPercentWidth(30);
        ColumnConstraints col2 = new ColumnConstraints();
        col2.setPercentWidth(70);
        grid.getColumnConstraints().addAll(col1, col2);
        
        VBox crfPane = new VBox(6);
        Label crfTitle = new Label("Qualité d'encodage (CRF / GPU Quality) :");
        crfSlider.setMin(0);
        crfSlider.setMax(51);
        crfSlider.setValue(23);
        crfSlider.setBlockIncrement(1);
        crfValueLabel.setStyle("-fx-text-fill: primary-color; -fx-font-weight: bold;");
        crfSlider.valueProperty().addListener((o, a, b) -> {
            int val = b.intValue();
            String desc = "Qualité standard";
            if (val <= 18) desc = "Excellente qualité / Sans perte";
            else if (val <= 23) desc = "Bonne qualité";
            else if (val <= 28) desc = "Qualité moyenne (compressé)";
            else desc = "Compression forte / Basse qualité";
            crfValueLabel.setText(String.format("CRF: %d (%s)", val, desc));
            updatePreview();
        });
        crfPane.getChildren().addAll(crfTitle, crfSlider, crfValueLabel);
        
        // Arguments bruts additionnels
        VBox extraPane = new VBox(4);
        Label extLabel = new Label("Arguments bruts additionnels :");
        extraArgsField.setPromptText("Ex: -ss 00:00:10 -t 30");
        extraArgsField.textProperty().addListener((o, a, b) -> updatePreview());
        extraPane.getChildren().addAll(extLabel, extraArgsField);
        
        advCard.getChildren().addAll(advLabel, grid, crfPane, extraPane);
        
        // Card 4 : Commande FFmpeg générée
        VBox previewCard = new VBox(8);
        previewCard.getStyleClass().add("card-panel");
        Label cmdLabel = new Label("4. COMMANDE GÉNÉRÉE (APERÇU)");
        cmdLabel.getStyleClass().add("section-label");
        
        commandPreview.setEditable(false);
        commandPreview.setPrefRowCount(3);
        commandPreview.setWrapText(true);
        commandPreview.getStyleClass().add("command-area");
        
        Button copyBtn = new Button("Copier");
        copyBtn.getStyleClass().add("btn-secondary");
        copyBtn.setOnAction(e -> {
            Clipboard clipboard = Clipboard.getSystemClipboard();
            ClipboardContent content = new ClipboardContent();
            content.putString(commandPreview.getText());
            clipboard.setContent(content);
        });
        
        HBox previewHeader = new HBox(cmdLabel);
        Pane spacer = new Pane();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        previewHeader.getChildren().addAll(spacer, copyBtn);
        previewHeader.setAlignment(Pos.CENTER_LEFT);
        
        previewCard.getChildren().addAll(previewHeader, commandPreview);
        
        // Big Action Button
        Button addBtn = new Button("Ajouter le Job à la file");
        addBtn.setMaxWidth(Double.MAX_VALUE);
        addBtn.setPrefHeight(45);
        addBtn.setOnAction(e -> addJob());

        // Configuration du pliage/dépliage interactif des cartes (Accordéon)
        makeCollapsible(srcCard, srcLabel, srcLabel, srcLabel, false);
        makeCollapsible(presetCard, prsLabel, prsLabel, prsLabel, true); // Démarre replié
        makeCollapsible(advCard, advLabel, advLabel, advLabel, true); // Démarre replié
        makeCollapsible(previewCard, previewHeader, cmdLabel, cmdLabel, true); // Démarre replié
        
        controlsContainer.getChildren().addAll(srcCard, presetCard, advCard, previewCard, addBtn);
        
        ScrollPane scrollPane = new ScrollPane(controlsContainer);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        scrollPane.getStyleClass().add("scroll-bar");
        
        // --- Colonne de Droite (File d'attente) ---
        VBox tableContainer = new VBox(10);
        tableContainer.setStyle("-fx-background-color: bg-primary;");
        tableContainer.setPadding(new Insets(15));
        Label tableTitle = new Label("FILE D'ATTENTE SÉQUENTIELLE");
        tableTitle.getStyleClass().add("section-label");
        
        tableContainer.getChildren().addAll(tableTitle, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        
        // --- Assemblage global ---
        buildTable();
        updateControlsState();
        updateCustomOutputLabel(presetBox.getValue());

        SplitPane splitPane = new SplitPane(scrollPane, tableContainer);
        splitPane.setDividerPositions(0.48);
        
        BorderPane mainRoot = new BorderPane();
        mainRoot.setTop(header);
        mainRoot.setCenter(splitPane);

        Scene scene = new Scene(mainRoot, 1024, 680);
        
        // Application du CSS
        String css = getClass().getResource("/style.css").toExternalForm();
        scene.getStylesheets().add(css);
        
        stage.setScene(scene);
        stage.setTitle("FFmpeg Studio 🎥");
        try {
            stage.getIcons().add(new javafx.scene.image.Image(getClass().getResourceAsStream("/icon.png")));
        } catch (Exception e) {
            System.err.println("Impossible de charger l'icône : " + e.getMessage());
        }
        
        setupSystemTray(stage);
        
        stage.show();
        updatePreview();
    }

    private void initFFmpegPaths() {
        // 1. Chemins personnalisés explicitement configurés par l'utilisateur (priorité absolue).
        String savedFf = prefs.get("ffmpegPath", null);
        String savedFp = prefs.get("ffprobePath", null);
        if (savedFf != null && savedFp != null && checkBinaries(savedFf, savedFp)) {
            ffmpegPath = savedFf;
            ffprobePath = savedFp;
            return;
        }

        // 2. Binaires embarqués livrés avec l'application (mode autonome, sans installation).
        String[] bundled = findBundledBinaries();
        if (bundled != null && checkBinaries(bundled[0], bundled[1])) {
            ffmpegPath = bundled[0];
            ffprobePath = bundled[1];
            return;
        }

        // 3. Repli sur le PATH système.
        ffmpegPath = "ffmpeg";
        ffprobePath = "ffprobe";

        if (!checkBinaries(ffmpegPath, ffprobePath)) {
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                String[] commonDirs = {
                    "C:\\ffmpeg\\bin\\",
                    "C:\\Program Files\\ffmpeg\\bin\\",
                    "C:\\Program Files (x86)\\ffmpeg\\bin\\"
                };
                for (String dir : commonDirs) {
                    String ff = dir + "ffmpeg.exe";
                    String fp = dir + "ffprobe.exe";
                    if (checkBinaries(ff, fp)) {
                        ffmpegPath = ff;
                        ffprobePath = fp;
                        prefs.put("ffmpegPath", ff);
                        prefs.put("ffprobePath", fp);
                        break;
                    }
                }
            } else if (os.contains("mac")) {
                String[] commonDirs = {
                    "/opt/homebrew/bin/",
                    "/usr/local/bin/",
                    "/usr/bin/",
                    "/opt/local/bin/"
                };
                for (String dir : commonDirs) {
                    String ff = dir + "ffmpeg";
                    String fp = dir + "ffprobe";
                    if (checkBinaries(ff, fp)) {
                        ffmpegPath = ff;
                        ffprobePath = fp;
                        prefs.put("ffmpegPath", ff);
                        prefs.put("ffprobePath", fp);
                        break;
                    }
                }
            } else { // Linux et autres Unix
                String[] commonDirs = {
                    "/usr/bin/",
                    "/usr/local/bin/",
                    "/bin/"
                };
                for (String dir : commonDirs) {
                    String ff = dir + "ffmpeg";
                    String fp = dir + "ffprobe";
                    if (checkBinaries(ff, fp)) {
                        ffmpegPath = ff;
                        ffprobePath = fp;
                        prefs.put("ffmpegPath", ff);
                        prefs.put("ffprobePath", fp);
                        break;
                    }
                }
            }
        }
    }

    /**
     * Recherche les binaires FFmpeg/ffprobe embarqués à côté de l'application.
     *
     * Avec jpackage, le JAR est déployé dans le dossier {@code app/} de l'image
     * applicative ; le CI y copie également ffmpeg(.exe) et ffprobe(.exe). On
     * localise donc le répertoire du JAR en cours d'exécution et on y cherche
     * les exécutables (ou dans un sous-dossier {@code ffmpeg/} ou {@code bin/}).
     *
     * @return un tableau {ffmpeg, ffprobe} (chemins absolus) ou {@code null}
     *         si aucun binaire embarqué n'est trouvé (ex : lancement en dev).
     */
    private String[] findBundledBinaries() {
        try {
            File codeSource = new File(
                    App.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            File baseDir = codeSource.isDirectory() ? codeSource : codeSource.getParentFile();
            if (baseDir == null) return null;

            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
            String ffName = isWindows ? "ffmpeg.exe" : "ffmpeg";
            String fpName = isWindows ? "ffprobe.exe" : "ffprobe";

            File[] candidateDirs = {
                    baseDir,
                    new File(baseDir, "ffmpeg"),
                    new File(baseDir, "bin")
            };

            for (File dir : candidateDirs) {
                File ff = new File(dir, ffName);
                File fp = new File(dir, fpName);
                if (ff.isFile() && fp.isFile()) {
                    // Sur macOS/Linux les binaires extraits d'un zip perdent souvent
                    // le bit exécutable, et macOS les met en quarantaine Gatekeeper.
                    if (!isWindows) {
                        ff.setExecutable(true, false);
                        fp.setExecutable(true, false);
                        clearMacQuarantine(ff);
                        clearMacQuarantine(fp);
                    }
                    return new String[]{ ff.getAbsolutePath(), fp.getAbsolutePath() };
                }
            }
        } catch (Exception e) {
            System.err.println("[FFmpeg] Détection des binaires embarqués impossible : " + e.getMessage());
        }
        return null;
    }

    /** Retire l'attribut de quarantaine macOS pour qu'un binaire embarqué ne soit pas bloqué par Gatekeeper. */
    private void clearMacQuarantine(File binary) {
        if (!System.getProperty("os.name").toLowerCase().contains("mac")) return;
        try {
            new ProcessBuilder("xattr", "-d", "com.apple.quarantine", binary.getAbsolutePath())
                    .redirectErrorStream(true)
                    .start()
                    .waitFor();
        } catch (Exception ignore) {
            // Best-effort : si l'attribut n'existe pas, xattr renvoie une erreur sans gravité.
        }
    }

    private String sanitizePath(String path) {
        if (path == null) return "";
        path = path.trim();
        if (path.length() >= 2) {
            if ((path.startsWith("\"") && path.endsWith("\"")) || 
                (path.startsWith("'") && path.endsWith("'"))) {
                path = path.substring(1, path.length() - 1);
            }
        }
        return path.trim();
    }

    private boolean checkBinaries(String ffmpeg, String ffprobe) {
        return checkExecutable(ffmpeg) && checkExecutable(ffprobe);
    }

    private boolean checkExecutable(String path) {
        String cleanPath = sanitizePath(path);
        if (cleanPath.isEmpty()) {
            return false;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(cleanPath, "-version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.getOutputStream().close();
            try (java.io.InputStream is = p.getInputStream()) {
                byte[] buf = new byte[1024];
                while (is.read(buf) != -1) {
                    // drain output to avoid SIGPIPE on Mac/Linux
                }
            }
            int exitCode = p.waitFor();
            if (exitCode != 0) {
                System.err.println("[WARN] L'exécutable '" + cleanPath + "' a retourné le code de sortie " + exitCode);
            }
            return exitCode == 0;
        } catch (Exception e) {
            System.err.println("[ERROR] Impossible de démarrer l'exécutable '" + cleanPath + "' : " + e.getMessage());
            e.printStackTrace(System.err);
            return false;
        }
    }

    private void updateFFmpegStatusUI() {
        boolean ffOk = checkExecutable(ffmpegPath);
        boolean fpOk = checkExecutable(ffprobePath);
        
        ffmpegStatusLabel.getStyleClass().clear();
        if (ffOk && fpOk) {
            ffmpegStatusLabel.setText("✓ FFmpeg & ffprobe détectés");
            ffmpegStatusLabel.getStyleClass().addAll("label", "ffmpeg-ok");
        } else if (!ffOk && !fpOk) {
            ffmpegStatusLabel.setText("⚠ FFmpeg & ffprobe non trouvés");
            ffmpegStatusLabel.getStyleClass().addAll("label", "ffmpeg-error");
        } else if (!ffOk) {
            ffmpegStatusLabel.setText("⚠ FFmpeg non trouvé");
            ffmpegStatusLabel.getStyleClass().addAll("label", "ffmpeg-error");
        } else {
            ffmpegStatusLabel.setText("⚠ ffprobe non trouvé");
            ffmpegStatusLabel.getStyleClass().addAll("label", "ffmpeg-error");
        }
    }

    private void showPathConfigDialog(Stage parentStage) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Configuration des chemins FFmpeg");
        dialog.setHeaderText("Spécifiez les chemins absolus vers les exécutables FFmpeg.");
        styleDialog(dialog, parentStage);

        ButtonType saveButtonType = new ButtonType("Enregistrer", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveButtonType, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(15));

        TextField ffmpegField = new TextField(ffmpegPath);
        ffmpegField.setPrefWidth(300);
        Button browseFf = new Button("Parcourir...");
        browseFf.getStyleClass().add("btn-secondary");
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        
        TextField ffprobeField = new TextField(ffprobePath);
        ffprobeField.setPrefWidth(300);
        Button browseFp = new Button("Parcourir...");
        browseFp.getStyleClass().add("btn-secondary");

        ffmpegField.textProperty().addListener((obs, oldVal, newVal) -> {
            String val = sanitizePath(newVal);
            if (!val.isEmpty()) {
                File ffmpegFile = new File(val);
                if (ffmpegFile.isFile()) {
                    File parent = ffmpegFile.getParentFile();
                    if (parent != null) {
                        String probeName = isWindows ? "ffprobe.exe" : "ffprobe";
                        File ffprobeFile = new File(parent, probeName);
                        if (ffprobeFile.exists()) {
                            String currentFp = sanitizePath(ffprobeField.getText());
                            if (currentFp.isEmpty() || currentFp.equals("ffprobe") || currentFp.equals("ffprobe.exe") || !new File(currentFp).exists()) {
                                ffprobeField.setText(ffprobeFile.getAbsolutePath());
                            }
                        }
                    }
                }
            }
        });

        browseFf.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Sélectionner ffmpeg" + (isWindows ? ".exe" : ""));
            if (isWindows) {
                fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Exécutable (*.exe)", "*.exe"));
            } else {
                fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Exécutable", "*"));
            }
            File file = fc.showOpenDialog(dialog.getOwner());
            if (file != null) {
                ffmpegField.setText(file.getAbsolutePath());
            }
        });

        browseFp.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Sélectionner ffprobe" + (isWindows ? ".exe" : ""));
            if (isWindows) {
                fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Exécutable (*.exe)", "*.exe"));
            } else {
                fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Exécutable", "*"));
            }
            File file = fc.showOpenDialog(dialog.getOwner());
            if (file != null) {
                ffprobeField.setText(file.getAbsolutePath());
            }
        });

        grid.add(new Label("Chemin ffmpeg :"), 0, 0);
        grid.add(ffmpegField, 1, 0);
        grid.add(browseFf, 2, 0);

        grid.add(new Label("Chemin ffprobe :"), 0, 1);
        grid.add(ffprobeField, 1, 1);
        grid.add(browseFp, 2, 1);

        dialog.getDialogPane().setContent(grid);

        dialog.showAndWait().ifPresent(response -> {
            if (response == saveButtonType) {
                String ff = sanitizePath(ffmpegField.getText());
                String fp = sanitizePath(ffprobeField.getText());
                
                ffmpegPath = ff;
                ffprobePath = fp;
                prefs.put("ffmpegPath", ff);
                prefs.put("ffprobePath", fp);
                
                runner.setFfmpegPath(ff);
                runner.setFfprobePath(fp);
                
                updateFFmpegStatusUI();
                updatePreview();
            }
        });
    }

    private void updateControlsState() {
        Preset preset = presetBox.getValue();
        String codec = videoCodecBox.getValue();
        
        boolean isAudioOnly = (preset == Preset.EXTRACT_AUDIO_MP3);
        boolean isCopy = "copy (Pas de réencodage)".equals(codec) || 
                         (preset == Preset.REMUX_MP4 && "Auto".equals(codec)) ||
                         (preset == Preset.DOWNLOAD_STREAM && "Auto".equals(codec));
        
        videoCodecBox.setDisable(isAudioOnly);
        resolutionBox.setDisable(isAudioOnly || isCopy);
        crfSlider.setDisable(isAudioOnly || isCopy);
        crfValueLabel.setDisable(isAudioOnly || isCopy);
    }

    private void pickFile(Stage stage) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Choisir un fichier média");
        File f = fc.showOpenDialog(stage);
        if (f != null) {
            selectedFile = f;
            fileLabel.setText(f.getName());
            updatePreview();
        }
    }

    private void pickCustomOutput(Stage stage) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Spécifier le fichier de sortie");
        Preset preset = presetBox.getValue();
        String ext = preset == Preset.EXTRACT_AUDIO_MP3 ? "*.mp3" : "*.mp4";
        String desc = preset == Preset.EXTRACT_AUDIO_MP3 ? "Audio MP3" : "Vidéo MP4";
        fc.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter(desc, ext),
            new FileChooser.ExtensionFilter("Tous les fichiers", "*.*")
        );
        File f = fc.showSaveDialog(stage);
        if (f != null) {
            customOutputFile = f;
            updateCustomOutputLabel(preset);
            updatePreview();
        }
    }

    private void updateCustomOutputLabel(Preset preset) {
        if (customOutputFile != null) {
            customOutputLabel.setText(getShortPath(customOutputFile.getAbsolutePath()));
        } else {
            File def = getOutputFile(preset);
            if (def != null) {
                customOutputLabel.setText(getShortPath(def.getAbsolutePath()) + " (Par défaut)");
            } else {
                customOutputLabel.setText("Fichier de destination : Non spécifié");
            }
        }
    }

    private String getShortPath(String absolutePath) {
        if (absolutePath == null) return "";
        String userHome = System.getProperty("user.home");
        if (absolutePath.startsWith(userHome)) {
            String relative = absolutePath.substring(userHome.length());
            if (relative.startsWith(java.io.File.separator + "Downloads")) {
                return relative;
            }
            return "~" + relative;
        }
        return absolutePath;
    }

    private File deriveOutput(File source, Preset preset) {
        String name = source.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = preset == Preset.EXTRACT_AUDIO_MP3 ? ".mp3" : ".mp4";
        return new File(source.getParentFile(), base + "_out" + ext);
    }

    private String getSourceInput() {
        if (fileSourceRadio.isSelected()) {
            return selectedFile == null ? null : selectedFile.getAbsolutePath();
        } else {
            String url = urlField.getText().trim();
            if (url.isEmpty()) return null;
            
            // Si c'est un flux Twitter/X de type variante HLS, on tente de reconstruire le master playlist
            // pour récupérer le son
            if (url.contains("video.twimg.com")) {
                java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                        "(https://video\\.twimg\\.com/(?:ext_tw_video|amplify_video)/[^/]+/(?:pu/pl|vid)/)avc1/[^/]+/([^/]+\\.m3u8)");
                java.util.regex.Matcher m = p.matcher(url);
                if (m.find()) {
                    String masterUrl = m.group(1) + m.group(2);
                    System.out.println("[URL] Substitution automatique de l'URL variante Twitter par son Master Playlist : " + masterUrl);
                    return masterUrl;
                }
            }
            return url;
        }
    }

    private File getOutputFile(Preset preset) {
        if (fileSourceRadio.isSelected()) {
            return selectedFile == null ? null : deriveOutput(selectedFile, preset);
        } else {
            if (customOutputFile != null) {
                return customOutputFile;
            }
            // Dossier de téléchargements par défaut si flux URL
            String ext = preset == Preset.EXTRACT_AUDIO_MP3 ? ".mp3" : ".mp4";
            String userHome = System.getProperty("user.home");
            File downloads = new File(userHome, "Downloads");
            if (!downloads.exists()) {
                downloads = new File(userHome);
            }
            String baseName = "video";
            if (suggestedTitleFromExtension != null && !suggestedTitleFromExtension.isEmpty()) {
                baseName = suggestedTitleFromExtension;
            }
            return new File(downloads, baseName + ext);
        }
    }

    private String getSelectedVideoCodec() {
        String val = videoCodecBox.getValue();
        if (val == null || val.startsWith("Auto")) return "Auto";
        if (val.contains("libx264")) return "libx264";
        if (val.contains("h264_qsv")) return "h264_qsv";
        if (val.contains("libx265")) return "libx265";
        if (val.contains("copy")) return "copy";
        return "Auto";
    }

    private Integer getSelectedCrf() {
        if (crfSlider.isDisable()) return null;
        return (int) crfSlider.getValue();
    }

    private String getSelectedResolution() {
        String val = resolutionBox.getValue();
        if (val == null || val.startsWith("Auto")) return "Auto";
        if (val.contains("1080p")) return "1080p";
        if (val.contains("720p")) return "720p";
        if (val.contains("480p")) return "480p";
        if (val.contains("360p")) return "360p";
        return "Auto";
    }

    private String getSelectedAudioBitrate() {
        String val = audioBitrateBox.getValue();
        if (val == null || val.startsWith("Auto")) return "Auto";
        if (val.contains("320k")) return "320k";
        if (val.contains("192k")) return "192k";
        if (val.contains("128k")) return "128k";
        if (val.contains("96k")) return "96k";
        if (val.contains("Copy")) return "Copy";
        if (val.contains("Mute")) return "Mute";
        return "Auto";
    }

    /**
     * Initialise et ouvre la fenêtre du lecteur vidéo pour le flux ou le fichier sélectionné.
     */
    private void playActiveStream(Stage parentStage) {
        String source = getSourceInput();
        if (source == null) {
            Alert alert = new Alert(Alert.AlertType.WARNING, "Veuillez sélectionner un fichier ou saisir une URL de flux.");
            styleDialog(alert, parentStage);
            alert.showAndWait();
            return;
        }
        playMedia(source, parentStage);
    }

    private void playMedia(String source, Stage parentStage) {
        String requestedUrl = source;
        if (!source.startsWith("http://") && !source.startsWith("https://")) {
            requestedUrl = new File(source).toURI().toString();
        }

        // 1. Détecter si la vidéo est déjà en cours de lecture
        if (mediaPlayer != null && activePlayerStage != null) {
            String currentSource = mediaPlayer.getMedia().getSource();
            if (isSameBaseUrl(currentSource, requestedUrl) || (source.contains(".m3u8") && currentSource.contains("local-hls/playlist.m3u8"))) {
                Platform.runLater(() -> {
                    if (activePlayerStage != null) {
                        activePlayerStage.show();
                        activePlayerStage.toFront();
                        activePlayerStage.setIconified(false);
                    }
                });
                System.out.println("[PLAY] Le flux ou la vidéo est déjà en cours de lecture. Fenêtre ramenée au premier plan.");
                return;
            }
        }

        // 2. Détecter si le flux est déjà en cours de préparation active (évite les spams de requêtes)
        if (activePrepTask != null && activePrepUrl != null) {
            if (isSameBaseUrl(activePrepUrl, requestedUrl)) {
                System.out.println("[PLAY] Le flux est déjà en cours de préparation. Nouvelle requête identique ignorée.");
                return;
            }
        }

        cleanupTransmux();
        if (source.startsWith("http://") || source.startsWith("https://")) {
            Alert loadingAlert = new Alert(Alert.AlertType.INFORMATION);
            loadingAlert.setTitle("Préparation de la lecture");
            loadingAlert.setHeaderText("Connexion et préparation du flux vidéo...");
            loadingAlert.setContentText("Veuillez patienter pendant le chargement de la vidéo...");
            styleDialog(loadingAlert, parentStage);
            
            ProgressBar pbIndicator = new ProgressBar(0);
            pbIndicator.setMaxWidth(Double.MAX_VALUE);
            Label statusLabel = new Label("Initialisation...");
            VBox contentBox = new VBox(10, statusLabel, pbIndicator);
            contentBox.setPadding(new Insets(10));
            loadingAlert.getDialogPane().setContent(contentBox);
            
            javafx.concurrent.Task<File> prepTask = new javafx.concurrent.Task<>() {
                @Override
                protected File call() throws Exception {
                    tempPlayFile = File.createTempFile("ffmpeg-studio-play", ".mp4");
                    tempPlayFile.deleteOnExit();
                    
                    // Si c'est un flux Twitter/X de type variante HLS, on tente de reconstruire le master playlist
                    // pour récupérer le son
                    String targetSource = source;
                    if (source.contains("video.twimg.com")) {
                        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                                "(https://video\\.twimg\\.com/(?:ext_tw_video|amplify_video)/[^/]+/(?:pu/pl|vid)/)avc1/[^/]+/([^/]+\\.m3u8)");
                        java.util.regex.Matcher m = p.matcher(source);
                        if (m.find()) {
                            targetSource = m.group(1) + m.group(2);
                            System.out.println("[PLAY] Substitution automatique de l'URL Twitter par son Master Playlist : " + targetSource);
                        }
                    }

                    FFmpegRunner runner = new FFmpegRunner(ffmpegPath, ffprobePath);
                    Job dummyJob = new Job(targetSource, tempPlayFile, Preset.REMUX_MP4, "");
                    
                    int exitCode = runner.run(dummyJob, (frac, msg) -> {
                        updateProgress(frac, 1.0);
                        updateMessage("Téléchargement : " + msg);
                    });
                    
                    if (exitCode != 0) {
                        throw new java.io.IOException("FFmpeg a échoué avec le code " + exitCode);
                    }
                    
                    return tempPlayFile;
                }
            };
            
            pbIndicator.progressProperty().bind(prepTask.progressProperty());
            statusLabel.textProperty().bind(prepTask.messageProperty());
            
            prepTask.setOnSucceeded(evt -> {
                loadingAlert.close();
                if (activePrepTask != prepTask) return;
                activePrepUrl = null;
                activePrepTask = null;
                File localFile = prepTask.getValue();
                openMediaPlayerStage(localFile.toURI().toString(), parentStage, true); // true = déclenchera cleanupTransmux() à la fermeture
            });
            
            prepTask.setOnFailed(evt -> {
                loadingAlert.close();
                if (activePrepTask != prepTask) return;
                activePrepUrl = null;
                activePrepTask = null;
                Throwable ex = prepTask.getException();
                cleanupTransmux();
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle("Erreur de Lecture");
                alert.setHeaderText("Impossible d'ouvrir ce flux vidéo.");
                alert.setContentText("FFmpeg n'a pas pu télécharger ou préparer le flux.\n\nDétail : " + (ex != null ? ex.getMessage() : "Inconnu"));
                styleDialog(alert, parentStage);
                alert.showAndWait();
            });
            
            prepTask.setOnCancelled(evt -> {
                loadingAlert.close();
                if (activePrepTask == prepTask) {
                    activePrepUrl = null;
                    activePrepTask = null;
                }
                cleanupTransmux();
            });
            
            activePrepUrl = requestedUrl;
            activePrepTask = prepTask;
            
            Thread th = new Thread(prepTask, "stream-prep-thread");
            th.setDaemon(true);
            th.start();
            
            loadingAlert.setOnCloseRequest(e -> {
                prepTask.cancel(true);
                cleanupTransmux();
            });
            
            loadingAlert.show();
            return;
        }
        
        // Fichier local, on l'ouvre directement
        String mediaUrl = new File(source).toURI().toString();
        openMediaPlayerStage(mediaUrl, parentStage, false);
    }

    private void openMediaPlayerStage(String mediaUrl, Stage parentStage, boolean isLocalHls) {
        try {
            disposeMediaPlayer();

            Media media = new Media(mediaUrl);
            media.setOnError(() -> {
                Throwable t = media.getError();
                System.err.println("[ERROR] Erreur du Media JavaFX : " + (t != null ? t.getMessage() : "Inconnue"));
                if (t != null) {
                    t.printStackTrace(System.err);
                }
            });

            mediaPlayer = new MediaPlayer(media);
            final MediaPlayer player = mediaPlayer;
            MediaView mediaView = new MediaView(player);

            Stage playerStage = new Stage();
            activePlayerStage = playerStage;
            playerStage.initOwner(parentStage);
            playerStage.setTitle("Lecteur de Flux - FFmpeg Studio 🎥");
            try {
                playerStage.getIcons().add(new javafx.scene.image.Image(getClass().getResourceAsStream("/icon.png")));
            } catch (Exception e) {
                // Ignorer
            }
            playerStage.setResizable(true);
            playerStage.setMinWidth(550);  // Fixe une largeur minimale de sécurité
            playerStage.setMinHeight(300); // Fixe une hauteur minimale de sécurité

            StackPane videoPane = new StackPane(mediaView);
            videoPane.setStyle("-fx-background-color: #000000;");
            videoPane.setMinSize(0, 0); // Permet de rétrécir sous la taille d'origine de la vidéo
            
            // Double-clic pour basculer le plein écran
            videoPane.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2) {
                    playerStage.setFullScreen(!playerStage.isFullScreen());
                }
            });
            
            // Lier les dimensions de la vidéo à la fenêtre
            mediaView.fitWidthProperty().bind(videoPane.widthProperty());
            mediaView.fitHeightProperty().bind(videoPane.heightProperty());
            mediaView.setPreserveRatio(true);

            // Barre de contrôles flottante premium
            HBox controls = new HBox(12);
            controls.setAlignment(Pos.CENTER);
            controls.setPadding(new Insets(10, 15, 10, 15));
            controls.setStyle("-fx-background-color: rgba(26, 26, 36, 0.85); -fx-border-color: #2b2b36; -fx-border-width: 1px; -fx-background-radius: 8px; -fx-border-radius: 8px;");
            controls.setMinHeight(HBox.USE_PREF_SIZE); // Évite que les contrôles ne soient écrasés lors du resize vertical
            controls.setMaxHeight(HBox.USE_PREF_SIZE); // Empêche le StackPane d'étirer verticalement les contrôles

            Button playPauseBtn = new Button("⏸");
            playPauseBtn.getStyleClass().add("btn-secondary");
            playPauseBtn.setOnAction(e -> {
                if (player.getStatus() == MediaPlayer.Status.PLAYING) {
                    player.pause();
                    playPauseBtn.setText("▶");
                } else {
                    player.play();
                    playPauseBtn.setText("⏸");
                }
            });

            Label timeLabel = new Label("00:00 / 00:00");
            timeLabel.setStyle("-fx-text-fill: #e1e1e6; -fx-font-family: monospace;");

            Slider timeSlider = new Slider();
            HBox.setHgrow(timeSlider, Priority.ALWAYS);
            timeSlider.getStyleClass().add("slider");
            timeSlider.setMin(0);

            // Volume
            Label volLabel = new Label("🔊");
            Slider volSlider = new Slider(0, 1, 0.5);
            volSlider.setPrefWidth(80);
            volSlider.getStyleClass().add("slider");
            player.setVolume(0.5);
            volSlider.valueProperty().addListener((o, a, b) -> {
                player.setVolume(b.doubleValue());
                volLabel.setText(b.doubleValue() == 0 ? "🔇" : "🔊");
            });

            // Bouton Plein Écran
            Button fsBtn = new Button("⛶");
            fsBtn.getStyleClass().add("btn-secondary");
            fsBtn.setOnAction(e -> playerStage.setFullScreen(!playerStage.isFullScreen()));

            controls.getChildren().addAll(playPauseBtn, timeSlider, timeLabel, volLabel, volSlider, fsBtn);

            // Événement d'état Prêt
            player.setOnReady(() -> {
                Duration duration = player.getTotalDuration();
                if (duration.isUnknown() || duration.isIndefinite()) {
                    timeSlider.setDisable(true);
                    timeLabel.setText("Direct / Live");
                } else {
                    timeSlider.setMax(duration.toSeconds());
                    timeSlider.setDisable(false);
                }
                
                double mediaW = media.getWidth();
                double mediaH = media.getHeight();
                double targetW = 854; // Taille par défaut de base
                double targetH = 480;
                
                if (mediaW > 0 && mediaH > 0) {
                    double ratio = mediaW / mediaH;
                    // Limiter la taille par défaut pour éviter d'ouvrir une fenêtre géante
                    if (mediaW > 960 || mediaH > 540) {
                        if (ratio > 960.0 / 540.0) {
                            targetW = 960;
                            targetH = 960 / ratio;
                        } else {
                            targetH = 540;
                            targetW = 540 * ratio;
                        }
                    } else {
                        targetW = Math.max(640, mediaW);
                        targetH = Math.max(360, mediaH);
                    }
                }
                
                playerStage.setWidth(targetW);
                playerStage.setHeight(targetH + 90); // 90px de marge pour les contrôles et les bordures de fenêtre
            });

            // Événement d'Erreur
            player.setOnError(() -> {
                Throwable t = player.getError();
                String err = t != null ? t.getMessage() : "Inconnue";
                System.err.println("[ERROR] Erreur du MediaPlayer JavaFX : " + err);
                if (t != null) {
                    t.printStackTrace(System.err);
                }
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("Erreur de Lecture");
                    alert.setHeaderText("Impossible de lire ce flux vidéo.");
                    alert.setContentText("Détail : " + err + "\n\nAssurez-vous que le flux est valide, actif et décodable nativement par le système (H.264/AAC).");
                    styleDialog(alert, playerStage);
                    alert.showAndWait();
                    playerStage.close();
                });
            });

            // Mettre à jour le temps de lecture
            player.currentTimeProperty().addListener((o, oldTime, newTime) -> {
                if (!timeSlider.isPressed() && !timeSlider.isValueChanging()) {
                    timeSlider.setValue(newTime.toSeconds());
                }
                updateTimeLabel(timeLabel, newTime, player.getTotalDuration());
            });

            // Recherche (Seek) lors d'un clic direct ou d'un glissement
            timeSlider.setOnMousePressed(e -> player.pause());
            timeSlider.setOnMouseReleased(e -> {
                player.seek(Duration.seconds(timeSlider.getValue()));
                if (playPauseBtn.getText().equals("⏸")) {
                    player.play();
                }
            });
            timeSlider.setOnMouseClicked(e -> {
                player.seek(Duration.seconds(timeSlider.getValue()));
            });

            StackPane playerLayout = new StackPane();
            playerLayout.getChildren().addAll(videoPane, controls);
            StackPane.setAlignment(controls, Pos.BOTTOM_CENTER);
            StackPane.setMargin(controls, new Insets(0, 15, 15, 15));

            Scene scene = new Scene(playerLayout, 640, 420);
            scene.getStylesheets().add(getClass().getResource("/style.css").toExternalForm());

            // Timer d'inactivité de la souris (2.5 secondes)
            PauseTransition idleTimeout = new PauseTransition(Duration.seconds(2.5));

            // Animation de disparition progressive (Fade Out)
            FadeTransition fadeOut = new FadeTransition(Duration.millis(350), controls);
            fadeOut.setFromValue(1.0);
            fadeOut.setToValue(0.0);
            fadeOut.setOnFinished(evt -> {
                controls.setVisible(false);
                scene.setCursor(Cursor.NONE);
            });

            // Animation d'apparition progressive (Fade In)
            FadeTransition fadeIn = new FadeTransition(Duration.millis(250), controls);
            fadeIn.setToValue(1.0);

            // Fonctions utilitaires pour afficher/masquer les contrôles
            Runnable showControls = () -> {
                if (fadeOut.getStatus() == Animation.Status.RUNNING) {
                    fadeOut.stop();
                }
                if (!controls.isVisible() || controls.getOpacity() < 1.0) {
                    controls.setVisible(true);
                    fadeIn.setFromValue(controls.getOpacity());
                    fadeIn.playFromStart();
                }
                scene.setCursor(Cursor.DEFAULT);
            };

            Runnable hideControls = () -> {
                if (player.getStatus() == MediaPlayer.Status.PLAYING && !controls.isHover()) {
                    if (fadeIn.getStatus() == Animation.Status.RUNNING) {
                        fadeIn.stop();
                    }
                    fadeOut.setFromValue(controls.getOpacity());
                    fadeOut.playFromStart();
                } else if (controls.isHover() && player.getStatus() == MediaPlayer.Status.PLAYING) {
                    idleTimeout.playFromStart(); // Relancer le timer
                }
            };

            idleTimeout.setOnFinished(evt -> hideControls.run());

            // Événements de souris sur le layout du lecteur
            playerLayout.setOnMouseMoved(e -> {
                showControls.run();
                if (player.getStatus() == MediaPlayer.Status.PLAYING) {
                    idleTimeout.playFromStart();
                } else {
                    idleTimeout.stop();
                }
            });

            playerLayout.setOnMouseDragged(e -> {
                showControls.run();
                if (player.getStatus() == MediaPlayer.Status.PLAYING) {
                    idleTimeout.playFromStart();
                } else {
                    idleTimeout.stop();
                }
            });

            playerLayout.setOnMouseExited(e -> {
                if (player.getStatus() == MediaPlayer.Status.PLAYING) {
                    hideControls.run();
                }
            });

            // Écouter le statut du lecteur pour forcer l'affichage ou démarrer le timer
            player.statusProperty().addListener((obs, oldStatus, newStatus) -> {
                if (newStatus == MediaPlayer.Status.PLAYING) {
                    idleTimeout.playFromStart();
                } else {
                    idleTimeout.stop();
                    showControls.run();
                }
            });

            // Raccourcis clavier (Espace = Play/Pause, M = Muet, F = Plein Écran)
            scene.setOnKeyPressed(keyEvent -> {
                switch (keyEvent.getCode()) {
                    case SPACE -> playPauseBtn.fire();
                    case M -> {
                        player.setMute(!player.isMute());
                        volLabel.setText(player.isMute() ? "🔇" : "🔊");
                    }
                    case F -> fsBtn.fire();
                    default -> {}
                }
            });

            playerStage.setScene(scene);

            playerStage.setOnCloseRequest(e -> {
                idleTimeout.stop();
                fadeOut.stop();
                fadeIn.stop();
                activePlayerStage = null;
                disposeMediaPlayer();
                if (isLocalHls) {
                    cleanupTransmux();
                }
            });

            playerStage.show();
            player.play();

        } catch (Exception ex) {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Erreur");
            alert.setHeaderText("Impossible d'initialiser le lecteur vidéo.");
            alert.setContentText(ex.getMessage());
            styleDialog(alert, parentStage);
            alert.showAndWait();
        }
    }

    /**
     * Gère la détection de flux de manière asynchrone à partir d'une page Web.
     */
    private void handleStreamDetection(Stage parentStage) {
        String inputUrl = urlField.getText().trim();
        if (inputUrl.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.WARNING, "Veuillez saisir l'URL d'une page web à analyser.");
            styleDialog(alert, parentStage);
            alert.showAndWait();
            return;
        }

        // Si l'utilisateur a déjà collé un lien de flux direct, pas besoin d'analyse
        if (inputUrl.endsWith(".m3u8") || inputUrl.endsWith(".mp4") || inputUrl.endsWith(".mpd") || inputUrl.endsWith(".webm") || inputUrl.contains(".m3u8?")) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Flux Direct Détecté");
            alert.setHeaderText("Cette URL est déjà un flux direct !");
            styleDialog(alert, parentStage);
            alert.getDialogPane().setPrefWidth(550);
            
            VBox content = new VBox(12);
            content.setPadding(new Insets(10, 0, 10, 0));
            
            Label infoLabel = new Label("Cette URL est déjà un lien direct de vidéo. Vous pouvez la lancer immédiatement :");
            infoLabel.setWrapText(true);
            
            TextField urlDisplay = new TextField(inputUrl);
            urlDisplay.setEditable(false);
            urlDisplay.getStyleClass().add("text-input");
            urlDisplay.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 11px;");
            HBox.setHgrow(urlDisplay, Priority.ALWAYS);
            
            content.getChildren().addAll(infoLabel, urlDisplay);
            alert.getDialogPane().setContent(content);
            
            ButtonType lancerBtnType = new ButtonType("Lancer", ButtonBar.ButtonData.OK_DONE);
            alert.getDialogPane().getButtonTypes().add(0, lancerBtnType);
            
            alert.showAndWait().ifPresent(response -> {
                if (response == lancerBtnType) {
                    Platform.runLater(() -> playActiveStream(parentStage));
                }
            });
            return;
        }

        // Configurer le chargement visuel
        detectBtn.setDisable(true);
        detectBtn.setText("Analyse...");

        javafx.concurrent.Task<List<com.eyone.ffmpegstudio.service.StreamDetector.DetectedStream>> task = new javafx.concurrent.Task<>() {
            @Override
            protected List<com.eyone.ffmpegstudio.service.StreamDetector.DetectedStream> call() throws Exception {
                return com.eyone.ffmpegstudio.service.StreamDetector.detectStreams(inputUrl);
            }
        };

        task.setOnSucceeded(evt -> {
            detectBtn.setDisable(false);
            detectBtn.setText("🔍 Détecter");
            List<com.eyone.ffmpegstudio.service.StreamDetector.DetectedStream> results = task.getValue();
            if (results == null || results.isEmpty()) {
                Alert alert = new Alert(Alert.AlertType.WARNING, "Aucun flux vidéo (.m3u8, .mp4, etc.) n'a été détecté sur cette page.");
                styleDialog(alert, parentStage);
                alert.showAndWait();
            } else if (results.size() == 1) {
                com.eyone.ffmpegstudio.service.StreamDetector.DetectedStream stream = results.get(0);
                urlField.setText(stream.getUrl());
                
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("Flux Détecté");
                alert.setHeaderText("Flux vidéo extrait avec succès !");
                styleDialog(alert, parentStage);
                alert.getDialogPane().setPrefWidth(550);
                
                // Amélioration de l'IHM pour une source unique
                VBox content = new VBox(12);
                content.setPadding(new Insets(10, 0, 10, 0));
                
                Label qualityLabel = new Label("Format / Qualité détecté :");
                qualityLabel.setStyle("-fx-font-weight: bold;");
                
                Label qualityValue = new Label(stream.getQuality());
                qualityValue.getStyleClass().clear();
                qualityValue.getStyleClass().addAll("badge", "badge-termine"); // badge vert
                
                HBox qualityRow = new HBox(10, qualityLabel, qualityValue);
                qualityRow.setAlignment(Pos.CENTER_LEFT);
                
                Label urlLabel = new Label("Lien du flux :");
                urlLabel.setStyle("-fx-font-weight: bold;");
                
                TextField urlDisplay = new TextField(stream.getUrl());
                urlDisplay.setEditable(false);
                urlDisplay.getStyleClass().add("text-input");
                urlDisplay.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 11px;");
                HBox.setHgrow(urlDisplay, Priority.ALWAYS);
                
                content.getChildren().addAll(qualityRow, urlLabel, urlDisplay);
                alert.getDialogPane().setContent(content);
                
                ButtonType lancerBtnType = new ButtonType("Lancer", ButtonBar.ButtonData.OK_DONE);
                alert.getDialogPane().getButtonTypes().add(0, lancerBtnType);
                
                alert.showAndWait().ifPresent(response -> {
                    if (response == lancerBtnType) {
                        Platform.runLater(() -> playActiveStream(parentStage));
                    }
                });
            } else {
                // Plus de 1 résultat : proposer un dialogue de choix
                ChoiceDialog<com.eyone.ffmpegstudio.service.StreamDetector.DetectedStream> dialog = new ChoiceDialog<>(results.get(0), results);
                dialog.setTitle("Flux Détectés");
                dialog.setHeaderText("Plusieurs flux ou qualités vidéo ont été trouvés.\nChoisissez le flux à charger dans l'application :");
                dialog.setContentText("Qualité :");
                styleDialog(dialog, parentStage);
                dialog.getDialogPane().setPrefWidth(550);
                
                ButtonType lancerBtnType = new ButtonType("Lancer", ButtonBar.ButtonData.OK_DONE);
                dialog.getDialogPane().getButtonTypes().add(0, lancerBtnType);
                
                final boolean[] shouldPlay = {false};
                dialog.setResultConverter(buttonType -> {
                    if (buttonType == ButtonType.OK) {
                        return dialog.getSelectedItem();
                    } else if (buttonType == lancerBtnType) {
                        shouldPlay[0] = true;
                        return dialog.getSelectedItem();
                    }
                    return null;
                });
                
                dialog.showAndWait().ifPresent(selectedStream -> {
                    urlField.setText(selectedStream.getUrl());
                    if (shouldPlay[0]) {
                        Platform.runLater(() -> playActiveStream(parentStage));
                    }
                });
            }
        });

        task.setOnFailed(evt -> {
            detectBtn.setDisable(false);
            detectBtn.setText("🔍 Détecter");
            Throwable ex = task.getException();
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Erreur de Détection");
            alert.setHeaderText("Impossible d'analyser la page.");
            alert.setContentText(ex != null ? ex.getMessage() : "Erreur inconnue");
            styleDialog(alert, parentStage);
            alert.showAndWait();
        });

        new Thread(task).start();
    }

    private void updateTimeLabel(Label label, Duration current, Duration total) {
        if (total.isUnknown() || total.isIndefinite()) {
            label.setText("Direct / Live");
            return;
        }
        label.setText(formatTime(current) + " / " + formatTime(total));
    }

    private String formatTime(Duration duration) {
        int seconds = (int) duration.toSeconds();
        int h = seconds / 3600;
        int m = (seconds % 3600) / 60;
        int s = seconds % 60;
        if (h > 0) {
            return String.format("%d:%02d:%02d", h, m, s);
        }
        return String.format("%02d:%02d", m, s);
    }

    private void updatePreview() {
        Preset preset = presetBox.getValue();
        String source = getSourceInput();
        if (source == null || preset == null) {
            commandPreview.setText("(sélectionne un fichier ou saisit une URL pour voir la commande)");
            return;
        }
        File output = getOutputFile(preset);
        if (output == null) {
            commandPreview.setText("(spécifie l'emplacement de sortie)");
            return;
        }
        List<String> cmd = preset.buildArgs(
                ffmpegPath, source, output,
                getSelectedVideoCodec(), getSelectedCrf(), getSelectedResolution(), getSelectedAudioBitrate(),
                extraArgsField.getText()
        );
        commandPreview.setText(toShellCommand(cmd));
    }

    private String toShellCommand(List<String> cmd) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cmd.size(); i++) {
            String arg = cmd.get(i);
            if (i > 0) sb.append(" ");
            
            // Check if argument needs quoting (contains spaces, &, ?, *, |, $, etc.)
            boolean needsQuotes = arg.contains(" ") || arg.contains("&") || arg.contains("?") || 
                                  arg.contains("*") || arg.contains("|") || arg.contains("$") || 
                                  arg.contains("<") || arg.contains(">") || arg.contains(";") ||
                                  arg.contains("(") || arg.contains(")");
            
            if (needsQuotes) {
                // Escape single quotes if any (standard Bourne shell escaping)
                String escaped = arg.replace("'", "'\\''");
                sb.append("'").append(escaped).append("'");
            } else {
                sb.append(arg);
            }
        }
        return sb.toString();
    }

    private boolean isSameBaseUrl(String url1, String url2) {
        if (url1 == null || url2 == null) return false;
        int q1 = url1.indexOf('?');
        String base1 = q1 >= 0 ? url1.substring(0, q1) : url1;
        int q2 = url2.indexOf('?');
        String base2 = q2 >= 0 ? url2.substring(0, q2) : url2;
        return base1.trim().equalsIgnoreCase(base2.trim());
    }

    private void addJob() {
        Preset preset = presetBox.getValue();
        String source = getSourceInput();
        Stage parentStage = (Stage) presetBox.getScene().getWindow();
        if (source == null || preset == null) {
            Alert alert = new Alert(Alert.AlertType.WARNING, "Choisis d'abord un fichier source ou saisis une URL.");
            styleDialog(alert, parentStage);
            alert.showAndWait();
            return;
        }
        File output = getOutputFile(preset);
        if (output == null) {
            Alert alert = new Alert(Alert.AlertType.WARNING, "Spécifie d'abord un fichier de destination.");
            styleDialog(alert, parentStage);
            alert.showAndWait();
            return;
        }
        Job job = new Job(
                source, output, preset,
                getSelectedVideoCodec(), getSelectedCrf(), getSelectedResolution(), getSelectedAudioBitrate(),
                extraArgsField.getText()
        );
        queue.submit(job);
    }

    @SuppressWarnings("unchecked")
    private void buildTable() {
        TableColumn<Job, String> nameCol = new TableColumn<>("Nom");
        nameCol.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(c.getValue().getOutput().getName()));
        nameCol.setPrefWidth(180);

        TableColumn<Job, Job.Status> statusCol = new TableColumn<>("Statut");
        statusCol.setCellValueFactory(c -> c.getValue().statusProperty());
        statusCol.setCellFactory(col -> new TableCell<>() {
            private final Label badge = new Label();
            @Override protected void updateItem(Job.Status status, boolean empty) {
                super.updateItem(status, empty);
                if (empty || status == null) {
                    setGraphic(null);
                } else {
                    String text = switch (status) {
                        case EN_ATTENTE -> "EN ATTENTE";
                        case EN_COURS -> "EN COURS";
                        case TERMINE -> "TERMINÉ";
                        case ECHEC -> "ÉCHEC";
                        case ANNULE -> "ANNULÉ";
                    };
                    badge.setText(text);
                    badge.getStyleClass().clear();
                    badge.getStyleClass().add("badge");
                    switch (status) {
                        case EN_ATTENTE -> badge.getStyleClass().add("badge-attente");
                        case EN_COURS -> badge.getStyleClass().add("badge-cours");
                        case TERMINE -> badge.getStyleClass().add("badge-termine");
                        case ECHEC -> badge.getStyleClass().add("badge-echec");
                        case ANNULE -> badge.getStyleClass().add("badge-annule");
                    }
                    if (status == Job.Status.ECHEC) {
                        Job job = getTableView().getItems().get(getIndex());
                        if (job != null && job.messageProperty().get() != null && !job.messageProperty().get().isEmpty()) {
                            Tooltip tooltip = new Tooltip(job.messageProperty().get());
                            tooltip.setStyle("-fx-font-size: 12px; -fx-base: #2b2b36; -fx-text-fill: #ffffff;");
                            badge.setTooltip(tooltip);
                        } else {
                            badge.setTooltip(null);
                        }
                    } else {
                        badge.setTooltip(null);
                    }
                    setGraphic(badge);
                }
            }
        });
        statusCol.setPrefWidth(110);

        TableColumn<Job, Number> progCol = new TableColumn<>("Progression");
        progCol.setCellValueFactory(c -> c.getValue().progressProperty());
        progCol.setCellFactory(col -> new TableCell<>() {
            private final ProgressBar bar = new ProgressBar(0);
            private final Label progressText = new Label("0%");
            private final HBox container = new HBox(10, bar, progressText);
            {
                bar.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(bar, Priority.ALWAYS);
                container.setAlignment(Pos.CENTER_LEFT);
            }
            @Override protected void updateItem(Number value, boolean empty) {
                super.updateItem(value, empty);
                if (empty || value == null) {
                    setGraphic(null);
                } else {
                    double val = value.doubleValue();
                    bar.setProgress(val);
                    progressText.setText(String.format("%.0f%%", val * 100));
                    setGraphic(container);
                }
            }
        });
        progCol.setPrefWidth(180);

        TableColumn<Job, String> timeCol = new TableColumn<>("Durée");
        timeCol.setCellValueFactory(c -> c.getValue().elapsedTimeProperty());
        timeCol.setPrefWidth(70);

        TableColumn<Job, Void> actionCol = new TableColumn<>("Actions");
        actionCol.setCellFactory(col -> new TableCell<>() {
            private final Button cancelBtn = new Button("Annuler");
            private final Button fileBtn = new Button("Fichier");
            private final Button playBtn = new Button("Lire");
            private final HBox container = new HBox(6, cancelBtn, fileBtn, playBtn);
            
            private final javafx.beans.value.ChangeListener<Job.Status> statusListener = (obs, oldStatus, newStatus) -> {
                updateButtons(newStatus);
            };
            private Job currentJob = null;

            {
                container.setAlignment(Pos.CENTER);
                cancelBtn.getStyleClass().add("btn-danger");
                fileBtn.getStyleClass().add("btn-secondary");
                playBtn.getStyleClass().add("btn-secondary");
                
                // Style des boutons pour tenir dans le tableau
                cancelBtn.setStyle("-fx-padding: 4px 8px; -fx-font-size: 11px;");
                fileBtn.setStyle("-fx-padding: 4px 8px; -fx-font-size: 11px;");
                playBtn.setStyle("-fx-padding: 4px 8px; -fx-font-size: 11px;");
                
                cancelBtn.setOnAction(e -> {
                    if (currentJob != null) {
                        if (currentJob.getStatus() == Job.Status.EN_ATTENTE || currentJob.getStatus() == Job.Status.EN_COURS) {
                            queue.cancel(currentJob);
                        } else {
                            queue.getJobs().remove(currentJob);
                        }
                    }
                });
                
                fileBtn.setOnAction(e -> {
                    if (currentJob != null && currentJob.getOutput() != null) {
                        File outFile = currentJob.getOutput();
                        try {
                            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                                new ProcessBuilder("explorer.exe", "/select,", outFile.getAbsolutePath()).start();
                            } else {
                                java.awt.Desktop.getDesktop().open(outFile.getParentFile());
                            }
                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                });
                
                playBtn.setOnAction(e -> {
                    if (currentJob != null && currentJob.getOutput() != null) {
                        playMedia(currentJob.getOutput().getAbsolutePath(), primaryStage);
                    }
                });
            }

            private void updateButtons(Job.Status status) {
                if (currentJob == null) return;
                
                if (status == Job.Status.EN_ATTENTE || status == Job.Status.EN_COURS) {
                    cancelBtn.setText("Annuler");
                    fileBtn.setDisable(true);
                    playBtn.setDisable(true);
                    playBtn.setStyle("-fx-padding: 4px 8px; -fx-font-size: 11px; -fx-background-color: #2b2b36; -fx-text-fill: #7a7a8a;");
                } else {
                    cancelBtn.setText("Supprimer");
                    
                    if (status == Job.Status.TERMINE && currentJob.getOutput() != null && currentJob.getOutput().exists()) {
                        fileBtn.setDisable(false);
                        playBtn.setDisable(false);
                        playBtn.setStyle("-fx-padding: 4px 8px; -fx-font-size: 11px; -fx-background-color: #00e676; -fx-text-fill: #121214; -fx-font-weight: bold;");
                    } else {
                        fileBtn.setDisable(true);
                        playBtn.setDisable(true);
                        playBtn.setStyle("-fx-padding: 4px 8px; -fx-font-size: 11px; -fx-background-color: #2b2b36; -fx-text-fill: #7a7a8a;");
                    }
                }
            }

            @Override protected void updateItem(Void v, boolean empty) {
                super.updateItem(v, empty);
                
                // Se détacher du job précédent pour éviter les fuites de mémoire
                if (currentJob != null) {
                    currentJob.statusProperty().removeListener(statusListener);
                }
                
                if (empty) {
                    currentJob = null;
                    setGraphic(null);
                } else {
                    currentJob = getTableView().getItems().get(getIndex());
                    if (currentJob != null) {
                        currentJob.statusProperty().addListener(statusListener);
                        updateButtons(currentJob.getStatus());
                    }
                    setGraphic(container);
                }
            }
        });
        actionCol.setPrefWidth(210);

        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.getColumns().addAll(nameCol, statusCol, progCol, timeCol, actionCol);
        table.setItems(queue.getJobs());
    }

    /**
     * Rend un panneau (VBox) repliable/dépliable en cliquant sur un élément déclencheur.
     *
     * @param card Les conteneurs VBox représentant la carte.
     * @param headerNode Le nœud principal d'en-tête à conserver visible (généralement le label ou le conteneur du titre).
     * @param clickTarget L'élément interactif qui reçoit le clic pour basculer (généralement le titre Label).
     * @param titleLabel Le label contenant le titre textuel à modifier pour y ajouter la flèche directionnelle.
     * @param startCollapsed Si vrai, le panneau démarre dans l'état replié/masqué au démarrage.
     */
    private void makeCollapsible(VBox card, javafx.scene.Node headerNode, javafx.scene.Node clickTarget, Label titleLabel, boolean startCollapsed) {
        // Obtenir tous les nœuds de contenu à masquer/afficher (tout sauf le headerNode)
        List<javafx.scene.Node> contentNodes = new java.util.ArrayList<>(card.getChildren());
        contentNodes.remove(headerNode);

        // Configurer le curseur pour indiquer l'interactivité
        clickTarget.setCursor(Cursor.HAND);

        // État de repliement
        final boolean[] isCollapsed = {false};
        String originalText = titleLabel.getText();

        // Gestionnaire d'action pour basculer l'affichage
        Runnable toggle = () -> {
            isCollapsed[0] = !isCollapsed[0];
            for (javafx.scene.Node node : contentNodes) {
                node.setVisible(!isCollapsed[0]);
                node.setManaged(!isCollapsed[0]);
            }
            if (isCollapsed[0]) {
                titleLabel.setText("▶ " + originalText);
            } else {
                titleLabel.setText("▼ " + originalText);
            }
        };

        // Déclencher le basculement lors d'un clic
        clickTarget.setOnMouseClicked(e -> toggle.run());

        // État de démarrage
        if (startCollapsed) {
            isCollapsed[0] = false;
            toggle.run();
        } else {
            titleLabel.setText("▼ " + originalText);
        }
    }

    private void styleDialog(Dialog<?> dialog, Stage parentStage) {
        if (parentStage != null) {
            dialog.initOwner(parentStage);
        }
        dialog.getDialogPane().getStylesheets().add(getClass().getResource("/style.css").toExternalForm());
        dialog.getDialogPane().getStyleClass().add("card-panel");
        if (parentStage != null && parentStage.getScene() != null && 
            parentStage.getScene().getRoot().getStyleClass().contains("light-theme")) {
            dialog.getDialogPane().getStyleClass().add("light-theme");
        }
    }

    private void startLocalServer() {
        try {
            localServer = HttpServer.create(new InetSocketAddress("localhost", 8555), 0);
            localServer.createContext("/show", new HttpHandler() {
                @Override
                public void handle(HttpExchange exchange) throws java.io.IOException {
                    if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
                        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
                        exchange.sendResponseHeaders(204, -1);
                        return;
                    }
                    Platform.runLater(() -> {
                        if (primaryStage != null) {
                            primaryStage.show();
                            primaryStage.toFront();
                            primaryStage.setIconified(false);
                        }
                    });
                    exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    byte[] response = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    OutputStream os = exchange.getResponseBody();
                    os.write(response);
                    os.close();
                }
            });
            localServer.createContext("/add-stream", new HttpHandler() {
                @Override
                public void handle(HttpExchange exchange) throws java.io.IOException {
                    // CORS preflight (OPTIONS)
                    if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
                        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
                        exchange.sendResponseHeaders(204, -1);
                        return;
                    }
                    
                    if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                        InputStream is = exchange.getRequestBody();
                        String body = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                                .lines().collect(Collectors.joining("\n"));
                        
                        System.out.println("[DEBUG HTTP Server] Body recu: " + body);
                        String url = extractUrlFromJson(body);
                        String title = extractTitleFromJson(body);
                        boolean play = extractPlayFromJson(body);
                        boolean download = extractDownloadFromJson(body);
                        String outputPath = extractOutputPathFromJson(body);
                        System.out.println("[DEBUG HTTP Server] URL extraite: " + url + " | title: " + title + " | play: " + play + " | download: " + download + " | outputPath: " + outputPath);
                        
                        if (url != null && !url.isEmpty()) {
                            Platform.runLater(() -> {
                                if (primaryStage != null) {
                                    primaryStage.show();
                                    primaryStage.toFront();
                                    primaryStage.setIconified(false);
                                }
                                
                                urlSourceRadio.setSelected(true);
                                urlField.setText(url); // Déclenche le listener et réinitialise
                                
                                if (title != null && !title.isEmpty()) {
                                    suggestedTitleFromExtension = title;
                                    customOutputFile = null;
                                }
                                
                                if (play) {
                                    System.out.println("[DEBUG HTTP Server] Declenchement de playActiveStream...");
                                    playActiveStream(primaryStage);
                                } else if (download) {
                                    if (outputPath != null && !outputPath.isEmpty()) {
                                        customOutputFile = new java.io.File(outputPath);
                                    } else {
                                        customOutputFile = null;
                                    }
                                    
                                    if (outputPath != null && outputPath.toLowerCase().endsWith(".mp3")) {
                                        presetBox.setValue(Preset.EXTRACT_AUDIO_MP3);
                                    } else {
                                        presetBox.setValue(Preset.REMUX_MP4);
                                    }
                                    
                                    updateCustomOutputLabel(presetBox.getValue());
                                    System.out.println("[DEBUG HTTP Server] Declenchement de addJob...");
                                    addJob();
                                } else {
                                    updateCustomOutputLabel(presetBox.getValue());
                                }
                            });
                            
                            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                            exchange.getResponseHeaders().add("Content-Type", "application/json");
                            byte[] response = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
                            exchange.sendResponseHeaders(200, response.length);
                            OutputStream os = exchange.getResponseBody();
                            os.write(response);
                            os.close();
                            return;
                        }
                    }
                    
                    exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    byte[] response = "{\"status\":\"error\"}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(400, response.length);
                    OutputStream os = exchange.getResponseBody();
                    os.write(response);
                    os.close();
                }
            });

            localServer.createContext("/get-default-path", new HttpHandler() {
                @Override
                public void handle(HttpExchange exchange) throws java.io.IOException {
                    // CORS preflight (OPTIONS)
                    if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, OPTIONS");
                        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
                        exchange.sendResponseHeaders(204, -1);
                        return;
                    }
                    
                    if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                        String query = exchange.getRequestURI().getRawQuery();
                        String title = "video";
                        String extension = ".mp4";
                        
                        if (query != null) {
                            for (String param : query.split("&")) {
                                String[] pair = param.split("=");
                                if (pair.length == 2) {
                                    if ("title".equalsIgnoreCase(pair[0])) {
                                        title = java.net.URLDecoder.decode(pair[1], "UTF-8");
                                        // Clean filename for OS compatibility
                                        title = title.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
                                        if (title.isEmpty()) {
                                            title = "video";
                                        }
                                    } else if ("preset".equalsIgnoreCase(pair[0])) {
                                        String prs = java.net.URLDecoder.decode(pair[1], "UTF-8");
                                        if ("mp3".equalsIgnoreCase(prs)) {
                                            extension = ".mp3";
                                        }
                                    }
                                }
                            }
                        }
                        
                        String userHome = System.getProperty("user.home");
                        java.io.File downloads = new java.io.File(userHome, "Downloads");
                        if (!downloads.exists()) {
                            downloads = new java.io.File(userHome);
                        }
                        
                        String fullPath = new java.io.File(downloads, title + extension).getAbsolutePath();
                        
                        String responseJson = String.format(
                            "{\"defaultFolder\":\"%s\",\"fileName\":\"%s\",\"fullPath\":\"%s\"}",
                            downloads.getAbsolutePath().replace("\\", "\\\\"),
                            (title + extension).replace("\\", "\\\\"),
                            fullPath.replace("\\", "\\\\")
                        );
                        
                        byte[] responseBytes = responseJson.getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                        exchange.getResponseHeaders().add("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, responseBytes.length);
                        try (OutputStream os = exchange.getResponseBody()) {
                            os.write(responseBytes);
                        }
                        return;
                    }
                    
                    exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                    exchange.sendResponseHeaders(405, -1);
                }
            });

            localServer.createContext("/pick-directory", new HttpHandler() {
                @Override
                public void handle(HttpExchange exchange) throws java.io.IOException {
                    // CORS preflight (OPTIONS)
                    if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
                        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
                        exchange.sendResponseHeaders(204, -1);
                        return;
                    }
                    
                    if ("POST".equalsIgnoreCase(exchange.getRequestMethod()) || "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                        final java.util.concurrent.CompletableFuture<String> futurePath = new java.util.concurrent.CompletableFuture<>();
                        
                        Platform.runLater(() -> {
                            try {
                                if (primaryStage != null) {
                                    primaryStage.show();
                                    primaryStage.toFront();
                                    primaryStage.setIconified(false);
                                }
                                
                                javafx.stage.DirectoryChooser chooser = new javafx.stage.DirectoryChooser();
                                chooser.setTitle("Choisir le dossier de destination");
                                
                                String userHome = System.getProperty("user.home");
                                java.io.File downloads = new java.io.File(userHome, "Downloads");
                                if (downloads.exists()) {
                                    chooser.setInitialDirectory(downloads);
                                } else {
                                    chooser.setInitialDirectory(new java.io.File(userHome));
                                }
                                
                                java.io.File selectedDirectory = chooser.showDialog(primaryStage);
                                if (selectedDirectory != null) {
                                    futurePath.complete(selectedDirectory.getAbsolutePath());
                                } else {
                                    futurePath.complete(""); // Annulé
                                }
                            } catch (Exception ex) {
                                futurePath.completeExceptionally(ex);
                            }
                        });
                        
                        try {
                            // Attendre max 60 secondes l'interaction de l'utilisateur
                            String path = futurePath.get(60, java.util.concurrent.TimeUnit.SECONDS);
                            String jsonResponse = String.format("{\"status\":\"ok\",\"path\":\"%s\"}", path.replace("\\", "\\\\"));
                            
                            byte[] responseBytes = jsonResponse.getBytes(StandardCharsets.UTF_8);
                            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                            exchange.getResponseHeaders().add("Content-Type", "application/json");
                            exchange.sendResponseHeaders(200, responseBytes.length);
                            try (OutputStream os = exchange.getResponseBody()) {
                                os.write(responseBytes);
                            }
                        } catch (Exception ex) {
                            String jsonResponse = "{\"status\":\"error\",\"message\":\"" + ex.getMessage() + "\"}";
                            byte[] responseBytes = jsonResponse.getBytes(StandardCharsets.UTF_8);
                            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                            exchange.getResponseHeaders().add("Content-Type", "application/json");
                            exchange.sendResponseHeaders(500, responseBytes.length);
                            try (OutputStream os = exchange.getResponseBody()) {
                                os.write(responseBytes);
                            }
                        }
                        return;
                    }
                    
                    exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                    exchange.sendResponseHeaders(405, -1);
                }
            });

            localServer.createContext("/proxy-m3u8", new HttpHandler() {
                @Override
                public void handle(HttpExchange exchange) throws java.io.IOException {
                    // CORS preflight (OPTIONS)
                    if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, OPTIONS");
                        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
                        exchange.sendResponseHeaders(204, -1);
                        return;
                    }
                    
                    if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                        String query = exchange.getRequestURI().getRawQuery();
                        String targetUrl = null;
                        if (query != null && query.startsWith("url=")) {
                            targetUrl = java.net.URLDecoder.decode(query.substring(4), "UTF-8");
                        }
                        
                        if (targetUrl != null && !targetUrl.isEmpty()) {
                            try {
                                java.net.URL urlObj = new java.net.URL(targetUrl);
                                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) urlObj.openConnection();
                                conn.setRequestMethod("GET");
                                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                                conn.setConnectTimeout(5000);
                                conn.setReadTimeout(5000);
                                
                                int status = conn.getResponseCode();
                                if (status == 200) {
                                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                                    StringBuilder sb = new StringBuilder();
                                    String line;
                                    String baseUrl = targetUrl.substring(0, targetUrl.lastIndexOf('/') + 1);
                                    java.net.URL baseUriObj = new java.net.URL(targetUrl);
                                    String hostRoot = baseUriObj.getProtocol() + "://" + baseUriObj.getHost();
                                    
                                    while ((line = reader.readLine()) != null) {
                                        String trimmed = line.trim();
                                        if (trimmed.startsWith("#")) {
                                            if (trimmed.contains("URI=\"")) {
                                                int startIdx = trimmed.indexOf("URI=\"") + 5;
                                                int endIdx = trimmed.indexOf("\"", startIdx);
                                                if (endIdx > startIdx) {
                                                    String relativeUri = trimmed.substring(startIdx, endIdx);
                                                    String absoluteUri = resolveUrl(relativeUri, baseUrl, hostRoot);
                                                    if (absoluteUri.contains(".m3u8")) {
                                                        absoluteUri = "http://localhost:8555/proxy-m3u8?url=" + java.net.URLEncoder.encode(absoluteUri, "UTF-8");
                                                    }
                                                    trimmed = trimmed.substring(0, startIdx) + absoluteUri + trimmed.substring(endIdx);
                                                }
                                            }
                                            sb.append(trimmed).append("\n");
                                        } else if (!trimmed.isEmpty()) {
                                            String absoluteUri = resolveUrl(trimmed, baseUrl, hostRoot);
                                            if (absoluteUri.contains(".m3u8")) {
                                                absoluteUri = "http://localhost:8555/proxy-m3u8?url=" + java.net.URLEncoder.encode(absoluteUri, "UTF-8");
                                            }
                                            sb.append(absoluteUri).append("\n");
                                        } else {
                                            sb.append("\n");
                                        }
                                    }
                                    reader.close();
                                    
                                    byte[] responseBytes = sb.toString().getBytes(StandardCharsets.UTF_8);
                                    exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                                    exchange.getResponseHeaders().add("Content-Type", "application/vnd.apple.mpegurl");
                                    exchange.sendResponseHeaders(200, responseBytes.length);
                                    OutputStream os = exchange.getResponseBody();
                                    os.write(responseBytes);
                                    os.close();
                                    return;
                                }
                            } catch (Exception ex) {
                                System.err.println("[DEBUG HLS Proxy] Exception: " + ex.getMessage());
                            }
                        }
                    }
                    
                    exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                    exchange.sendResponseHeaders(404, -1);
                }
            });

            localServer.createContext("/local-hls", new HttpHandler() {
                @Override
                public void handle(HttpExchange exchange) throws java.io.IOException {
                    // CORS preflight (OPTIONS)
                    if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, OPTIONS");
                        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
                        exchange.sendResponseHeaders(204, -1);
                        return;
                    }
                    
                    if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                        String path = exchange.getRequestURI().getPath(); // /local-hls/playlist.m3u8 etc.
                        String prefix = "/local-hls/";
                        if (path.startsWith(prefix) && tempHlsDir != null && tempHlsDir.exists()) {
                            String fileName = path.substring(prefix.length());
                            if (!fileName.contains("..")) {
                                File file = new File(tempHlsDir, fileName);
                                if (file.exists() && file.isFile()) {
                                    try {
                                        byte[] fileBytes = java.nio.file.Files.readAllBytes(file.toPath());
                                        
                                        String contentType = "application/octet-stream";
                                        if (fileName.endsWith(".m3u8")) {
                                            contentType = "application/vnd.apple.mpegurl";
                                        } else if (fileName.endsWith(".ts")) {
                                            contentType = "video/MP2T";
                                        }
                                        
                                        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                                        exchange.getResponseHeaders().add("Content-Type", contentType);
                                        exchange.sendResponseHeaders(200, fileBytes.length);
                                        try (OutputStream os = exchange.getResponseBody()) {
                                            os.write(fileBytes);
                                        }
                                        System.out.println("[HLS Server] 200 OK : " + fileName + " (" + fileBytes.length + " octets)");
                                        return;
                                    } catch (Exception ex) {
                                        System.err.println("[HLS Server] [ERROR] Impossible de servir " + fileName + " : " + ex.getMessage());
                                        ex.printStackTrace(System.err);
                                        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                                        exchange.sendResponseHeaders(500, -1);
                                        return;
                                    }
                                } else {
                                    System.out.println("[HLS Server] 404 Introuvable : " + fileName);
                                }
                            }
                        }
                    }
                    
                    exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                    exchange.sendResponseHeaders(404, -1);
                }
            });
            
            localServer.setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r);
                t.setDaemon(true);
                t.setName("LocalServer-Executor");
                return t;
            }));
            localServer.start();
            System.out.println("Mini-serveur HTTP démarré sur http://localhost:8555");
        } catch (Exception e) {
            System.err.println("Impossible de démarrer le mini-serveur HTTP : " + e.getMessage());
        }
    }

    private static String resolveUrl(String relativeUrl, String baseUrl, String hostRoot) {
        if (relativeUrl.startsWith("http://") || relativeUrl.startsWith("https://")) {
            return relativeUrl;
        }
        if (relativeUrl.startsWith("/")) {
            return hostRoot + relativeUrl;
        }
        try {
            java.net.URL base = new java.net.URL(baseUrl);
            java.net.URL resolved = new java.net.URL(base, relativeUrl);
            return resolved.toString();
        } catch (Exception e) {
            return baseUrl + relativeUrl;
        }
    }

    private String extractUrlFromJson(String json) {
        if (json == null) return null;
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"");
        java.util.regex.Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return matcher.group(1).replace("\\/", "/");
        }
        return null;
    }

    private String extractTitleFromJson(String json) {
        if (json == null) return null;
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\"title\"\\s*:\\s*\"([^\"]+)\"");
        java.util.regex.Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            String title = matcher.group(1);
            // Nettoyage pour un nom de fichier Windows/OS valide
            title = title.replaceAll("[\\\\/:*?\"<>|]", "_");
            return title.trim();
        }
        return null;
    }

    private void setupSystemTray(Stage stage) {
        // Désactiver la fermeture automatique de JavaFX lorsque toutes les fenêtres sont masquées
        Platform.setImplicitExit(false);

        if (!java.awt.SystemTray.isSupported()) {
            System.out.println("SystemTray n'est pas supporté sur ce système.");
            stage.setOnCloseRequest(e -> Platform.exit());
            return;
        }

        // Pour éviter le blocage de l'AWT thread
        java.awt.Toolkit.getDefaultToolkit();

        try {
            java.awt.SystemTray tray = java.awt.SystemTray.getSystemTray();
            
            // Chargement de l'icône AWT
            java.awt.Image image = javax.imageio.ImageIO.read(getClass().getResourceAsStream("/icon.png"));
            
            java.awt.PopupMenu popup = new java.awt.PopupMenu();
            
            java.awt.MenuItem openItem = new java.awt.MenuItem("Ouvrir FFmpeg Studio");
            openItem.addActionListener(e -> Platform.runLater(() -> {
                stage.show();
                stage.toFront();
                stage.setIconified(false);
            }));
            popup.add(openItem);
            
            java.awt.MenuItem exitItem = new java.awt.MenuItem("Quitter");
            exitItem.addActionListener(e -> Platform.runLater(() -> {
                try {
                    tray.remove(trayIcon);
                } catch (Exception ex) {}
                Platform.exit();
            }));
            popup.add(exitItem);
            
            trayIcon = new java.awt.TrayIcon(image, "FFmpeg Studio", popup);
            trayIcon.setImageAutoSize(true);
            
            // Action au double-clic sur l'icône
            trayIcon.addActionListener(e -> Platform.runLater(() -> {
                stage.show();
                stage.toFront();
                stage.setIconified(false);
            }));
            
            tray.add(trayIcon);
            
            // Intercepter la fermeture de la fenêtre principale
            stage.setOnCloseRequest(e -> {
                e.consume(); // Empêche la fermeture réelle de l'application
                stage.hide(); // Masque simplement la fenêtre
                
                // Afficher une notification ballon au premier masquage
                if (prefs.getBoolean("showTrayInfo", true)) {
                    trayIcon.displayMessage(
                        "FFmpeg Studio",
                        "L'application continue de fonctionner en arrière-plan dans la barre des tâches.",
                        java.awt.TrayIcon.MessageType.INFO
                    );
                    prefs.putBoolean("showTrayInfo", false); // Afficher une seule fois par session
                }
            });
            
        } catch (Exception ex) {
            System.err.println("Impossible d'initialiser le SystemTray : " + ex.getMessage());
            stage.setOnCloseRequest(e -> Platform.exit());
        }
    }

    private boolean extractPlayFromJson(String json) {
        if (json == null) return false;
        return json.contains("\"play\":true") || json.contains("\"play\": true");
    }

    private boolean extractDownloadFromJson(String json) {
        if (json == null) return false;
        return json.contains("\"download\":true") || json.contains("\"download\": true");
    }

    private String extractOutputPathFromJson(String json) {
        if (json == null) return null;
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\"outputPath\"\\s*:\\s*\"([^\"]+)\"");
        java.util.regex.Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return matcher.group(1).replace("\\\\", "\\").replace("\\/", "/");
        }
        return null;
    }

    private void stopProcess(Process process) {
        if (process != null) {
            try {
                process.destroy();
                process.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            } catch (Exception e) {
                // Ignore
            }
        }
    }

    private void deleteDir(File dir) {
        if (dir != null && dir.exists()) {
            try {
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        f.delete();
                    }
                }
                dir.delete();
            } catch (Exception e) {
                // Ignore
            }
        }
    }

    private void disposeMediaPlayer() {
        final MediaPlayer oldPlayer = mediaPlayer;
        if (oldPlayer != null) {
            mediaPlayer = null;
            Runnable r = () -> {
                try {
                    oldPlayer.stop();
                    // Différer le dispose au prochain pulse JavaFX pour laisser à stop() le temps d'initier le nettoyage GStreamer
                    Platform.runLater(() -> {
                        try {
                            oldPlayer.dispose();
                        } catch (Exception ex) {
                            // Ignore
                        }
                    });
                } catch (Exception ex) {
                    // Ignore
                }
            };
            if (Platform.isFxApplicationThread()) {
                r.run();
            } else {
                Platform.runLater(r);
            }
        }
        if (activePlayerStage != null) {
            Platform.runLater(() -> {
                if (activePlayerStage != null) {
                    activePlayerStage.close();
                    activePlayerStage = null;
                }
            });
        }
    }

    private synchronized void cleanupTransmux() {
        if (activePrepTask != null) {
            activePrepTask.cancel();
            activePrepTask = null;
        }
        activePrepUrl = null;
        if (activeTransmuxProcess != null) {
            System.out.println("[Transmux] Arrêt du processus FFmpeg...");
            stopProcess(activeTransmuxProcess);
            activeTransmuxProcess = null;
        }
        if (tempHlsDir != null) {
            System.out.println("[Transmux] Nettoyage des fichiers temporaires dans " + tempHlsDir.getAbsolutePath());
            deleteDir(tempHlsDir);
            tempHlsDir = null;
        }
        if (tempPlayFile != null && tempPlayFile.exists()) {
            System.out.println("[Play] Nettoyage du fichier de lecture temporaire : " + tempPlayFile.getAbsolutePath());
            tempPlayFile.delete();
            tempPlayFile = null;
        }
    }

    private boolean checkAndNotifyExistingInstance() {
        try {
            java.net.URL url = new java.net.URL("http://localhost:8555/show");
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(1000);
            conn.setReadTimeout(1000);
            int code = conn.getResponseCode();
            if (code == 200) {
                System.out.println("Une autre instance est déjà en cours d'exécution. Notification envoyée.");
                return true;
            }
        } catch (Exception e) {
            // Le port est libre ou l'instance n'a pas répondu
        }
        return false;
    }

    private void openLogFile() {
        java.io.File file = new java.io.File("errors.log");
        if (!file.exists()) {
            try {
                file.createNewFile();
            } catch (Exception ex) {
                // ignore
            }
        }
        if (file.exists()) {
            try {
                String os = System.getProperty("os.name").toLowerCase();
                if (os.contains("win")) {
                    new ProcessBuilder("notepad.exe", file.getAbsolutePath()).start();
                } else if (os.contains("mac")) {
                    new ProcessBuilder("open", "-e", file.getAbsolutePath()).start();
                } else {
                    new ProcessBuilder("xdg-open", file.getAbsolutePath()).start();
                }
            } catch (Exception ex) {
                try {
                    if (java.awt.Desktop.isDesktopSupported()) {
                        java.awt.Desktop.getDesktop().open(file);
                    }
                } catch (Exception e2) {
                    System.err.println("Impossible d'ouvrir le fichier de log : " + e2.getMessage());
                }
            }
        }
    }

    @Override
    public void stop() throws Exception {
        super.stop();
        System.out.println("Arrêt en cours de l'application...");
        if (trayIcon != null) {
            try {
                java.awt.SystemTray.getSystemTray().remove(trayIcon);
            } catch (Exception ex) {
                // Ignore
            }
        }
        if (queue != null) {
            queue.shutdown();
        }
        disposeMediaPlayer();
        if (localServer != null) {
            System.out.println("Arrêt du mini-serveur HTTP...");
            localServer.stop(0);
        }
        cleanupTransmux();
        System.exit(0);
    }

    private static void setupErrorLogging() {
        try {
            java.io.File logFile = new java.io.File("errors.log");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(logFile, true);
            java.io.PrintStream fileOut = new java.io.PrintStream(fos, true, "UTF-8");
            java.io.PrintStream originalErr = System.err;
            
            java.io.PrintStream dualErr = new java.io.PrintStream(new java.io.OutputStream() {
                @Override
                public void write(int b) throws java.io.IOException {
                    originalErr.write(b);
                    fileOut.write(b);
                }
                
                @Override
                public void write(byte[] b, int off, int len) throws java.io.IOException {
                    originalErr.write(b, off, len);
                    fileOut.write(b, off, len);
                }
                
                @Override
                public void flush() throws java.io.IOException {
                    originalErr.flush();
                    fileOut.flush();
                }
                
                @Override
                public void close() throws java.io.IOException {
                    fileOut.close();
                    originalErr.close();
                }
            }, true, "UTF-8");
            
            System.setErr(dualErr);
            System.err.println("\n--- DÉMARRAGE DE L'APPLICATION [" + java.time.LocalDateTime.now() + "] ---");
        } catch (Exception e) {
            System.out.println("Impossible de configurer le fichier de log d'erreurs : " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        setupErrorLogging();
        launch(args);
    }
}
