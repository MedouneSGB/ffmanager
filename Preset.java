package com.eyone.ffmpegstudio.model;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Un preset traduit une intention ("Compresser pour le web") en arguments FFmpeg.
 *
 * REGLE D'OR : on ne construit JAMAIS la commande par concatenation de chaines.
 * Chaque argument est un element de liste distinct. Ca evite l'injection et
 * les bugs d'espaces dans les chemins Windows ("Program Files").
 */
public enum Preset {

    // -c copy : pas de reencodage, juste un remux. Quasi instantane, CPU nul.
    REMUX_MP4("Remux en MP4 (rapide, sans reencodage)",
            "-c", "copy", "-bsf:a", "aac_adtstoasc"),

    // Reencodage H.264, CRF 23 = bon compromis qualite/taille.
    COMPRESS_WEB("Compresser pour le web (H.264)",
            "-c:v", "libx264", "-crf", "23", "-preset", "medium",
            "-c:a", "aac", "-b:a", "128k"),

    // Compression plus agressive pour le partage (WhatsApp, etc.).
    COMPRESS_STRONG("Compression forte (petite taille)",
            "-c:v", "libx264", "-crf", "28", "-preset", "slow",
            "-vf", "scale=-2:720", "-c:a", "aac", "-b:a", "96k"),

    EXTRACT_AUDIO_MP3("Extraire l'audio (MP3)",
            "-vn", "-c:a", "libmp3lame", "-q:a", "2");

    private final String label;
    private final List<String> args;

    Preset(String label, String... args) {
        this.label = label;
        this.args = Arrays.asList(args);
    }

    public String getLabel() { return label; }

    /**
     * Construit la liste complete d'arguments FFmpeg pour ce preset.
     * Ordre : [ffmpeg] -y -i <source> <args du preset> <extra> <output>
     */
    public List<String> buildArgs(String ffmpegPath, File source, File output, String extraArgs) {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-y");                 // ecrase la sortie si elle existe
        cmd.add("-i");
        cmd.add(source.getAbsolutePath());
        cmd.addAll(args);
        if (extraArgs != null && !extraArgs.isBlank()) {
            // simple split sur espaces ; suffisant pour la v1
            for (String token : extraArgs.trim().split("\\s+")) {
                cmd.add(token);
            }
        }
        // -progress pipe:1 -> sortie cle=valeur facile a parser
        cmd.add("-progress");
        cmd.add("pipe:1");
        cmd.add("-nostats");
        cmd.add(output.getAbsolutePath());
        return cmd;
    }

    @Override
    public String toString() { return label; }
}
