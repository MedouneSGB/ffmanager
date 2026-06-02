# ffmanager

Application desktop JavaFX qui fournit une interface conviviale par-dessus
FFmpeg : file de jobs (conversion, compression, remux HLS, extraction audio),
presets + champ d'arguments bruts, aperçu live de la commande générée.

## Stack

- Java 21, JavaFX 21.0.4, Maven
- FFmpeg + ffprobe appelés via `ProcessBuilder` (arguments en liste, jamais concaténés)
- Cible principale : Windows

## Structure

```
src/main/java/com/eyone/ffmpegstudio/
├── App.java            – UI JavaFX (code-only, pas FXML)
├── Launcher.java       – point d'entrée fat-jar (contourne la restriction JavaFX)
├── model/
│   ├── Job.java        – job FFmpeg avec Property observables (status, progress)
│   └── Preset.java     – enum des presets → buildArgs() retourne List<String>
└── service/
    ├── FFmpegRunner.java      – lance FFmpeg, parse -progress pipe:1
    └── JobQueueService.java   – file séquentielle (pool taille 1), annulation
```

## Conventions

- Packages : `com.eyone.ffmpegstudio` (model / service)
- Worker de file taille 1 en v1 (séquentiel, CPU-bound)
- Toute mise à jour des Property depuis un thread de fond → `Platform.runLater`
- Commandes FFmpeg construites en `List<String>` (pas de concaténation de chaînes)

## Lancement dev

```
mvn javafx:run
```

## TODO

- Couche d'options avancées (codec, CRF, résolution) qui écrase les valeurs du preset
- Embarquer ffmpeg.exe / ffprobe.exe via jpackage (app autonome, sans PATH)
- Nettoyage des fichiers temporaires + limites de taille de file
- Découper App.java en FXML + controller quand ça grossit
