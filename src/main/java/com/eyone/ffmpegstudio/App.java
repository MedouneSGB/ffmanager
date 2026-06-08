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
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

// Moteur de lecture VLC (libVLC via vlcj). Les types dont le nom court entre en
// collision avec javafx (MediaPlayer) sont référencés en chemin complet dans le code.
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery;
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;
import uk.co.caprica.vlcj.javafx.videosurface.ImageViewVideoSurface;

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

    /** Page de soutien (GitHub Sponsors). */
    private static final String SPONSOR_URL = "https://github.com/sponsors/MedouneSGB";

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
    // Référence du lecteur de streaming
    private HttpServer localServer;
    private Stage primaryStage;
    private Stage activePlayerStage;
    private volatile boolean playerActive = false;
    private Stage miniDownloadStage;
    private VBox miniDownloadVBox;

    // Moteur de lecture : VLC/libVLC via vlcj.
    private Boolean vlcAvailable; // mémoïsé : null = pas encore testé
    private MediaPlayerFactory vlcFactory;
    private EmbeddedMediaPlayer vlcPlayer;
    // Source brute en cours de lecture, indépendante du moteur (pour le test "déjà ouvert").
    private String currentPlayingSource;

    // Transmuxing local HLS
    private java.io.File tempHlsDir;
    private Process activeTransmuxProcess;
    private javafx.concurrent.Task<String> activePrepTask;
    private String activePrepUrl;
    private String activeTransmuxUrl;

    @Override
    public void start(Stage stage) {
        if (checkAndNotifyExistingInstance()) {
            Platform.exit();
            System.exit(0);
            return;
        }
        this.primaryStage = stage;
        // Chargement des chemins FFmpeg (binaires embarqués détectés automatiquement).
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
        logsBtn.setOnAction(e -> showLogPanel(stage));

        Button sponsorBtn = new Button("💜 Soutenir");
        sponsorBtn.getStyleClass().add("btn-secondary");
        sponsorBtn.setTooltip(new Tooltip("Soutenir le développement de FFmpeg Studio"));
        sponsorBtn.setOnAction(e -> openUrl(SPONSOR_URL, stage));

        HBox statusBox = new HBox(12, sponsorBtn, themeToggleBtn, logsBtn);
        statusBox.setAlignment(Pos.CENTER_RIGHT);
        
        BorderPane header = new BorderPane();
        header.setLeft(appTitle);
        header.setRight(statusBox);
        header.setPadding(new Insets(10, 15, 10, 15));
        header.setStyle("-fx-background-color: bg-header; -fx-border-color: transparent transparent border-header transparent; -fx-border-width: 1px;");

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
        saveDestBtn.setMinWidth(140);
        saveDestBtn.setPrefWidth(140);
        saveDestBtn.setOnAction(e -> pickCustomOutput(stage));
        
        Button playUrlBtn = new Button("▶ Lire le flux");
        playUrlBtn.getStyleClass().add("btn-secondary");
        playUrlBtn.setMinWidth(110);
        playUrlBtn.setPrefWidth(110);
        playUrlBtn.setOnAction(e -> playActiveStream(stage));
        
        customOutputLabel.setStyle("-fx-font-weight: normal; -fx-text-fill: text-muted;");
        customOutputLabel.setMinWidth(0);
        HBox.setHgrow(customOutputLabel, Priority.ALWAYS);
        
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
        java.net.URL cssUrl = getClass().getResource("/style.css");
        if (cssUrl != null) {
            scene.getStylesheets().add(cssUrl.toExternalForm());
        }
        
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

    /**
     * Recherche le runtime VLC (libVLC) embarqué à côté de l'application.
     *
     * Sur le modèle de {@link #findBundledBinaries()} : le CI copie le runtime VLC
     * (libvlc + libvlccore + dossier {@code plugins/}) dans le dossier {@code vlc/}
     * de l'image jpackage, à côté du JAR. On localise le répertoire contenant la
     * bibliothèque native principale ({@code libvlc.dll} / {@code .dylib} / {@code .so}).
     *
     * @return le dossier contenant libvlc, ou {@code null} si aucun runtime embarqué
     *         (ex : lancement en dev → repli sur une installation VLC système).
     */
    private File findBundledVlc() {
        try {
            File codeSource = new File(
                    App.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            File baseDir = codeSource.isDirectory() ? codeSource : codeSource.getParentFile();
            if (baseDir == null) return null;

            String os = System.getProperty("os.name").toLowerCase();
            String libName = os.contains("win") ? "libvlc.dll"
                    : os.contains("mac") ? "libvlc.dylib" : "libvlc.so";

            File[] candidateDirs = {
                    new File(baseDir, "vlc"),
                    baseDir
            };
            for (File dir : candidateDirs) {
                if (new File(dir, libName).isFile()) {
                    return dir;
                }
            }
        } catch (Exception e) {
            System.err.println("[VLC] Détection du runtime VLC embarqué impossible : " + e.getMessage());
        }
        return null;
    }

    /**
     * Vérifie (une seule fois, résultat mémoïsé) qu'une bibliothèque libVLC est
     * chargeable. Priorité au runtime embarqué (dossier {@code vlc/}), repli sur
     * une installation VLC système via {@link NativeDiscovery}.
     */
    private boolean isVlcAvailable() {
        if (vlcAvailable != null) return vlcAvailable;
        try {
            File bundled = findBundledVlc();
            if (bundled != null) {
                // La stratégie de découverte vlcj qui inspecte "jna.library.path"
                // trouvera le runtime embarqué ; les plugins co-localisés (dossier
                // plugins/) sont résolus automatiquement par libVLC.
                System.setProperty("jna.library.path", bundled.getAbsolutePath());
                com.sun.jna.NativeLibrary.addSearchPath("libvlc", bundled.getAbsolutePath());

                // Sous Linux, libVLC ne retrouve pas ses plugins relocalisés : il faut
                // pointer VLC_PLUGIN_PATH vers le dossier plugins/ embarqué. On positionne
                // la variable d'environnement réelle du process via setenv() de la libc
                // (JNA), car libVLC (code C) la lit avec getenv() — un System.setProperty
                // ne serait pas visible côté natif.
                if (System.getProperty("os.name").toLowerCase().contains("nux")) {
                    try {
                        File plugins = new File(bundled, "plugins");
                        if (plugins.isDirectory()) {
                            PosixCLib.INSTANCE.setenv("VLC_PLUGIN_PATH", plugins.getAbsolutePath(), 1);
                        }
                    } catch (Throwable t) {
                        System.err.println("[VLC] Impossible de définir VLC_PLUGIN_PATH : " + t.getMessage());
                    }
                }
            }
            vlcAvailable = new NativeDiscovery().discover();
            if (!vlcAvailable) {
                System.err.println("[VLC] Aucune bibliothèque libVLC trouvée (embarquée ou système).");
            }
        } catch (Throwable t) {
            System.err.println("[VLC] Indisponible : " + t.getMessage());
            vlcAvailable = false;
        }
        return vlcAvailable;
    }

    /** Accès à setenv() de la libc POSIX (Linux/macOS) pour définir VLC_PLUGIN_PATH côté natif. */
    private interface PosixCLib extends com.sun.jna.Library {
        PosixCLib INSTANCE = com.sun.jna.Native.load("c", PosixCLib.class);
        int setenv(String name, String value, int overwrite);
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
            // Décoder d'éventuelles entités HTML (&amp;, &#x3D;) restées dans une URL
            // collée ou détectée, sinon le flux est injouable et intéléchargeable.
            url = org.jsoup.parser.Parser.unescapeEntities(url, false);

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
        // 1. Détecter si la même source est déjà en cours de lecture (tous moteurs confondus)
        if (activePlayerStage != null && currentPlayingSource != null
                && isSameBaseUrl(currentPlayingSource, source)) {
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

        disposeMediaPlayer();
        playerActive = true;
        currentPlayingSource = source;

        // Moteur VLC : lit la source brute directement (fichier local ou URL réseau),
        // libVLC décodant nativement HLS, fMP4, MKV, HEVC, etc.
        if (isVlcAvailable()) {
            openVlcPlayerStage(source, parentStage);
            return;
        }

        // VLC indisponible : plus de repli JavaFX, on informe l'utilisateur.
        playerActive = false;
        currentPlayingSource = null;
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Lecteur VLC indisponible");
            alert.setHeaderText("VLC (libVLC) n'a pas été trouvé sur ce système.");
            alert.setContentText("La lecture intégrée nécessite VLC.\n"
                    + "Installez VLC ou utilisez une version packagée de l'application (libVLC embarqué).");
            styleDialog(alert, parentStage);
            alert.showAndWait();
        });
    }

    private synchronized void startHlsTransmux(String source) throws java.io.IOException {
        cleanupTransmux();
        
        tempHlsDir = java.nio.file.Files.createTempDirectory("ffmpeg-studio-hls").toFile();
        File playlistFile = new File(tempHlsDir, "playlist.m3u8");
        
        List<String> cmd = new java.util.ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-y");
        cmd.add("-user_agent");
        cmd.add("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        cmd.add("-i");
        cmd.add(source);
        cmd.add("-c");
        cmd.add("copy");
        cmd.add("-f");
        cmd.add("hls");
        cmd.add("-hls_time");
        cmd.add("3");
        cmd.add("-hls_list_size");
        cmd.add("0");
        cmd.add("-hls_segment_filename");
        cmd.add(new File(tempHlsDir, "seg_%03d.ts").getAbsolutePath());
        cmd.add(playlistFile.getAbsolutePath());
        
        System.out.println("[Transmux] Lancement de la commande : " + String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.to(new File(tempHlsDir, "transmux.log")));
        
        activeTransmuxProcess = pb.start();
        activeTransmuxUrl = source;
    }

    /**
     * Ouvre la fenêtre du lecteur en s'appuyant sur le moteur VLC (libVLC via vlcj).
     *
     * Contrairement au lecteur JavaFX, VLC lit la {@code source} brute directement
     * (fichier local ou URL réseau) sans passer par le proxy/transmux localhost,
     * car libVLC décode nativement HLS, fMP4, MKV, HEVC, etc.
     */
    private void openVlcPlayerStage(String source, Stage parentStage) {
        try {
            if (vlcFactory == null) {
                vlcFactory = new MediaPlayerFactory();
            }
            vlcPlayer = vlcFactory.mediaPlayers().newEmbeddedMediaPlayer();
            final EmbeddedMediaPlayer vp = vlcPlayer;

            // Surface vidéo : rendu par callback dans un ImageView JavaFX.
            ImageView mediaView = new ImageView();
            mediaView.setPreserveRatio(true);
            vp.videoSurface().set(new ImageViewVideoSurface(mediaView));

            Stage playerStage = new Stage();
            activePlayerStage = playerStage;
            playerStage.initOwner(parentStage);
            playerStage.setTitle("Lecteur VLC - FFmpeg Studio 🎥");
            try {
                playerStage.getIcons().add(new javafx.scene.image.Image(getClass().getResourceAsStream("/icon.png")));
            } catch (Exception e) {
                // Ignorer
            }
            playerStage.setResizable(true);
            playerStage.setMinWidth(550);
            playerStage.setMinHeight(300);

            StackPane videoPane = new StackPane(mediaView);
            videoPane.setStyle("-fx-background-color: #000000;");
            videoPane.setMinSize(0, 0);
            videoPane.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2) {
                    playerStage.setFullScreen(!playerStage.isFullScreen());
                }
            });
            mediaView.fitWidthProperty().bind(videoPane.widthProperty());
            mediaView.fitHeightProperty().bind(videoPane.heightProperty());

            // Barre de contrôles flottante (identique au lecteur JavaFX)
            HBox controls = new HBox(12);
            controls.setAlignment(Pos.CENTER);
            controls.setPadding(new Insets(10, 15, 10, 15));
            controls.setStyle("-fx-background-color: rgba(26, 26, 36, 0.85); -fx-border-color: #2b2b36; -fx-border-width: 1px; -fx-background-radius: 8px; -fx-border-radius: 8px;");
            controls.setMinHeight(HBox.USE_PREF_SIZE);
            controls.setMaxHeight(HBox.USE_PREF_SIZE);

            Button playPauseBtn = new Button("⏸");
            playPauseBtn.getStyleClass().add("btn-secondary");
            playPauseBtn.setMinWidth(36);
            playPauseBtn.setPrefWidth(36);
            playPauseBtn.setStyle("-fx-padding: 6 0 6 0; -fx-alignment: center;");
            playPauseBtn.setOnAction(e -> {
                if (vp.status().isPlaying()) {
                    vp.controls().pause();
                    playPauseBtn.setText("▶");
                } else {
                    vp.controls().play();
                    playPauseBtn.setText("⏸");
                }
            });

            Label timeLabel = new Label("00:00 / 00:00");
            timeLabel.setStyle("-fx-text-fill: #e1e1e6; -fx-font-family: monospace;");
            timeLabel.setMinWidth(110);
            timeLabel.setPrefWidth(110);
            timeLabel.setAlignment(Pos.CENTER);

            Slider timeSlider = new Slider();
            HBox.setHgrow(timeSlider, Priority.ALWAYS);
            timeSlider.getStyleClass().add("slider");
            timeSlider.setMin(0);
            timeSlider.setMinWidth(50);

            Label volLabel = new Label("🔊");
            volLabel.setMinWidth(24);
            volLabel.setPrefWidth(24);
            volLabel.setAlignment(Pos.CENTER);

            Slider volSlider = new Slider(0, 1, 0.5);
            volSlider.setPrefWidth(80);
            volSlider.setMinWidth(60);
            volSlider.getStyleClass().add("slider");
            volSlider.valueProperty().addListener((o, a, b) -> {
                vp.audio().setVolume((int) Math.round(b.doubleValue() * 100));
                volLabel.setText(b.doubleValue() == 0 ? "🔇" : "🔊");
            });

            Button fsBtn = new Button("⛶");
            fsBtn.getStyleClass().add("btn-secondary");
            fsBtn.setMinWidth(36);
            fsBtn.setPrefWidth(36);
            fsBtn.setStyle("-fx-padding: 6 0 6 0; -fx-alignment: center;");
            fsBtn.setOnAction(e -> playerStage.setFullScreen(!playerStage.isFullScreen()));

            controls.getChildren().addAll(playPauseBtn, timeSlider, timeLabel, volLabel, volSlider, fsBtn);

            // Suivi de la durée totale (en ms) pour gérer live vs durée connue.
            final long[] totalMs = { -1 };

            // Recherche (Seek) : on met en pause pendant le glissement.
            timeSlider.setOnMousePressed(e -> vp.controls().setPause(true));
            timeSlider.setOnMouseReleased(e -> {
                vp.controls().setTime((long) (timeSlider.getValue() * 1000));
                if (playPauseBtn.getText().equals("⏸")) {
                    vp.controls().setPause(false);
                }
            });
            timeSlider.setOnMouseClicked(e -> vp.controls().setTime((long) (timeSlider.getValue() * 1000)));

            StackPane playerLayout = new StackPane();
            playerLayout.getChildren().addAll(videoPane, controls);
            StackPane.setAlignment(controls, Pos.BOTTOM_CENTER);
            StackPane.setMargin(controls, new Insets(0, 15, 15, 15));

            Scene scene = new Scene(playerLayout, 640, 420);
            java.net.URL cssUrl = getClass().getResource("/style.css");
            if (cssUrl != null) {
                scene.getStylesheets().add(cssUrl.toExternalForm());
            }

            // Animations d'auto-masquage des contrôles (identiques au lecteur JavaFX)
            PauseTransition idleTimeout = new PauseTransition(Duration.seconds(2.5));
            FadeTransition fadeOut = new FadeTransition(Duration.millis(350), controls);
            fadeOut.setFromValue(1.0);
            fadeOut.setToValue(0.0);
            fadeOut.setOnFinished(evt -> {
                controls.setVisible(false);
                scene.setCursor(Cursor.NONE);
            });
            FadeTransition fadeIn = new FadeTransition(Duration.millis(250), controls);
            fadeIn.setToValue(1.0);

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
                if (vp.status().isPlaying() && !controls.isHover()) {
                    if (fadeIn.getStatus() == Animation.Status.RUNNING) {
                        fadeIn.stop();
                    }
                    fadeOut.setFromValue(controls.getOpacity());
                    fadeOut.playFromStart();
                } else if (controls.isHover() && vp.status().isPlaying()) {
                    idleTimeout.playFromStart();
                }
            };
            idleTimeout.setOnFinished(evt -> hideControls.run());

            playerLayout.setOnMouseMoved(e -> {
                showControls.run();
                if (vp.status().isPlaying()) idleTimeout.playFromStart(); else idleTimeout.stop();
            });
            playerLayout.setOnMouseDragged(e -> {
                showControls.run();
                if (vp.status().isPlaying()) idleTimeout.playFromStart(); else idleTimeout.stop();
            });
            playerLayout.setOnMouseExited(e -> {
                if (vp.status().isPlaying()) hideControls.run();
            });

            scene.setOnKeyPressed(keyEvent -> {
                switch (keyEvent.getCode()) {
                    case SPACE -> playPauseBtn.fire();
                    case M -> {
                        vp.audio().setMute(!vp.audio().isMute());
                        volLabel.setText(vp.audio().isMute() ? "🔇" : "🔊");
                    }
                    case F -> fsBtn.fire();
                    case LEFT -> {
                        long cur = vp.status().time();
                        long target = Math.max(0, cur - 10000);
                        vp.controls().setTime(target);
                        keyEvent.consume();
                    }
                    case RIGHT -> {
                        long cur = vp.status().time();
                        long target = totalMs[0] > 0 ? Math.min(totalMs[0], cur + 10000) : cur + 10000;
                        vp.controls().setTime(target);
                        keyEvent.consume();
                    }
                    case UP -> {
                        double nextVol = Math.min(1.0, volSlider.getValue() + 0.05);
                        volSlider.setValue(nextVol);
                        keyEvent.consume();
                    }
                    case DOWN -> {
                        double nextVol = Math.max(0.0, volSlider.getValue() - 0.05);
                        volSlider.setValue(nextVol);
                        keyEvent.consume();
                    }
                    default -> {}
                }
            });

            // Événements vlcj : arrivent sur des threads natifs → Platform.runLater obligatoire.
            vp.events().addMediaPlayerEventListener(new MediaPlayerEventAdapter() {
                @Override
                public void mediaPlayerReady(uk.co.caprica.vlcj.player.base.MediaPlayer mp) {
                    Platform.runLater(() -> vp.audio().setVolume((int) Math.round(volSlider.getValue() * 100)));
                }
                @Override
                public void lengthChanged(uk.co.caprica.vlcj.player.base.MediaPlayer mp, long newLength) {
                    totalMs[0] = newLength;
                    Platform.runLater(() -> {
                        if (newLength > 0) {
                            timeSlider.setMax(newLength / 1000.0);
                            timeSlider.setDisable(false);
                        } else {
                            timeSlider.setDisable(true);
                            timeLabel.setText("Direct / Live");
                        }
                    });
                }
                @Override
                public void timeChanged(uk.co.caprica.vlcj.player.base.MediaPlayer mp, long newTime) {
                    Platform.runLater(() -> {
                        if (!timeSlider.isPressed() && !timeSlider.isValueChanging()) {
                            timeSlider.setValue(newTime / 1000.0);
                        }
                        Duration total = totalMs[0] > 0 ? Duration.millis(totalMs[0]) : Duration.UNKNOWN;
                        updateTimeLabel(timeLabel, Duration.millis(newTime), total);
                    });
                }
                @Override
                public void videoOutput(uk.co.caprica.vlcj.player.base.MediaPlayer mp, int newCount) {
                    java.awt.Dimension dim = vp.video().videoDimension();
                    if (dim == null) return;
                    Platform.runLater(() -> {
                        double mediaW = dim.getWidth();
                        double mediaH = dim.getHeight();
                        double targetW = 854, targetH = 480;
                        if (mediaW > 0 && mediaH > 0) {
                            double ratio = mediaW / mediaH;
                            if (mediaW > 960 || mediaH > 540) {
                                if (ratio > 960.0 / 540.0) { targetW = 960; targetH = 960 / ratio; }
                                else { targetH = 540; targetW = 540 * ratio; }
                            } else {
                                targetW = Math.max(640, mediaW);
                                targetH = Math.max(360, mediaH);
                            }
                        }
                        playerStage.setWidth(targetW);
                        playerStage.setHeight(targetH + 90);
                    });
                }
                @Override
                public void playing(uk.co.caprica.vlcj.player.base.MediaPlayer mp) {
                    Platform.runLater(() -> { playPauseBtn.setText("⏸"); idleTimeout.playFromStart(); });
                }
                @Override
                public void paused(uk.co.caprica.vlcj.player.base.MediaPlayer mp) {
                    Platform.runLater(() -> { playPauseBtn.setText("▶"); idleTimeout.stop(); showControls.run(); });
                }
                @Override
                public void finished(uk.co.caprica.vlcj.player.base.MediaPlayer mp) {
                    Platform.runLater(() -> { playPauseBtn.setText("▶"); showControls.run(); });
                }
                @Override
                public void error(uk.co.caprica.vlcj.player.base.MediaPlayer mp) {
                    System.err.println("[ERROR] Erreur du lecteur VLC pour : " + source);
                    Platform.runLater(() -> {
                        String originalUrl = (source.startsWith("http://") || source.startsWith("https://")) ? source : null;
                        if (originalUrl != null) {
                            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                            alert.setTitle("Erreur de Lecture");
                            alert.setHeaderText("Lecture VLC impossible");
                            alert.setContentText("Ce flux vidéo n'a pas pu être lu par VLC.\n\nVoulez-vous le télécharger à la place ?");
                            styleDialog(alert, playerStage);
                            ButtonType downloadBtnType = new ButtonType("Télécharger", ButtonBar.ButtonData.OK_DONE);
                            ButtonType cancelBtnType = new ButtonType("Annuler", ButtonBar.ButtonData.CANCEL_CLOSE);
                            alert.getButtonTypes().setAll(downloadBtnType, cancelBtnType);
                            alert.showAndWait().ifPresent(response -> {
                                if (response == downloadBtnType) {
                                    urlSourceRadio.setSelected(true);
                                    urlField.setText(originalUrl);
                                    presetBox.getSelectionModel().select(Preset.DOWNLOAD_STREAM);
                                    if (primaryStage != null) {
                                        primaryStage.show();
                                        primaryStage.toFront();
                                        primaryStage.setIconified(false);
                                    }
                                }
                            });
                        } else {
                            Alert alert = new Alert(Alert.AlertType.ERROR);
                            alert.setTitle("Erreur de Lecture");
                            alert.setHeaderText("Impossible de lire ce média avec VLC.");
                            alert.setContentText("Assurez-vous que le fichier ou le flux est valide et accessible.");
                            styleDialog(alert, playerStage);
                            alert.showAndWait();
                        }
                        playerStage.close();
                    });
                }
            });

            playerStage.setScene(scene);
            playerStage.setOnCloseRequest(e -> {
                idleTimeout.stop();
                fadeOut.stop();
                fadeIn.stop();
                activePlayerStage = null;
                disposeMediaPlayer();
            });

            playerStage.show();

            // Démarrage de la lecture (URL réseau → on reproduit le user-agent du proxy).
            boolean isNetwork = source.startsWith("http://") || source.startsWith("https://");
            String mrl = isNetwork ? source : new File(source).getAbsolutePath();
            if (isNetwork) {
                vp.media().play(mrl,
                        ":http-user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            } else {
                vp.media().play(mrl);
            }

        } catch (Throwable ex) {
            System.err.println("[VLC] Échec d'initialisation du lecteur VLC : " + ex.getMessage());
            ex.printStackTrace(System.err);
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle("Erreur");
                alert.setHeaderText("Impossible d'initialiser le lecteur VLC.");
                alert.setContentText(String.valueOf(ex.getMessage()));
                styleDialog(alert, parentStage);
                alert.showAndWait();
            });
            disposeMediaPlayer();
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
        nameCol.setMinWidth(120);

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
        statusCol.setMinWidth(90);

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
        progCol.setMinWidth(110);

        TableColumn<Job, String> timeCol = new TableColumn<>("Durée");
        timeCol.setCellValueFactory(c -> c.getValue().elapsedTimeProperty());
        timeCol.setPrefWidth(70);
        timeCol.setMinWidth(55);

        TableColumn<Job, Void> actionCol = new TableColumn<>("Actions");
        actionCol.setCellFactory(col -> new TableCell<>() {
            private final Button cancelBtn = new Button();
            private final Button fileBtn = new Button("📂");
            private final Button playBtn = new Button("▶");
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
                
                cancelBtn.setTooltip(new Tooltip("Annuler ou supprimer"));
                fileBtn.setTooltip(new Tooltip("Afficher le dossier du fichier"));
                playBtn.setTooltip(new Tooltip("Lire le fichier"));
                
                // Adapter dynamiquement le texte/icones selon la largeur disponible
                widthProperty().addListener((obs, oldVal, newVal) -> {
                    if (currentJob != null) {
                        updateButtons(currentJob.getStatus());
                    }
                });
                
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
                
                boolean useShort = getWidth() < 185;
                
                if (status == Job.Status.EN_ATTENTE || status == Job.Status.EN_COURS) {
                    cancelBtn.setText(useShort ? "❌" : "❌ Annuler");
                    cancelBtn.setTooltip(new Tooltip("Annuler la tâche"));
                    cancelBtn.setVisible(true);
                    cancelBtn.setManaged(true);
                    
                    fileBtn.setVisible(false);
                    fileBtn.setManaged(false);
                    
                    playBtn.setVisible(false);
                    playBtn.setManaged(false);
                } else {
                    cancelBtn.setText(useShort ? "🗑" : "🗑 Supprimer");
                    cancelBtn.setTooltip(new Tooltip("Supprimer de la file"));
                    cancelBtn.setVisible(true);
                    cancelBtn.setManaged(true);
                    
                    if (status == Job.Status.TERMINE && currentJob.getOutput() != null && currentJob.getOutput().exists()) {
                        fileBtn.setVisible(true);
                        fileBtn.setManaged(true);
                        fileBtn.setDisable(false);
                        fileBtn.setText(useShort ? "📂" : "📂 Dossier");
                        
                        playBtn.setVisible(true);
                        playBtn.setManaged(true);
                        playBtn.setDisable(false);
                        playBtn.setText(useShort ? "▶" : "▶ Lire");
                        playBtn.setTooltip(new Tooltip("Lire le fichier"));
                        playBtn.setStyle("-fx-padding: 4px 8px; -fx-font-size: 11px; -fx-background-color: #00e676; -fx-text-fill: #121214; -fx-font-weight: bold;");
                    } else {
                        fileBtn.setVisible(false);
                        fileBtn.setManaged(false);
                        
                        playBtn.setVisible(false);
                        playBtn.setManaged(false);
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
        actionCol.setMinWidth(90);

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
        java.net.URL cssUrl = getClass().getResource("/style.css");
        if (cssUrl != null) {
            dialog.getDialogPane().getStylesheets().add(cssUrl.toExternalForm());
        }
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
                                boolean isMainAppShowing = primaryStage != null && primaryStage.isShowing() && !primaryStage.isIconified();
                                
                                if (primaryStage != null) {
                                    if (play || download) {
                                        if (isMainAppShowing) {
                                            primaryStage.show();
                                            primaryStage.toFront();
                                            primaryStage.setIconified(false);
                                        }
                                    } else {
                                        primaryStage.show();
                                        primaryStage.toFront();
                                        primaryStage.setIconified(false);
                                    }
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
                                    
                                    if (!isMainAppShowing) {
                                        showMiniDownloadPanel(primaryStage);
                                    }
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

            localServer.createContext("/proxy-video/", new HttpHandler() {
                @Override
                public void handle(HttpExchange exchange) throws java.io.IOException {
                    // CORS preflight (OPTIONS)
                    if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, OPTIONS");
                        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Range, Content-Type");
                        exchange.sendResponseHeaders(204, -1);
                        return;
                    }
                    
                    if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                        if (!playerActive) {
                            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                            exchange.sendResponseHeaders(404, -1);
                            return;
                        }
                        String query = exchange.getRequestURI().getRawQuery();
                        String targetUrl = null;
                        if (query != null && query.startsWith("url=")) {
                            targetUrl = java.net.URLDecoder.decode(query.substring(4), "UTF-8");
                        }
                        
                        if (targetUrl != null && !targetUrl.isEmpty()) {
                            java.net.HttpURLConnection conn = null;
                            try {
                                String currentUrl = targetUrl;
                                int redirectCount = 0;
                                while (redirectCount < 5) {
                                    java.net.URL urlObj = new java.net.URL(currentUrl);
                                    conn = (java.net.HttpURLConnection) urlObj.openConnection();
                                    conn.setRequestMethod("GET");
                                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
                                    
                                    // Transmettre le header Range s'il est présent
                                    String rangeHeader = exchange.getRequestHeaders().getFirst("Range");
                                    if (rangeHeader != null) {
                                        conn.setRequestProperty("Range", rangeHeader);
                                    }
                                    
                                    conn.setConnectTimeout(8000);
                                    conn.setReadTimeout(15000);
                                    conn.setInstanceFollowRedirects(false); // On gère les redirections manuellement pour supporter HTTP <-> HTTPS
                                    
                                    int status = conn.getResponseCode();
                                    if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                                        String loc = conn.getHeaderField("Location");
                                        if (loc != null) {
                                            currentUrl = resolveUrl(loc, currentUrl, currentUrl);
                                            redirectCount++;
                                            conn.disconnect();
                                            continue;
                                        }
                                    }
                                    break;
                                }
                                
                                int status = conn.getResponseCode();
                                
                                // Copier les en-têtes importants de la réponse
                                String contentType = conn.getContentType();
                                if (contentType != null) {
                                    exchange.getResponseHeaders().add("Content-Type", contentType);
                                } else {
                                    exchange.getResponseHeaders().add("Content-Type", "video/mp4");
                                }
                                exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                                
                                String contentRange = conn.getHeaderField("Content-Range");
                                if (contentRange != null) {
                                    exchange.getResponseHeaders().add("Content-Range", contentRange);
                                }
                                String acceptRanges = conn.getHeaderField("Accept-Ranges");
                                if (acceptRanges != null) {
                                    exchange.getResponseHeaders().add("Accept-Ranges", acceptRanges);
                                }
                                
                                long contentLength = conn.getContentLengthLong();
                                exchange.sendResponseHeaders(status, contentLength > 0 ? contentLength : 0);
                                
                                try (InputStream is = (status >= 400) ? conn.getErrorStream() : conn.getInputStream();
                                     OutputStream os = exchange.getResponseBody()) {
                                    if (is != null) {
                                        byte[] buffer = new byte[16384];
                                        int read;
                                        while ((read = is.read(buffer)) != -1) {
                                            os.write(buffer, 0, read);
                                        }
                                    }
                                }
                                return;
                            } catch (Exception ex) {
                                System.err.println("[DEBUG Video Proxy] Erreur pour " + targetUrl + " : " + ex.getMessage());
                            } finally {
                                if (conn != null) {
                                    conn.disconnect();
                                }
                            }
                        }
                    }
                    
                    exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                    exchange.sendResponseHeaders(404, -1);
                }
            });

            localServer.createContext("/proxy-m3u8/", new HttpHandler() {
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
                        if (!playerActive) {
                            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                            exchange.sendResponseHeaders(404, -1);
                            return;
                        }
                        String query = exchange.getRequestURI().getRawQuery();
                        String targetUrl = null;
                        if (query != null && query.startsWith("url=")) {
                            targetUrl = java.net.URLDecoder.decode(query.substring(4), "UTF-8");
                        }
                        
                        if (targetUrl != null && !targetUrl.isEmpty()) {
                            java.net.HttpURLConnection conn = null;
                            try {
                                String currentUrl = targetUrl;
                                int redirectCount = 0;
                                while (redirectCount < 5) {
                                    java.net.URL urlObj = new java.net.URL(currentUrl);
                                    conn = (java.net.HttpURLConnection) urlObj.openConnection();
                                    conn.setRequestMethod("GET");
                                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
                                    conn.setConnectTimeout(8000);
                                    conn.setReadTimeout(15000);
                                    conn.setInstanceFollowRedirects(false); // On gère les redirections manuellement pour supporter HTTP <-> HTTPS
                                    
                                    int status = conn.getResponseCode();
                                    if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                                        String loc = conn.getHeaderField("Location");
                                        if (loc != null) {
                                            currentUrl = resolveUrl(loc, currentUrl, currentUrl);
                                            redirectCount++;
                                            conn.disconnect();
                                            continue;
                                        }
                                    }
                                    break;
                                }
                                
                                int status = conn.getResponseCode();
                                if (status == 200) {
                                    java.util.List<String> rawLines = new java.util.ArrayList<>();
                                    boolean isFmp4 = false;
                                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                                        String line;
                                        while ((line = reader.readLine()) != null) {
                                            rawLines.add(line);
                                            String trimmed = line.trim();
                                            if (trimmed.startsWith("#EXT-X-MAP")) {
                                                isFmp4 = true;
                                            }
                                            if (!trimmed.startsWith("#") && !trimmed.isEmpty()) {
                                                if (trimmed.contains(".m4s") || trimmed.contains(".mp4") || trimmed.endsWith(".m4s") || trimmed.endsWith(".mp4")) {
                                                    isFmp4 = true;
                                                }
                                            }
                                        }
                                    }

                                    boolean isMaster = false;
                                    for (String line : rawLines) {
                                        if (line.trim().startsWith("#EXT-X-STREAM-INF")) {
                                            isMaster = true;
                                            break;
                                        }
                                    }

                                    if (isMaster) {
                                        String baseUrl = currentUrl.substring(0, currentUrl.lastIndexOf('/') + 1);
                                        java.net.URL baseUriObj = new java.net.URL(currentUrl);
                                        String hostRoot = baseUriObj.getProtocol() + "://" + baseUriObj.getHost();
                                        
                                        String bestVariantUrl = null;
                                        int maxBandwidth = -1;
                                        String currentInfo = null;
                                        for (String line : rawLines) {
                                            String trimmed = line.trim();
                                            if (trimmed.startsWith("#EXT-X-STREAM-INF")) {
                                                currentInfo = trimmed;
                                            } else if (!trimmed.startsWith("#") && !trimmed.isEmpty() && currentInfo != null) {
                                                int bandwidth = 0;
                                                java.util.regex.Pattern p = java.util.regex.Pattern.compile("BANDWIDTH=(\\d+)");
                                                java.util.regex.Matcher m = p.matcher(currentInfo);
                                                if (m.find()) {
                                                    bandwidth = Integer.parseInt(m.group(1));
                                                }
                                                if (bandwidth > maxBandwidth) {
                                                    maxBandwidth = bandwidth;
                                                    bestVariantUrl = resolveUrl(trimmed, baseUrl, hostRoot);
                                                }
                                                currentInfo = null;
                                            }
                                        }
                                        if (bestVariantUrl != null) {
                                            if (checkUrlIsFmp4(bestVariantUrl)) {
                                                System.out.println("[HLS Proxy] Master playlist avec variante fMP4 détectée. Lancement du transmuxeur local sur le Master HLS...");
                                                isFmp4 = true;
                                                targetUrl = currentUrl;
                                            } else {
                                                System.out.println("[HLS Proxy] Master playlist détectée. Redirection vers la meilleure variante : " + bestVariantUrl);
                                                exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                                                exchange.getResponseHeaders().add("Location", "http://localhost:8555/proxy-m3u8/playlist.m3u8?url=" + java.net.URLEncoder.encode(bestVariantUrl, "UTF-8"));
                                                exchange.sendResponseHeaders(302, -1);
                                                return;
                                            }
                                        }
                                    }

                                    if (isFmp4) {
                                        System.out.println("[HLS Proxy] Fragmented MP4 détecté. Redirection vers le transmuxeur local...");
                                        String transmuxSource = targetUrl;
                                        if (targetUrl.contains("video.twimg.com")) {
                                            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                                                    "(https://video\\.twimg\\.com/(?:ext_tw_video|amplify_video)/[^/]+/(?:pu/pl|vid)/)avc1/[^/]+/([^/]+\\.m3u8)");
                                            java.util.regex.Matcher m = p.matcher(targetUrl);
                                            if (m.find()) {
                                                transmuxSource = m.group(1) + m.group(2);
                                                System.out.println("[HLS Proxy] Reconstruction du Master Playlist pour le transmuxeur : " + transmuxSource);
                                            }
                                        }
                                        boolean alreadyRunning = false;
                                        synchronized (App.this) {
                                            if (activeTransmuxUrl != null && activeTransmuxUrl.equals(transmuxSource)) {
                                                alreadyRunning = true;
                                            }
                                        }
                                        
                                        if (!alreadyRunning) {
                                            startHlsTransmux(transmuxSource);
                                        } else {
                                            System.out.println("[HLS Proxy] Le transmuxage de cette URL est déjà en cours d'exécution ou terminé.");
                                        }
                                        
                                        File playlistFile = null;
                                        synchronized (App.this) {
                                            if (tempHlsDir != null) {
                                                playlistFile = new File(tempHlsDir, "playlist.m3u8");
                                            }
                                        }
                                        
                                        if (playlistFile != null) {
                                            int waitCount = 0;
                                            while ((!playlistFile.exists() || playlistFile.length() == 0) && waitCount < 300) {
                                                try {
                                                    Thread.sleep(50);
                                                } catch (InterruptedException ie) {
                                                    Thread.currentThread().interrupt();
                                                }
                                                waitCount++;
                                            }
                                            if (playlistFile.exists()) {
                                                java.util.List<String> lines = java.nio.file.Files.readAllLines(playlistFile.toPath(), StandardCharsets.UTF_8);
                                                StringBuilder sb = new StringBuilder();
                                                for (String line : lines) {
                                                    String trimmed = line.trim();
                                                    if (trimmed.startsWith("#")) {
                                                        sb.append(trimmed).append("\n");
                                                    } else if (!trimmed.isEmpty()) {
                                                        sb.append("http://localhost:8555/local-hls/").append(trimmed).append("\n");
                                                    } else {
                                                        sb.append("\n");
                                                    }
                                                }
                                                byte[] responseBytes = sb.toString().getBytes(StandardCharsets.UTF_8);
                                                exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                                                exchange.getResponseHeaders().add("Content-Type", "application/vnd.apple.mpegurl");
                                                exchange.sendResponseHeaders(200, responseBytes.length);
                                                try (OutputStream os = exchange.getResponseBody()) {
                                                    os.write(responseBytes);
                                                }
                                                return;
                                            } else {
                                                System.err.println("[HLS Proxy] Timeout de transmuxage fMP4.");
                                            }
                                        } else {
                                            System.err.println("[HLS Proxy] Dossier temp HLS introuvable.");
                                        }
                                    }

                                    StringBuilder sb = new StringBuilder();
                                    String baseUrl = currentUrl.substring(0, currentUrl.lastIndexOf('/') + 1);
                                    java.net.URL baseUriObj = new java.net.URL(currentUrl);
                                    String hostRoot = baseUriObj.getProtocol() + "://" + baseUriObj.getHost();
                                    
                                    for (String line : rawLines) {
                                        String trimmed = line.trim();
                                        if (trimmed.startsWith("#")) {
                                            if (trimmed.contains("URI=\"")) {
                                                int startIdx = trimmed.indexOf("URI=\"") + 5;
                                                int endIdx = trimmed.indexOf("\"", startIdx);
                                                if (endIdx > startIdx) {
                                                    String relativeUri = trimmed.substring(startIdx, endIdx);
                                                    String absoluteUri = resolveUrl(relativeUri, baseUrl, hostRoot);
                                                    if (absoluteUri.contains(".m3u8")) {
                                                        absoluteUri = "http://localhost:8555/proxy-m3u8/playlist.m3u8?url=" + java.net.URLEncoder.encode(absoluteUri, "UTF-8");
                                                    } else {
                                                        absoluteUri = "http://localhost:8555/proxy-video/segment.ts?url=" + java.net.URLEncoder.encode(absoluteUri, "UTF-8");
                                                    }
                                                    trimmed = trimmed.substring(0, startIdx) + absoluteUri + trimmed.substring(endIdx);
                                                }
                                            }
                                            sb.append(trimmed).append("\n");
                                        } else if (!trimmed.isEmpty()) {
                                            String absoluteUri = resolveUrl(trimmed, baseUrl, hostRoot);
                                            if (absoluteUri.contains(".m3u8")) {
                                                absoluteUri = "http://localhost:8555/proxy-m3u8/playlist.m3u8?url=" + java.net.URLEncoder.encode(absoluteUri, "UTF-8");
                                            } else {
                                                absoluteUri = "http://localhost:8555/proxy-video/segment.ts?url=" + java.net.URLEncoder.encode(absoluteUri, "UTF-8");
                                            }
                                            sb.append(absoluteUri).append("\n");
                                        } else {
                                            sb.append("\n");
                                        }
                                    }
                                    
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
                            } finally {
                                if (conn != null) {
                                    conn.disconnect();
                                }
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
                        if (path.startsWith(prefix)) {
                            String fileName = path.substring(prefix.length());
                            if (!fileName.contains("..")) {
                                File localTempDir = tempHlsDir;
                                if (localTempDir != null) {
                                    File file = new File(localTempDir, fileName);
                                    int waitCount = 0;
                                    // Attendre que le fichier soit créé par FFmpeg (timeout de 5 secondes)
                                    while ((!file.exists() || !file.isFile() || file.length() == 0) && waitCount < 100) {
                                        try {
                                            Thread.sleep(50);
                                        } catch (InterruptedException ie) {
                                            Thread.currentThread().interrupt();
                                        }
                                        waitCount++;
                                    }
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
                                        System.out.println("[HLS Server] 404 Introuvable (après attente) : " + fileName);
                                    }
                                }
                            }
                        }
                    }
                    
                    exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                    exchange.sendResponseHeaders(404, -1);
                }
            });
            
            localServer.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
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

    private boolean checkUrlIsFmp4(String targetUrl) {
        java.net.HttpURLConnection conn = null;
        try {
            String currentUrl = targetUrl;
            int redirectCount = 0;
            while (redirectCount < 5) {
                java.net.URL urlObj = new java.net.URL(currentUrl);
                conn = (java.net.HttpURLConnection) urlObj.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setInstanceFollowRedirects(false);
                
                int status = conn.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String loc = conn.getHeaderField("Location");
                    if (loc != null) {
                        currentUrl = resolveUrl(loc, currentUrl, currentUrl);
                        redirectCount++;
                        conn.disconnect();
                        continue;
                    }
                }
                break;
            }
            
            if (conn.getResponseCode() == 200) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String trimmed = line.trim();
                        if (trimmed.startsWith("#EXT-X-MAP")) {
                            return true;
                        }
                        if (!trimmed.startsWith("#") && !trimmed.isEmpty()) {
                            if (trimmed.contains(".m4s") || trimmed.contains(".mp4") || trimmed.endsWith(".m4s") || trimmed.endsWith(".mp4")) {
                                return true;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[Proxy] Erreur lors de la vérification fMP4 de " + targetUrl + " : " + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return false;
    }

    private String extractOriginalUrl(String mediaUrl) {
        if (mediaUrl == null) return null;
        try {
            if (mediaUrl.startsWith("http://localhost:8555/proxy-video/") || 
                mediaUrl.startsWith("http://localhost:8555/proxy-m3u8/")) {
                int q = mediaUrl.indexOf("url=");
                if (q >= 0) {
                    return java.net.URLDecoder.decode(mediaUrl.substring(q + 4), "UTF-8");
                }
            } else if (mediaUrl.startsWith("http://localhost:8555/local-hls/")) {
                return activeTransmuxUrl;
            }
        } catch (Exception ignore) {}
        return null;
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
                
                // Si des téléchargements sont en cours/attente, on affiche le mini-panneau
                boolean hasActiveJobs = false;
                if (queue != null) {
                    for (Job j : queue.getJobs()) {
                        if (j.getStatus() == Job.Status.EN_ATTENTE || j.getStatus() == Job.Status.EN_COURS) {
                            hasActiveJobs = true;
                            break;
                        }
                    }
                }
                if (hasActiveJobs) {
                    showMiniDownloadPanel(stage);
                } else {
                    // Afficher une notification ballon au premier masquage
                    if (prefs.getBoolean("showTrayInfo", true)) {
                        trayIcon.displayMessage(
                            "FFmpeg Studio",
                            "L'application continue de fonctionner en arrière-plan dans la barre des tâches.",
                            java.awt.TrayIcon.MessageType.INFO
                        );
                        prefs.putBoolean("showTrayInfo", false); // Afficher une seule fois par session
                    }
                }
            });
            
        } catch (Exception ex) {
            System.err.println("Impossible d'initialiser le SystemTray : " + ex.getMessage());
            stage.setOnCloseRequest(e -> Platform.exit());
        }
    }

    private void showMiniDownloadPanel(Stage parentStage) {
        if (miniDownloadStage != null && miniDownloadStage.isShowing()) {
            miniDownloadStage.toFront();
            return;
        }

        miniDownloadStage = new Stage();
        miniDownloadStage.setTitle("Téléchargements - FFmpeg Studio");
        try {
            miniDownloadStage.getIcons().add(new javafx.scene.image.Image(getClass().getResourceAsStream("/icon.png")));
        } catch (Exception e) {}
        
        miniDownloadStage.setResizable(false);
        miniDownloadStage.setAlwaysOnTop(true);

        miniDownloadVBox = new VBox(10);
        miniDownloadVBox.setPadding(new Insets(0, 5, 0, 5));

        ScrollPane scrollPane = new ScrollPane(miniDownloadVBox);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefViewportHeight(95);
        scrollPane.setPrefViewportWidth(380);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background-insets: 0; -fx-padding: 0;");

        Runnable updateList = () -> {
            if (miniDownloadVBox == null) return;
            miniDownloadVBox.getChildren().clear();
            
            java.util.List<Job> activeJobs = new java.util.ArrayList<>();
            for (Job j : queue.getJobs()) {
                if (j.getStatus() == Job.Status.EN_ATTENTE || j.getStatus() == Job.Status.EN_COURS) {
                    activeJobs.add(j);
                }
            }
            
            if (activeJobs.isEmpty() && !queue.getJobs().isEmpty()) {
                activeJobs.add(queue.getJobs().get(queue.getJobs().size() - 1));
            }

            if (activeJobs.isEmpty()) {
                Label emptyLabel = new Label("Aucun téléchargement actif.");
                emptyLabel.setStyle("-fx-text-fill: text-muted; -fx-font-style: italic; -fx-font-size: 11px;");
                miniDownloadVBox.getChildren().add(emptyLabel);
            } else {
                for (Job job : activeJobs) {
                    VBox jobRow = createMiniJobRow(job);
                    miniDownloadVBox.getChildren().add(jobRow);
                }
            }
        };

        javafx.collections.ListChangeListener<Job> queueListener = change -> Platform.runLater(updateList);
        queue.getJobs().addListener(queueListener);

        miniDownloadStage.setOnCloseRequest(e -> {
            queue.getJobs().removeListener(queueListener);
            miniDownloadStage = null;
            miniDownloadVBox = null;
        });

        Button openMainBtn = new Button("Ouvrir FFmpeg Studio");
        openMainBtn.getStyleClass().add("btn-secondary");
        openMainBtn.setMaxWidth(Double.MAX_VALUE);
        openMainBtn.setStyle("-fx-font-size: 12px; -fx-padding: 6 12;");
        openMainBtn.setOnAction(e -> {
            if (primaryStage != null) {
                primaryStage.show();
                primaryStage.toFront();
                primaryStage.setIconified(false);
            }
            miniDownloadStage.close();
        });

        VBox rootLayout = new VBox(10, scrollPane, openMainBtn);
        rootLayout.setPadding(new Insets(10));
        rootLayout.getStyleClass().add("card-panel");

        Scene scene = new Scene(rootLayout);
        java.net.URL cssUrl = getClass().getResource("/style.css");
        if (cssUrl != null) {
            scene.getStylesheets().add(cssUrl.toExternalForm());
        }
        
        if (parentStage != null && parentStage.getScene() != null && 
            parentStage.getScene().getRoot().getStyleClass().contains("light-theme")) {
            rootLayout.getStyleClass().add("light-theme");
        }

        miniDownloadStage.setScene(scene);
        updateList.run();
        miniDownloadStage.show();
    }

    private VBox createMiniJobRow(Job job) {
        Label nameLabel = new Label(job.getOutput().getName());
        nameLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: text-primary; -fx-font-size: 12px;");
        nameLabel.setWrapText(true);

        ProgressBar bar = new ProgressBar(0);
        bar.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(bar, Priority.ALWAYS);
        bar.progressProperty().bind(job.progressProperty());

        Label pctLabel = new Label("0%");
        pctLabel.setStyle("-fx-text-fill: text-secondary; -fx-font-family: monospace; -fx-font-size: 11px;");
        pctLabel.setMinWidth(40);
        pctLabel.setAlignment(Pos.CENTER_RIGHT);
        job.progressProperty().addListener((obs, oldVal, newVal) -> {
            Platform.runLater(() -> {
                pctLabel.setText(String.format("%.0f%%", newVal.doubleValue() * 100));
            });
        });
        pctLabel.setText(String.format("%.0f%%", job.progressProperty().get() * 100));

        HBox progressRow = new HBox(10, bar, pctLabel);
        progressRow.setAlignment(Pos.CENTER_LEFT);

        Label statusLabel = new Label();
        statusLabel.setStyle("-fx-font-size: 10px;");
        
        Button actionBtn = new Button("Annuler");
        actionBtn.setStyle("-fx-font-size: 10px; -fx-padding: 3 6;");
        actionBtn.getStyleClass().add("btn-secondary");

        Button fileBtn = new Button("📂");
        fileBtn.setStyle("-fx-font-size: 10px; -fx-padding: 3 6;");
        fileBtn.getStyleClass().add("btn-secondary");
        fileBtn.setTooltip(new Tooltip("Afficher le dossier du fichier"));
        fileBtn.setOnAction(e -> {
            if (job.getOutput() != null) {
                File outFile = job.getOutput();
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

        Button playBtn = new Button("▶");
        playBtn.setStyle("-fx-font-size: 10px; -fx-padding: 3 6; -fx-background-color: #00e676; -fx-text-fill: #121214; -fx-font-weight: bold;");
        playBtn.setTooltip(new Tooltip("Lire le fichier"));
        playBtn.setOnAction(e -> {
            if (job.getOutput() != null) {
                playMedia(job.getOutput().getAbsolutePath(), primaryStage);
            }
        });

        Runnable updateStatusUI = () -> {
            Job.Status s = job.getStatus();
            statusLabel.setText(switch (s) {
                case EN_ATTENTE -> "En attente...";
                case EN_COURS -> "En cours...";
                case TERMINE -> "Terminé ✅";
                case ECHEC -> "Échec ❌";
                case ANNULE -> "Annulé ⏹";
            });
            if (s == Job.Status.EN_ATTENTE || s == Job.Status.EN_COURS) {
                actionBtn.setText("Annuler");
                actionBtn.setDisable(false);
                actionBtn.setOnAction(e -> queue.cancel(job));
                fileBtn.setVisible(false);
                fileBtn.setManaged(false);
                playBtn.setVisible(false);
                playBtn.setManaged(false);
            } else {
                actionBtn.setText("Supprimer");
                actionBtn.setOnAction(e -> {
                    queue.getJobs().remove(job);
                });
                if (s == Job.Status.TERMINE && job.getOutput() != null && job.getOutput().exists()) {
                    fileBtn.setVisible(true);
                    fileBtn.setManaged(true);
                    playBtn.setVisible(true);
                    playBtn.setManaged(true);
                } else {
                    fileBtn.setVisible(false);
                    fileBtn.setManaged(false);
                    playBtn.setVisible(false);
                    playBtn.setManaged(false);
                }
            }
        };

        job.statusProperty().addListener((obs, oldVal, newVal) -> Platform.runLater(updateStatusUI));
        updateStatusUI.run();

        HBox footerRow = new HBox(6, statusLabel, new Pane(), fileBtn, playBtn, actionBtn);
        HBox.setHgrow(footerRow.getChildren().get(1), Priority.ALWAYS);
        footerRow.setAlignment(Pos.CENTER_LEFT);

        VBox row = new VBox(5, nameLabel, progressRow, footerRow);
        row.setStyle("-fx-border-color: border-color; -fx-border-width: 0 0 1 0; -fx-padding: 0 0 5 0;");
        return row;
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
        playerActive = false;
        currentPlayingSource = null;

        // Libération du moteur VLC (release hors thread JavaFX, recommandé par vlcj).
        final EmbeddedMediaPlayer oldVlc = vlcPlayer;
        final MediaPlayerFactory oldFactory = vlcFactory;
        if (oldVlc != null) {
            vlcPlayer = null;
            vlcFactory = null;
            new Thread(() -> {
                try {
                    oldVlc.controls().stop();
                    oldVlc.release();
                    if (oldFactory != null) {
                        oldFactory.release();
                    }
                } catch (Exception ex) {
                    // Ignore
                }
            }, "vlc-dispose").start();
        }

        if (activePlayerStage != null) {
            Platform.runLater(() -> {
                if (activePlayerStage != null) {
                    activePlayerStage.close();
                    activePlayerStage = null;
                }
            });
        }
        cleanupTransmux();
    }

    private synchronized void cleanupTransmux() {
        if (activePrepTask != null) {
            activePrepTask.cancel();
            activePrepTask = null;
        }
        activePrepUrl = null;
        activeTransmuxUrl = null;
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

    /** Ouvre une URL dans le navigateur par défaut (HostServices JavaFX, repli java.awt.Desktop). */
    private void openUrl(String url, Stage parentStage) {
        try {
            getHostServices().showDocument(url);
        } catch (Throwable primary) {
            try {
                if (java.awt.Desktop.isDesktopSupported()) {
                    java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
                    return;
                }
                throw primary;
            } catch (Throwable fallback) {
                System.err.println("[Soutenir] Impossible d'ouvrir l'URL : " + fallback.getMessage());
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.INFORMATION);
                    alert.setTitle("Soutenir FFmpeg Studio");
                    alert.setHeaderText("Ouvrez ce lien dans votre navigateur :");
                    alert.setContentText(url);
                    styleDialog(alert, parentStage);
                    alert.showAndWait();
                });
            }
        }
    }

    /** Affiche un panneau présentant directement le contenu du journal, avec rafraîchissement et vidage. */
    private void showLogPanel(Stage parentStage) {
        final java.io.File file = new java.io.File("errors.log");

        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Logs de l'application");
        dialog.setHeaderText("Contenu du journal (errors.log)");
        styleDialog(dialog, parentStage);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        TextArea logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(false);
        logArea.setStyle("-fx-font-family: 'monospace';");
        VBox.setVgrow(logArea, Priority.ALWAYS);

        Runnable reload = () -> {
            String content = "";
            try {
                if (file.exists()) {
                    content = new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                }
            } catch (Exception ex) {
                content = "Impossible de lire le journal : " + ex.getMessage();
            }
            logArea.setText(content.isEmpty() ? "(journal vide)" : content);
            logArea.positionCaret(logArea.getLength());
        };
        reload.run();

        Button refreshBtn = new Button("🔄 Rafraîchir");
        refreshBtn.getStyleClass().add("btn-secondary");
        refreshBtn.setOnAction(e -> reload.run());

        Button clearBtn = new Button("🗑 Supprimer les logs");
        clearBtn.getStyleClass().add("btn-secondary");
        clearBtn.setOnAction(e -> {
            try {
                // Tronque le fichier à 0 octet ; le flux d'écriture en mode append reprend ensuite depuis le début.
                new java.io.FileOutputStream(file, false).close();
            } catch (Exception ex) {
                System.err.println("Impossible de vider le journal : " + ex.getMessage());
            }
            reload.run();
        });

        HBox actions = new HBox(10, refreshBtn, clearBtn);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(10, logArea, actions);
        content.setPadding(new Insets(10));

        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefSize(760, 520);
        dialog.setResizable(true);
        dialog.showAndWait();
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
