package com.eyone.ffmpegstudio.service;

import com.eyone.ffmpegstudio.model.Job;

import java.io.BufferedReader;
import java.io.IOException;
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

    private final String ffmpegPath;
    private final String ffprobePath;

    public FFmpegRunner(String ffmpegPath, String ffprobePath) {
        this.ffmpegPath = ffmpegPath;
        this.ffprobePath = ffprobePath;
    }

    /**
     * Execute le job. progressCallback recoit (fraction 0..1, message).
     * Retourne le code de sortie de FFmpeg (0 = succes).
     */
    public int run(Job job, BiConsumer<Double, String> progressCallback)
            throws IOException, InterruptedException {

        double totalSeconds = probeDurationSeconds(job.getSource().getAbsolutePath());

        List<String> cmd = job.getPreset().buildArgs(
                ffmpegPath, job.getSource(), job.getOutput(), job.getExtraArgs());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false); // on garde stderr separe (logs FFmpeg)
        Process process = pb.start();
        job.setProcess(process); // pour l'annulation

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
        String out;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            out = r.readLine();
        }
        p.waitFor();
        try {
            return out == null ? 0 : Double.parseDouble(out.trim());
        } catch (NumberFormatException e) {
            return 0; // duree inconnue (ex: flux live) -> progression indeterminee
        }
    }
}
