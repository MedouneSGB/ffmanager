package com.eyone.ffmpegstudio.service;

import com.eyone.ffmpegstudio.model.Job;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Lance FFmpeg pour un job et reporte la progression.
 *
 * - ffprobe donne d'abord la duree totale (pour calculer un %).
 * - ffmpeg est lance avec -progress pipe:1 : il ecrit des lignes cle=valeur
 *   sur stdout (out_time_us=..., progress=continue/end).
 * - La lecture se fait sur le thread appelant (qui doit etre un thread de fond,
 *   PAS le thread UI JavaFX).
 */
public class FFmpegRunner {

    private String ffmpegPath;
    private String ffprobePath;

    public FFmpegRunner(String ffmpegPath, String ffprobePath) {
        this.ffmpegPath = ffmpegPath;
        this.ffprobePath = ffprobePath;
    }

    public String getFfmpegPath() { return ffmpegPath; }
    public void setFfmpegPath(String path) { this.ffmpegPath = path; }

    public String getFfprobePath() { return ffprobePath; }
    public void setFfprobePath(String path) { this.ffprobePath = path; }

    /** Vérifie si les binaires FFmpeg et ffprobe sont disponibles. */
    public boolean checkAvailability() {
        return isExecutableAvailable(ffmpegPath) && isExecutableAvailable(ffprobePath);
    }

    private static boolean isExecutableAvailable(String path) {
        if (path == null || path.trim().isEmpty()) {
            return false;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(path, "-version");
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
                System.err.println("[WARN] FFmpegRunner check: '" + path + "' a retourné le code de sortie " + exitCode);
            }
            return exitCode == 0;
        } catch (Exception e) {
            System.err.println("[ERROR] Échec de la vérification de l'exécutable '" + path + "' : " + e.getMessage());
            e.printStackTrace(System.err);
            return false;
        }
    }

    /**
     * Execute le job. progressCallback recoit (fraction 0..1, message).
     * Retourne le code de sortie de FFmpeg (0 = succes).
     */
    public int run(Job job, BiConsumer<Double, String> progressCallback)
            throws IOException, InterruptedException {

        double totalSeconds = probeDurationSeconds(job.getSource());

        List<String> cmd = job.getPreset().buildArgs(
                ffmpegPath, job.getSource(), job.getOutput(),
                job.getVideoCodec(), job.getCrf(), job.getResolution(), job.getAudioBitrate(),
                job.getExtraArgs());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        Process process = pb.start();
        job.setProcess(process);

        // FFmpeg ecrit ses logs sur stderr. Si on ne le vide pas, le buffer
        // systeme se remplit et FFmpeg se bloque (deadlock). On le draine sur
        // un thread daemon qui jette simplement les octets.
        Thread stderrDrainer = new Thread(() -> drainQuietly(process.getErrorStream()), "ffmpeg-stderr");
        stderrDrainer.setDaemon(true);
        stderrDrainer.start();

        // Lecture du flux -progress sur stdout
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("out_time_us=")) {
                    String value = line.substring("out_time_us=".length()).trim();
                    try {
                        long us = Long.parseLong(value);
                        double done = us / 1_000_000.0;
                        if (totalSeconds > 0) {
                            double frac = Math.min(done / totalSeconds, 1.0);
                            progressCallback.accept(frac,
                                    String.format("%.0f%%", frac * 100));
                        }
                    } catch (NumberFormatException ignore) {
                        // ligne "N/A" en debut de flux, on ignore
                    }
                } else if (line.equals("progress=end")) {
                    progressCallback.accept(1.0, "100%");
                }
            }
        }

        return process.waitFor();
    }

    private static void drainQuietly(InputStream stream) {
        try {
            byte[] buf = new byte[4096];
            while (stream.read(buf) != -1) { /* jette */ }
        } catch (IOException ignore) { }
    }

    /** Interroge ffprobe pour recuperer la duree du fichier en secondes. */
    private double probeDurationSeconds(String inputPath)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                ffprobePath,
                "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                inputPath);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = null;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (out == null) {
                    out = line.trim();
                }
            }
        }
        p.waitFor();
        try {
            return out == null ? 0 : Double.parseDouble(out.trim());
        } catch (NumberFormatException e) {
            return 0; // duree inconnue (ex: flux live) -> progression indeterminee
        }
    }
}
