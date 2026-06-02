package com.eyone.ffmpegstudio.model;

import javafx.beans.property.*;
import java.io.File;

/**
 * Un job de traitement FFmpeg. Les champs "live" (statut, progression, message)
 * sont des Property JavaFX : l'UI s'y abonne et se met a jour toute seule.
 */
public class Job {

    public enum Status { EN_ATTENTE, EN_COURS, TERMINE, ECHEC, ANNULE }

    private final File source;
    private final File output;
    private final Preset preset;
    /** Flags FFmpeg bruts saisis par l'utilisateur (peut etre vide). */
    private final String extraArgs;

    private final ObjectProperty<Status> status =
            new SimpleObjectProperty<>(Status.EN_ATTENTE);
    private final DoubleProperty progress = new SimpleDoubleProperty(0);
    private final StringProperty message = new SimpleStringProperty("");

    /** Reference vers le process en cours, pour pouvoir l'annuler (process.destroy()). */
    private volatile Process process;

    public Job(File source, File output, Preset preset, String extraArgs) {
        this.source = source;
        this.output = output;
        this.preset = preset;
        this.extraArgs = extraArgs == null ? "" : extraArgs.trim();
    }

    public File getSource()       { return source; }
    public File getOutput()       { return output; }
    public Preset getPreset()     { return preset; }
    public String getExtraArgs()  { return extraArgs; }

    public ObjectProperty<Status> statusProperty() { return status; }
    public DoubleProperty progressProperty()        { return progress; }
    public StringProperty messageProperty()         { return message; }

    public Status getStatus()        { return status.get(); }
    public void setStatus(Status s)  { status.set(s); }

    public Process getProcess()            { return process; }
    public void setProcess(Process p)      { this.process = p; }
}
