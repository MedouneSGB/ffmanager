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

    DOWNLOAD_STREAM("Télécharger un flux réseau (M3U8/HLS/direct)",
            "-c", "copy", "-bsf:a", "aac_adtstoasc"),

    // -c copy : pas de reencodage, juste un remux. Quasi instantane, CPU nul.
    REMUX_MP4("Remux en MP4 (rapide, sans reencodage)",
            "-c", "copy", "-bsf:a", "aac_adtstoasc"),

    // QSV : encodeur H.264 materiel Intel Quick Sync. ~5-10x plus rapide que
    // libx264, quasi sans impact CPU. global_quality 23 ≈ CRF 23 subjectif.
    COMPRESS_WEB_QSV("Compresser pour le web — GPU Intel QSV (H.264)",
            "-c:v", "h264_qsv", "-global_quality", "23",
            "-c:a", "aac", "-b:a", "128k"),

    // Fallback CPU si QSV non disponible (pilote manquant, VM, etc.).
    COMPRESS_WEB("Compresser pour le web — CPU (H.264)",
            "-c:v", "libx264", "-crf", "23", "-preset", "medium",
            "-c:a", "aac", "-b:a", "128k"),

    COMPRESS_STRONG_QSV("Compression forte — GPU Intel QSV (720p)",
            "-c:v", "h264_qsv", "-global_quality", "28",
            "-vf", "scale=-2:720",
            "-c:a", "aac", "-b:a", "96k"),

    COMPRESS_STRONG("Compression forte — CPU (720p)",
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
        return buildArgs(ffmpegPath, source.getAbsolutePath(), output, "Auto", null, "Auto", "Auto", extraArgs);
    }

    public List<String> buildArgs(String ffmpegPath, String source, File output, String extraArgs) {
        return buildArgs(ffmpegPath, source, output, "Auto", null, "Auto", "Auto", extraArgs);
    }

    /**
     * Construit la liste complete d'arguments FFmpeg pour ce preset avec des surcharges d'options avancées.
     */
    public List<String> buildArgs(String ffmpegPath, File source, File output,
                                  String videoCodec, Integer crf, String resolution, String audioBitrate,
                                  String extraArgs) {
        return buildArgs(ffmpegPath, source.getAbsolutePath(), output, videoCodec, crf, resolution, audioBitrate, extraArgs);
    }

    public List<String> buildArgs(String ffmpegPath, String source, File output,
                                  String videoCodec, Integer crf, String resolution, String audioBitrate,
                                  String extraArgs) {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-y");                 // ecrase la sortie si elle existe
        cmd.add("-i");
        cmd.add(source);

        // On construit la liste des arguments de base du preset, modifiée par les options avancées
        List<String> baseArgs = new ArrayList<>(args);

        // --- 1. Surcharges vidéo (uniquement si le preset n'est pas un extracteur audio pur)
        boolean isAudioOnly = baseArgs.contains("-vn");
        
        if (!isAudioOnly) {
            // Surcharge du Codec Vidéo
            if (videoCodec != null && !videoCodec.equalsIgnoreCase("Auto")) {
                // Supprimer les définitions existantes de codec vidéo
                removeArgWithNext(baseArgs, "-c:v");
                removeArgWithNext(baseArgs, "-codec:v");
                
                if (videoCodec.equalsIgnoreCase("copy")) {
                    baseArgs.add("-c:v");
                    baseArgs.add("copy");
                    // Pour le remux, on vire le CRF, la qualité, les presets, les filtres vidéo
                    removeArgWithNext(baseArgs, "-crf");
                    removeArgWithNext(baseArgs, "-global_quality");
                    removeArgWithNext(baseArgs, "-preset");
                    removeArgWithNext(baseArgs, "-vf");
                    removeArgWithNext(baseArgs, "-filter:v");
                } else if (videoCodec.equalsIgnoreCase("h264_qsv")) {
                    baseArgs.add("-c:v");
                    baseArgs.add("h264_qsv");
                    removeArgWithNext(baseArgs, "-crf"); // QSV utilise global_quality, pas crf
                } else if (videoCodec.equalsIgnoreCase("libx264")) {
                    baseArgs.add("-c:v");
                    baseArgs.add("libx264");
                    removeArgWithNext(baseArgs, "-global_quality");
                } else if (videoCodec.equalsIgnoreCase("libx265")) {
                    baseArgs.add("-c:v");
                    baseArgs.add("libx265");
                    removeArgWithNext(baseArgs, "-global_quality");
                }
            }

            // Déterminer le codec effectif actuel pour savoir comment appliquer la qualité
            String currentVideoCodec = getArgValue(baseArgs, "-c:v");
            if (currentVideoCodec == null) {
                currentVideoCodec = getArgValue(baseArgs, "-codec:v");
            }

            // Surcharge de la qualité (CRF / global_quality)
            if (crf != null && (currentVideoCodec == null || !currentVideoCodec.equalsIgnoreCase("copy"))) {
                removeArgWithNext(baseArgs, "-crf");
                removeArgWithNext(baseArgs, "-global_quality");
                if (currentVideoCodec != null && currentVideoCodec.contains("qsv")) {
                    baseArgs.add("-global_quality");
                    baseArgs.add(String.valueOf(crf));
                } else {
                    baseArgs.add("-crf");
                    baseArgs.add(String.valueOf(crf));
                }
            }

            // Surcharge de la résolution (vf scale)
            if (resolution != null && !resolution.equalsIgnoreCase("Auto") && (currentVideoCodec == null || !currentVideoCodec.equalsIgnoreCase("copy"))) {
                removeArgWithNext(baseArgs, "-vf");
                removeArgWithNext(baseArgs, "-filter:v");
                int height = 720;
                if (resolution.contains("1080")) height = 1080;
                else if (resolution.contains("720")) height = 720;
                else if (resolution.contains("480")) height = 480;
                else if (resolution.contains("360")) height = 360;
                
                baseArgs.add("-vf");
                baseArgs.add("scale=-2:" + height);
            }
        }

        // --- 2. Surcharges audio
        if (audioBitrate != null && !audioBitrate.equalsIgnoreCase("Auto")) {
            if (audioBitrate.equalsIgnoreCase("Mute")) {
                // Couper le son complet
                removeArgWithNext(baseArgs, "-c:a");
                removeArgWithNext(baseArgs, "-b:a");
                removeArgWithNext(baseArgs, "-q:a");
                removeArgWithNext(baseArgs, "-vn"); // Just in case
                if (!baseArgs.contains("-an")) {
                    baseArgs.add("-an");
                }
            } else if (audioBitrate.equalsIgnoreCase("Copy")) {
                // Copier le son sans réencodage
                removeArgWithNext(baseArgs, "-an");
                removeArgWithNext(baseArgs, "-c:a");
                removeArgWithNext(baseArgs, "-b:a");
                removeArgWithNext(baseArgs, "-q:a");
                baseArgs.add("-c:a");
                baseArgs.add("copy");
            } else {
                // Bitrate audio spécifique (ex: 128k, 192k, 320k)
                removeArgWithNext(baseArgs, "-an");
                removeArgWithNext(baseArgs, "-b:a");
                removeArgWithNext(baseArgs, "-q:a");
                
                // Si on a pas déjà d'encodeur audio, on met par défaut aac (ou libmp3lame si preset MP3)
                String currentAudioCodec = getArgValue(baseArgs, "-c:a");
                if (currentAudioCodec == null || currentAudioCodec.equalsIgnoreCase("copy")) {
                    removeArgWithNext(baseArgs, "-c:a");
                    baseArgs.add("-c:a");
                    if (this == Preset.EXTRACT_AUDIO_MP3) {
                        baseArgs.add("libmp3lame");
                    } else {
                        baseArgs.add("aac");
                    }
                }
                baseArgs.add("-b:a");
                baseArgs.add(audioBitrate);
            }
        }

        cmd.addAll(baseArgs);

        if (extraArgs != null && !extraArgs.isBlank()) {
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

    private static void removeArgWithNext(List<String> args, String target) {
        for (int i = 0; i < args.size(); i++) {
            if (args.get(i).equals(target)) {
                args.remove(i); // supprime le drapeau
                if (i < args.size()) {
                    args.remove(i); // supprime la valeur
                }
                i--; // ajuste l'index
            }
        }
    }

    private static String getArgValue(List<String> args, String target) {
        for (int i = 0; i < args.size() - 1; i++) {
            if (args.get(i).equals(target)) {
                return args.get(i + 1);
            }
        }
        return null;
    }

    @Override
    public String toString() { return label; }
}
