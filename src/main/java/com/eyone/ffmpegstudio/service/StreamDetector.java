package com.eyone.ffmpegstudio.service;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service utilitaire pour scanner une page web et ses iframes,
 * détecter les flux vidéo (m3u8, mp4, etc.) et en extraire les différentes qualités.
 */
public class StreamDetector {

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    // Regexp robuste pour identifier des liens HTTP/HTTPS contenant des fichiers de flux
    private static final Pattern STREAM_PATTERN = Pattern.compile(
            "https?://[^\"'\\s>]+\\.(?:m3u8|mp4|mpd|webm)[^\"'\\s>]*",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Représente un flux vidéo détecté avec sa qualité.
     */
    public static class DetectedStream {
        private final String url;
        private final String quality;

        public DetectedStream(String url, String quality) {
            this.url = url;
            this.quality = quality;
        }

        public String getUrl() {
            return url;
        }

        public String getQuality() {
            return quality;
        }

        @Override
        public String toString() {
            String domain = getDomainName(url);
            if (quality != null && !quality.isEmpty()) {
                return "[" + quality + "] " + domain;
            }
            return "[Flux] " + domain;
        }

        private String getDomainName(String urlStr) {
            try {
                java.net.URI uri = new java.net.URI(urlStr);
                String domain = uri.getHost();
                if (domain != null) {
                    return domain.startsWith("www.") ? domain.substring(4) : domain;
                }
            } catch (Exception e) {
                // Fallback
            }
            try {
                int start = urlStr.indexOf("://");
                if (start > 0) {
                    start += 3;
                    int end = urlStr.indexOf('/', start);
                    if (end > start) {
                        String domain = urlStr.substring(start, end);
                        return domain.startsWith("www.") ? domain.substring(4) : domain;
                    }
                    String domain = urlStr.substring(start);
                    return domain.startsWith("www.") ? domain.substring(4) : domain;
                }
            } catch (Exception e) {}
            return "Source";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof DetectedStream)) return false;
            DetectedStream that = (DetectedStream) o;
            return url.equals(that.url);
        }

