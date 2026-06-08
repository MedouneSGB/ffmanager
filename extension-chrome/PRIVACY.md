# Politique de confidentialité — FFmpeg Studio Linker

_Dernière mise à jour : 8 juin 2026_

**FFmpeg Studio Linker** est une extension de navigateur qui détecte les flux
vidéo de la page que vous consultez et permet de les envoyer en un clic à
l'application de bureau **FFmpeg Studio** installée sur votre ordinateur.

## En résumé

- **Nous ne collectons aucune donnée personnelle.**
- **Aucune donnée n'est transmise à l'éditeur ni à un quelconque serveur tiers.**
- **Aucune publicité, aucun traçage, aucun service d'analytique.**
- Toutes les opérations restent **locales à votre machine** (sauf les requêtes
  que votre navigateur effectue déjà vers les sites que vous visitez).

## Données traitées et finalité

| Donnée | Pourquoi | Où elle va |
|--------|----------|------------|
| URLs des requêtes réseau de l'onglet actif (via `webRequest`) | Détecter les flux vidéo (ex. `.m3u8`, `.mp4`) présents sur la page | Traitées localement dans le navigateur ; jamais envoyées à un tiers |
| Contenu de la page active (via le script de contenu) | Repérer les lecteurs et liens vidéo | Traité localement, non stocké |
| Réglages de l'extension (via `storage`) | Mémoriser vos préférences (ex. dossier de sortie, préréglage) | Stockés localement dans votre navigateur |
| Playlist HLS de la vidéo détectée | Lister les qualités disponibles | Téléchargée depuis le serveur de la vidéo que vous consultez déjà |
| Flux/URL que vous choisissez d'envoyer | Lancer le téléchargement/la lecture dans l'app | Envoyé uniquement à l'application locale via `http://localhost:8555` |

## Communications réseau

L'extension communique avec :

1. **L'application FFmpeg Studio sur votre propre machine** (`http://localhost:8555`)
   pour transmettre l'URL choisie et vos préférences. Cette adresse ne quitte
   jamais votre ordinateur.
2. **Le serveur d'origine de la vidéo** que vous consultez, uniquement pour
   récupérer la playlist HLS et en lister les qualités — exactement comme le
   ferait votre navigateur en lisant la vidéo.

Aucune autre destination réseau n'est contactée.

## Justification des permissions

- **`webRequest`** : observer les URLs des requêtes de l'onglet pour y détecter
  des flux vidéo.
- **`activeTab`** : agir sur l'onglet courant lorsque vous cliquez sur l'extension.
- **`storage`** : enregistrer vos préférences localement.
- **`host_permissions: <all_urls>`** : les vidéos pouvant se trouver sur
  n'importe quel site, la détection doit pouvoir s'exécuter sur tout domaine.
  Aucune donnée de navigation n'est collectée ni exfiltrée.

## Conservation et partage

L'extension ne conserve aucune donnée en dehors des réglages que vous définissez
(stockés localement). Aucune donnée n'est vendue, louée ou partagée.

## Contact

Pour toute question : **medounesibygeorgesbalde@gmail.com**
