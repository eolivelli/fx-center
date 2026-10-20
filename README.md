# FX Center

A cue player for the sound technician of a small theatre show: a folder of
music and sound effects (MP3, WAV, …), shown as a list in show order, with a
big **VAI** button. Runs on a laptop (one jar, Java 11+) and on an Android
phone. The folder can be local or on Google Drive; Drive files are cached on
the device and never streamed, so the show does not depend on the theatre's
Wi‑Fi.

Built for *La fabbrica di cioccolato* (see
[eolivelli/matilda](https://github.com/eolivelli/matilda)); nothing in it is
specific to that show except the default folder.

## Guida rapida (per il fonico)

1. **Installare.** Computer: scaricare `fx-center-vX.Y.Z.jar` dall'ultima
   [release](https://github.com/eolivelli/fx-center/releases) e aprirlo con
   doppio clic (serve Java 11 o più recente). Telefono Android: scaricare
   `fx-center-vX.Y.Z-android-debug.apk` e installarlo.
2. **La prima volta, con Internet**, l'app scarica tutti i brani dalla
   cartella Google Drive dello spettacolo e li tiene sul dispositivo. Da lì in
   poi funziona anche senza rete. Premere **Aggiorna** quando qualcuno ha
   aggiunto o cambiato un file nella cartella.
3. **Durante lo spettacolo:** toccare un brano per selezionarlo, poi **VAI**
   (sul computer anche la barra spaziatrice). **STOP** ferma (Esc), **PAUSA**
   mette in pausa (P). Quando un brano finisce l'app seleziona il successivo
   ma **non** lo fa partire: parte solo al prossimo VAI.
4. **Documenti** apre il copione (Google Doc) e gli altri documenti che stanno
   nella cartella.
5. I nomi dei file danno l'ordine: `MUS-01 Sigla.mp3`, `MUS-02 …`, `SFX-07 …`.
   Blu = musiche (`MUS-`), verde = effetti (`SFX-`/`FX-`). Un brano grigio con
   `⤓` non è ancora stato scaricato.
6. Un'altra cartella: **Google Drive…** e incollare il link (la cartella va
   condivisa con *chiunque abbia il link*), oppure **Cartella locale…** sul
   computer.

## How it works

```
core/      shared model, Drive client, cache, ShowController   (Java 11, no platform APIs)
desktop/   Swing app + javax.sound player, shaded into one jar  (Maven)
android/   Android app + MediaPlayer, compiles core/ from source (Gradle, AGP)
```

- **Google Drive without login.** The folder is shared "anyone with the link"
  and read with a Drive API key restricted to the Drive API
  (`core/.../Defaults.java`). The app lists the folder with
  `files.list` and downloads with `alt=media`; Google Docs are not downloaded
  but opened by their link.
- **Cache.** `~/.fx-center/cache/<folder>/<fileId>/<name>` on the desktop,
  app-private storage on Android. A file is served from the cache only when
  the checksum Drive reports matches the one recorded after a verified
  download, so a replaced track is fetched again and a torn download is never
  played. The last listing is kept too, so the app starts offline.
- **Show rules** live in `ShowController` and are unit-tested: GO plays the
  selected cue, finishing advances the selection without playing, STOP keeps
  the selection.

## Building

```sh
mvn verify                                   # JDK 11: core + desktop, tests, desktop/target/fx-center-*.jar
cd android && ./gradlew test assembleDebug   # JDK 17 to run Gradle; needs the Android SDK (local.properties)
```

Releases: push a tag `vX.Y.Z` and the `release` workflow attaches the jar and
the debug APK to the GitHub release. Bump `versionCode`/`versionName` in
`android/app/build.gradle` first so the APK installs as an update.

Manual checks on a developer machine (they need a sound card / a display):

```sh
java -cp desktop/target/test-classes:desktop/target/fx-center-0.1.0-SNAPSHOT.jar \
    dev.olivelli.fxcenter.desktop.PlayCheck some.mp3
java -Dfxcenter.home=/tmp/fxh -jar desktop/target/fx-center-0.1.0-SNAPSHOT.jar
```

## Licence

Apache-2.0. The desktop jar bundles mp3spi/JLayer/tritonus-share (LGPL-2.1)
for MP3 decoding and Gson (Apache-2.0).
