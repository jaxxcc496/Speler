# Speler — Android-muziekspeler

Een eenvoudige Android-muziekspeler gebouwd met Kotlin, Jetpack Compose en AndroidX Media3/ExoPlayer.

## Functies
- Playlists maken en lokaal bewaren.
- Audio- en videobestanden toevoegen via de Android-bestandskiezer.
- Directe HTTP(S)-audio-URL's toevoegen aan een playlist.
- Nummers afspelen, pauzeren en naar het vorige/volgende nummer gaan.
- Nummers en playlists verwijderen.

## Belangrijk
YouTube-links werken niet als directe audio-URL. Gebruik alleen directe links naar bestanden waarvoor je toestemming hebt.

## APK bouwen via GitHub Actions
1. Open het tabblad **Actions**.
2. Start de workflow **Build APK** of push een nieuwe commit.
3. Als de build slaagt, download je het artifact **speler-apk** bij de workflowrun.

De Android-build is nog niet lokaal getest; controleer de GitHub Actions-build op fouten.