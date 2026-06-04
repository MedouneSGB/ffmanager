package com.eyone.ffmpegstudio.model;

import javafx.beans.property.*;
import java.io.File;

/**
 * Un job de traitement FFmpeg. Les champs "live" (statut, progression, message)
 * sont des Property JavaFX : l'UI s'y abonne et se met a jour toute seule.
 */
public class Job {

    public enum Status { EN_ATTENTE, EN_COURS, TERMINE, ECHEC, ANNULE }

    private final String source;
    private final File output;
    private final Preset preset;
    private final String videoCodec;
    private final Integer crf;
    private final String resolution;
    private final String audioBitrate;
    /** Flags FFmpeg bruts saisis par l'utilisateur (peut etre vide). */
    private final String extraArgs;

    private final ObjectProperty<Status> status =
            new SimpleObjectProperty<>(Status.EN_ATTENTE);
    private final DoubleProperty progress = new SimpleDoubleProperty(0);
    private final StringProperty message = new SimpleStringProperty("");
    private final StringProperty elapsedTime = new SimpleStringProperty("-");

    private long startedAt;
    private long finishedAt;

    /** Reference vers le process en cours, pour pouvoir l'annuler (process.destroy()). */
    private volatile Process process;

    public Job(File source, File output, Preset preset, String extraArgs) {
        this(source.getAbsolutePath(), output, preset, "Auto", null, "Auto", "Auto", extraArgs);
    }

    public Job(File source, File output, Preset preset, String videoCodec, Integer crf, String resolution, String audioBitrate, String extraArgs) {
        this(source.getAbsolutePath(), output, preset, videoCodec, crf, resolution, audioBitrate, extraArgs);
    }

    public Job(String source, File output, Preset preset, String extraArgs) {
        this(source, output, preset, "Auto", null, "Auto", "Auto", extraArgs);
    }

    public Job(String source, File output, Preset preset, String videoCodec, Integer crf, String resolution, String audioBitrate, String extraArgs) {
        this.source = source;
        this.output = output;
        this.preset = preset;
        this.videoCodec = videoCodec == null ? "Auto" : videoCodec;
        this.crf = crf;
        this.resolution = resolution == null ? "Auto" : resolution;
        this.audioBitrate = audioBitrate == null ? "Auto" : audioBitrate;
        this.extraArgs = extraArgs == null ? "" : extraArgs.trim();
    }

    public String getSource()       { return source; }

    public String getSourceName() {
        if (source.startsWith("http://") || source.startsWith("https://")) {
            int lastSlash = source.lastIndexOf('/');
            if (lastSlash > 0 && lastSlash < source.length() - 1) {
                String sub = source.substring(lastSlash + 1);
                int q = sub.indexOf('?');
                return q > 0 ? sub.substring(0, q) : sub;
            }
            return source;
        } else {
            return new File(source).getName();
        }
    }
    public File getOutput()       { return output; }
    public Preset getPreset()     { return preset; }
    public String getVideoCodec()  { return videoCodec; }
    public Integer getCrf()        { return crf; }
    public String getResolution()  { return resolution; }
    public String getAudioBitrate() { return audioBitrate; }
    public String getExtraArgs()  { return extraArgs; }

    public ObjectProperty<Status> statusProperty()  { return status; }
    public DoubleProperty progressProperty()         { return progress; }
    public StringProperty messageProperty()          { return message; }
    public StringProperty elapsedTimeProperty()      { return elapsedTime; }

    public Status getStatus()        { return status.get(); }
    public void setStatus(Status s)  { status.set(s); }

    public void markStarted() {
        startedAt = System.currentTimeMillis();
    }

    public void markFinished() {
        finishedAt = System.currentTimeMillis();
        elapsedTime.set(formatDuration((finishedAt - startedAt) / 1000L));
    }

    /** Appele periodiquement depuis le thread de fond pour afficher le temps ecoule. */
    public void updateElapsed() {
        if (startedAt == 0) return;
        long secs = (System.currentTimeMillis() - startedAt) / 1000L;
        elapsedTime.set(formatDuration(secs));
    }

    private static String formatDuration(long totalSecs) {
        long h = totalSecs / 3600;
        long m = (totalSecs % 3600) / 60;
        long s = totalSecs % 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, s);
        return String.format("%d:%02d", m, s);
    }

    public Process getProcess()            { return process; }
    public void setProcess(Process p)      { this.process = p; }
}