        @Override
        public int hashCode() {
            return url.hashCode();
        }
    }

    /**
     * Détecte les flux de streaming disponibles dans la page et ses iframes,
     * et essaie de résoudre les différentes qualités disponibles (ex: 720p, 1080p).
     *
     * @param pageUrl L'URL de la page web à analyser.
     * @return Une liste de DetectedStream trouvés.
     */
    public static List<DetectedStream> detectStreams(String pageUrl) {
        Set<String> rawUrls = new LinkedHashSet<>();
        try {
            // Étape 1 : Charger la page principale
            Connection.Response response = Jsoup.connect(pageUrl)
                    .userAgent(USER_AGENT)
                    .timeout(12000)
                    .followRedirects(true)
                    .execute();

            String mainHtml = response.body();
            findUrlsInHtml(mainHtml, rawUrls);

            Document doc = response.parse();
            Elements iframes = doc.select("iframe");

            for (Element iframe : iframes) {
                String iframeUrl = iframe.absUrl("src");
                if (iframeUrl != null && !iframeUrl.isEmpty() && !iframeUrl.startsWith("about:")) {
                    try {
                        // Étape 2 : Charger l'iframe avec le Referer de la page parente
                        String iframeHtml = Jsoup.connect(iframeUrl)
                                .userAgent(USER_AGENT)
                                .referrer(pageUrl)
                                .timeout(10000)
                                .followRedirects(true)
                                .ignoreContentType(true)
                                .execute()
                                .body();

                        findUrlsInHtml(iframeHtml, rawUrls);
                    } catch (Exception ex) {
                        System.err.println("[StreamDetector] Impossible d'analyser l'iframe: " + iframeUrl + " - " + ex.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[StreamDetector] Erreur lors de l'analyse de l'URL " + pageUrl + " : " + e.getMessage());
        }

        // Étape 3 : Résoudre les qualités pour chaque flux brut détecté
        Set<DetectedStream> resolvedStreams = new LinkedHashSet<>();
        for (String url : rawUrls) {
            String lowerUrl = url.toLowerCase();
            if (lowerUrl.contains(".m3u8")) {
                // Ajouter le master par défaut
                resolvedStreams.add(new DetectedStream(url, "Auto (Playlist Master)"));
                // Essayer de lire et découper les qualités contenues dans le fichier m3u8
                parseM3u8Qualities(url, resolvedStreams);
            } else if (lowerUrl.contains(".mp4")) {
                resolvedStreams.add(new DetectedStream(url, "Direct MP4"));
            } else if (lowerUrl.contains(".mpd")) {
                resolvedStreams.add(new DetectedStream(url, "DASH (Master)"));
            } else if (lowerUrl.contains(".webm")) {
                resolvedStreams.add(new DetectedStream(url, "Direct WebM"));
            } else {
                resolvedStreams.add(new DetectedStream(url, "Source vidéo"));
            }
        }

        return new ArrayList<>(resolvedStreams);
    }

    private static void findUrlsInHtml(String html, Set<String> results) {
        if (html == null) return;
        Matcher matcher = STREAM_PATTERN.matcher(html);
        while (matcher.find()) {
            String url = matcher.group();
            // Nettoyer les barres obliques échappées (\/) couramment trouvées dans les JSON
            url = url.replace("\\/", "/");
            results.add(url);
        }
    }

    /**
     * Analyse un fichier de playlist master m3u8 pour en extraire les flux de qualités individuelles.
     */
    private static void parseM3u8Qualities(String masterUrl, Set<DetectedStream> results) {
        try {
            // Télécharger le contenu du fichier .m3u8
            String content = Jsoup.connect(masterUrl)
                    .userAgent(USER_AGENT)
                    .timeout(6000)
                    .ignoreContentType(true)
                    .execute()
                    .body();

            if (content == null || !content.contains("#EXTM3U")) {
                return;
            }

            String[] lines = content.split("\\r?\\n");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.startsWith("#EXT-X-STREAM-INF:")) {
                    String resolution = "Qualité inconnue";
                    
                    // Recherche de la résolution (ex: RESOLUTION=1280x720)
                    Matcher resMatcher = Pattern.compile("RESOLUTION=(\\d+x\\d+)", Pattern.CASE_INSENSITIVE).matcher(line);
                    if (resMatcher.find()) {
                        String resVal = resMatcher.group(1);
                        String[] parts = resVal.split("x");
                        if (parts.length == 2) {
                            resolution = parts[1] + "p (" + resVal + ")";
                        } else {
                            resolution = resVal;
                        }
                    } else {
                        // Fallback sur la bande passante si pas de résolution
                        Matcher bwMatcher = Pattern.compile("BANDWIDTH=(\\d+)", Pattern.CASE_INSENSITIVE).matcher(line);
                        if (bwMatcher.find()) {
                            int bw = Integer.parseInt(bwMatcher.group(1));
                            resolution = (bw / 1000) + " kbps";
                        }
                    }

                    // La ligne suivante contient le chemin ou l'URL de cette sous-playlist
                    if (i + 1 < lines.length) {
                        String streamUri = lines[i + 1].trim();
                        if (!streamUri.isEmpty() && !streamUri.startsWith("#")) {
                            String resolvedUrl = resolveRelativeUrl(masterUrl, streamUri);
                            results.add(new DetectedStream(resolvedUrl, resolution));
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[StreamDetector] Impossible de lire ou de découper la playlist m3u8 : " + masterUrl + " - " + e.getMessage());
        }
    }

    private static String resolveRelativeUrl(String baseUrl, String relativeUrl) {
        if (relativeUrl.startsWith("http://") || relativeUrl.startsWith("https://")) {
            return relativeUrl;
        }
        try {
            java.net.URI base = new java.net.URI(baseUrl);
            java.net.URI resolved = base.resolve(relativeUrl);
            return resolved.toString();
        } catch (Exception e) {
            int lastSlash = baseUrl.lastIndexOf('/');
            if (lastSlash > 0) {
                return baseUrl.substring(0, lastSlash + 1) + relativeUrl;
            }
            return relativeUrl;
        }
    }
}
