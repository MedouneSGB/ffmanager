# Mentions des composants tiers (Third-Party Notices)

FFmpeg Studio est distribué avec, ou s'appuie sur, des logiciels tiers listés
ci-dessous. Chaque composant reste soumis à sa propre licence. Ce document
satisfait les obligations d'attribution et d'information de ces licences.

> **Synthèse licence de l'œuvre distribuée.** Le code source propre à FFmpeg
> Studio est sous licence **MIT** (voir `LICENSE`). Comme les binaires
> officiels embarquent **vlcj (GPLv3)** et des **builds GPL de FFmpeg**,
> l'application **distribuée sous forme binaire** est, dans son ensemble,
> régie par la **GPLv3**. Le code source correspondant est disponible
> publiquement sur le dépôt du projet, conformément à la GPL.

---

## Binaires embarqués dans les distributions

### FFmpeg / ffprobe
- **Éditeur** : FFmpeg project — https://ffmpeg.org
- **Licence** : GPLv2 ou ultérieure / LGPLv2.1 ou ultérieure selon la
  configuration de build. Les binaires fournis par les paquets
  `@ffmpeg-installer/ffmpeg` et `@ffprobe-installer/ffprobe` utilisés par le
  CI sont des **builds GPL**.
- **Usage** : exécutables appelés en sous-processus, et copiés dans les
  paquets de distribution (dossier `app/`).
- **Code source** : https://github.com/FFmpeg/FFmpeg

### VLC / libVLC (VideoLAN)
- **Éditeur** : VideoLAN — https://www.videolan.org
- **Licence** : libVLC (cœur) sous **LGPLv2.1 ou ultérieure** ; certains
  modules/plugins VLC sont sous **GPLv2 ou ultérieure**.
- **Usage** : bibliothèque native de décodage/lecture (libvlc + libvlccore +
  dossier `plugins/`) embarquée dans le dossier `vlc/` des distributions.
- **Code source** : https://code.videolan.org/videolan/vlc

---

## Dépendances Java (liées au programme)

### vlcj
- **Éditeur** : Caprica Software Limited (uk.co.caprica) — https://github.com/caprica/vlcj
- **Licence** : **GPLv3** (une licence commerciale séparée est proposée par l'éditeur).
- **Usage** : liaison Java vers libVLC pour le moteur de lecture intégré.

### vlcj-javafx
- **Éditeur** : Caprica Software Limited — https://github.com/caprica/vlcj-javafx
- **Licence** : **GPLv3**.
- **Usage** : rendu de la surface vidéo vlcj dans un `ImageView` JavaFX.

### JNA (Java Native Access)
- **Éditeur** : Java Native Access project — https://github.com/java-native-access/jna
- **Licence** : double **Apache-2.0** / **LGPLv2.1 ou ultérieure** (au choix).
- **Usage** : chargement des bibliothèques natives (libVLC, libc).

### OpenJFX (JavaFX)
- **Éditeur** : Gluon / OpenJDK — https://openjfx.io
- **Licence** : **GPLv2 avec Classpath Exception**.
- **Usage** : framework d'interface graphique.

### jsoup
- **Éditeur** : Jonathan Hedley — https://jsoup.org
- **Licence** : **MIT**.
- **Usage** : analyse HTML pour la détection de flux (MediaHunter).

---

## Obtenir le code source

Conformément aux licences GPL/LGPL, le code source complet correspondant aux
binaires distribués est disponible sur le dépôt public du projet :

    https://github.com/MedouneSGB/ffmanager

Pour les composants tiers, se référer aux liens « Code source » ci-dessus.
