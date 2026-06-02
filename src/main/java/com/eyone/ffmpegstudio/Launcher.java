package com.eyone.ffmpegstudio;

/**
 * Point d'entrée du fat-jar (jpackage / shade).
 * JavaFX exige que la classe principale du jar ne soit pas une sous-classe
 * d'Application — Launcher contourne cette restriction.
 */
public class Launcher {
    public static void main(String[] args) {
        App.main(args);
    }
}
