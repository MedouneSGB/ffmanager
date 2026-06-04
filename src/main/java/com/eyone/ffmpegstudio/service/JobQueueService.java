package com.eyone.ffmpegstudio.service;

import com.eyone.ffmpegstudio.model.Job;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Gere la file de jobs.
 *
 * v1 : un seul worker (pool de taille 1) -> les jobs s'executent l'un apres
 * l'autre. C'est le comportement le plus previsible, surtout pour de la
 * compression (CPU-bound) ou paralleliser ralentirait tout.
 * On rendra la taille configurable plus tard.
 *
 * IMPORTANT : la liste 'jobs' est observable et lue par l'UI. Toute modif
 * des Property d'un Job depuis un thread de fond DOIT passer par
 * Platform.runLater (fait ici via le callback).
 */
public class JobQueueService {

    private final ObservableList<Job> jobs = FXCollections.observableArrayList();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ffmpeg-worker");
        t.setDaemon(true); // ne bloque pas la fermeture de l'app
        return t;
    });
    private final Map<Job, Future<?>> futures = new ConcurrentHashMap<>();
    private final FFmpegRunner runner;

    public JobQueueService(FFmpegRunner runner) {
        this.runner = runner;
    }

    public ObservableList<Job> getJobs() { return jobs; }

    /** Ajoute un job a la file et le programme pour execution. */
    public void submit(Job job) {
        jobs.add(job);
        Future<?> future = executor.submit(() -> process(job));
        futures.put(job, future);
    }

    private void process(Job job) {
        // Si annule alors qu'il etait encore en attente
        if (job.getStatus() == Job.Status.ANNULE) return;

        job.markStarted();
        Platform.runLater(() -> job.setStatus(Job.Status.EN_COURS));
        try {
            int code = runner.run(job, (frac, msg) -> Platform.runLater(() -> {
                job.progressProperty().set(frac);
                job.messageProperty().set(msg);
                job.updateElapsed();
            }));
            Platform.runLater(() -> {
                if (job.getStatus() == Job.Status.ANNULE) {
                    cleanupOutputFile(job);
                    return;
                }
                job.markFinished();
                job.setStatus(code == 0 ? Job.Status.TERMINE : Job.Status.ECHEC);
                if (code != 0) {
                    job.messageProperty().set("FFmpeg a echoue (code " + code + ")");
                    cleanupOutputFile(job);
                }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Platform.runLater(() -> { 
                job.markFinished(); 
                job.setStatus(Job.Status.ANNULE); 
                cleanupOutputFile(job);
            });
        } catch (Exception e) {
            Platform.runLater(() -> {
                job.markFinished();
                job.setStatus(Job.Status.ECHEC);
                job.messageProperty().set(e.getMessage());
                cleanupOutputFile(job);
            });
        } finally {
            futures.remove(job);
        }
    }

    private void cleanupOutputFile(Job job) {
        try {
            java.io.File out = job.getOutput();
            if (out != null && out.exists()) {
                if (out.delete()) {
                    System.out.println("Fichier de sortie partiel supprime : " + out.getAbsolutePath());
                }
            }
        } catch (Exception e) {
            System.err.println("Erreur suppression fichier partiel : " + e.getMessage());
        }
    }

    /**
     * Annule un job. Si en cours, tue le process FFmpeg.
     * Si encore en attente, l'empeche de demarrer.
     */
    public void cancel(Job job) {
        job.setStatus(Job.Status.ANNULE);
        Process p = job.getProcess();
        if (p != null && p.isAlive()) {
            p.destroy(); // SIGTERM ; destroyForcibly() si recalcitrant
        }
        Future<?> f = futures.get(job);
        if (f != null) f.cancel(true);
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
