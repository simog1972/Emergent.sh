# Clip Manager (Android)

App Android semplice per raccogliere il contenuto degli appunti (clipboard) e organizzarlo in liste con un titolo.
Ogni lista genera automaticamente un file `.txt` sul telefono.

**APK pronto:** [`release/ClipManager.apk`](release/ClipManager.apk) (Android 7.0+)

## Come si usa

- **Apri l'app** → il testo che hai negli appunti viene salvato come clip (nella scheda *CLIP*).
  Torna nell'app dopo ogni copia e il nuovo testo viene aggiunto (i doppioni consecutivi vengono ignorati).
- **Riunire clip sotto un nome:** nella scheda *CLIP* tocca i clip per selezionarli → **Riunisci** → scegli un nuovo titolo o una lista esistente.
- **Prima il titolo, poi i clip:** scheda *LISTE* → **+ Nuovo titolo** (spunta "Salva qui tutti i prossimi clip").
  Da quel momento ogni nuovo clip finisce in quella lista, finché premi **Stop** sulla barra arancione.
- **File TXT:** ogni lista è salvata in `Documents/ClipManager/<titolo>.txt` e si aggiorna da sola a ogni modifica
  (aggiunta, eliminazione, modifica, rinomina). Dalla lista puoi anche **Condividi TXT** o **Copia tutto**.
- Tieni premuto un clip o una lista per: copia, modifica, sposta, rinomina, elimina.

### Salvare senza aprire l'app

Android (dalla versione 10) non permette a nessuna app di leggere gli appunti in background, quindi ci sono tre scorciatoie:

1. Seleziona un testo in qualsiasi app → menu di selezione → **Salva in Clip Manager**.
2. **Condividi** un testo → **Clip Manager**.
3. Aggiungi il riquadro **Salva clip** nelle Impostazioni rapide (tendina in alto → matita/modifica):
   dopo aver copiato, abbassa la tendina e toccalo.

## Installazione

Scarica `release/ClipManager.apk` sul telefono e aprilo. Android chiederà di consentire l'installazione da
"origini sconosciute" per l'app con cui lo apri (browser, file manager): è normale per un'app non presa dal Play Store.

## Compilare

Il progetto non usa Gradle: `build.sh` usa direttamente gli strumenti Android dei pacchetti Ubuntu/Debian.

```sh
sudo apt-get install aapt apksigner zipalign dalvik-exchange android-sdk-platform-23
./build.sh        # -> build/ClipManager.apk
```

L'APK è firmato con `keystore/clipmanager.jks` (password `clipmanager`): usa sempre la stessa chiave,
altrimenti Android non permette di aggiornare l'app già installata.
