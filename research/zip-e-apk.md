# Zip e APK in Memy: come installare, come estrarre, quali rischi

Ricerca per l'issue #12. Telefono di riferimento: vivo X300 Pro, OriginOS 7 Cina, Android 16, senza servizi Google.
App: minSdk 29, targetSdk 36. Nessun codice dell'app è stato modificato.

## In breve

1. Per installare un APK si riusa il metodo che usa già `UpdateManager.install()`: `PackageInstaller`, una sessione, `commit()`. Per gli APK di altre app però serve `setRequireUserAction(USER_ACTION_REQUIRED)`, così il sistema chiede **sempre** conferma.
2. Prima di installare, Memy legge l'APK con `getPackageArchiveInfo(path, GET_PERMISSIONS | GET_SIGNING_CERTIFICATES | GET_SERVICES)` e mostra una scheda: nome, icona, pacchetto, versione, permessi a rischio, impronta SHA-256 della firma, e "nuova app" oppure "aggiornamento".
3. Se c'è già un'app con lo stesso pacchetto e la firma è diversa, Android rifiuta l'installazione. Memy deve dirlo **prima** e non proporre di disinstallare l'app vecchia: si perderebbero i suoi dati.
4. Per gli APK divisi (XAPK/APKS/APKM) si usa una sola sessione e si scrivono dentro tutti gli `.apk`. Gli OBB vanno in `Android/obb/<pacchetto>/`, che un'app normale potrebbe non poter scrivere: va provato sul telefono.
5. Gli zip si aprono con `ZipFile` di commons-compress (già nel progetto) letto **da file**. Limiti consigliati: 10.000 voci, 1 GB per singolo file, 4 GB in tutto, rapporto di compressione massimo 100:1, si contano i byte davvero scritti. Ogni percorso viene controllato contro "zip slip".
6. I file estratti vanno in `Download/Memy/<nome-zip>/…` tramite MediaStore, come già fa `DocumentStore`. Prima si controlla lo spazio libero.
7. Gli zip con password **non** si possono aprire con commons-compress. Per ora Memy lo dice e basta, senza aggiungere librerie.

---

## 1. Installare un APK su Android 16

### Come funziona (API ufficiale)

Il flusso è quello che Memy ha già in `update/UpdateManager.kt` e `UpdateReceiver.kt`:

1. `packageManager.packageInstaller.createSession(SessionParams(MODE_FULL_INSTALL))`
2. `session.openWrite(nome, 0, dimensione)`, poi si copia l'APK e si chiama `fsync`
3. `session.commit(pendingIntent.intentSender)`. Il `PendingIntent` deve essere `FLAG_MUTABLE`, perché il sistema ci aggiunge lo stato (già così).
4. Il receiver riceve `EXTRA_STATUS`:
   - `STATUS_PENDING_USER_ACTION`: si avvia l'`Intent` in `EXTRA_INTENT`, che apre la schermata di conferma del sistema (già gestito in `UpdateReceiver`);
   - `STATUS_SUCCESS`: installata;
   - `STATUS_FAILURE_*`: fallita, il motivo è in `EXTRA_STATUS_MESSAGE`.

Fonti: [PackageInstaller](https://developer.android.com/reference/android/content/pm/PackageInstaller), [SessionParams](https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams), [commit AOSP su STATUS_PENDING_USER_ACTION](https://android.googlesource.com/platform/frameworks/base/+/c17bbf82ce4b%5E%21/), [CommonsWare: Installing Apps Using PackageInstaller](https://commonsware.com/Q/pages/chap-pkg-001).

### Conferma dell'utente

- `setRequireUserAction` esiste da Android 12 (API 31). `USER_ACTION_NOT_REQUIRED` è solo una **richiesta**. Il sistema la accetta soltanto per gli aggiornamenti di app di cui Memy risulta l'installatore, ed è così che funziona l'auto-aggiornamento di Memy. ([API diff 31](https://developer.android.com/sdk/api_diff/31/changes/android.content.pm.PackageInstaller.SessionParams))
- **Per gli APK di terzi: `setRequireUserAction(USER_ACTION_REQUIRED)`.** Così la conferma del sistema compare sempre, e si aggiunge a quella di Memy. Non va riusato `install()` così com'è, perché imposta `NOT_REQUIRED` e `setAppPackageName(context.packageName)`.
- Va aggiunto `setAppPackageName(pacchettoLetto)`, così il sistema rifiuta subito un file che contiene un'app diversa da quella mostrata all'utente. Va aggiunto anche `setPackageSource(PACKAGE_SOURCE_LOCAL_FILE)` (API 33+; per l'auto-aggiornamento resta `DOWNLOADED_FILE`).
- Per le sessioni c'è un solo receiver. Per distinguere "aggiornamento di Memy" da "app condivisa", basta mettere un extra nell'`Intent` del `PendingIntent`, oppure usare il `sessionId`.

### Nuova installazione e aggiornamento

| Caso | Come capirlo | Cosa succede |
|---|---|---|
| Nuova app | `pm.getPackageInfo(pkg, 0)` lancia `NameNotFoundException` | Conferma "Installare X?" |
| Aggiornamento, stessa firma, versione maggiore o uguale | `longVersionCode` installato <= quello nuovo | Conferma "Aggiornare X?". I dati restano. |
| Versione più vecchia (downgrade) | `longVersionCode` nuovo < installato | Android rifiuta (`INSTALL_FAILED_VERSION_DOWNGRADE`). Memy lo dice prima. |
| **Firma diversa** | certificato SHA-256 diverso | Android rifiuta (`STATUS_FAILURE_CONFLICT`, `INSTALL_FAILED_UPDATE_INCOMPATIBLE`). L'unica strada sarebbe disinstallare e perdere i dati. **Memy non lo propone in automatico.** Al massimo lo spiega. |
| APK troppo vecchio | `applicationInfo.targetSdkVersion` < 23 (sicuro) o < 24 (Android 15+, da verificare) | Android 14 e successivi bloccano le app con target molto vecchio. Memy avvisa. ([Android 14, cambiamenti](https://developer.android.com/about/versions/14/behavior-changes-all)) |

Firma diversa a parità di pacchetto è il segnale classico di APK **ripubblicato o modificato** (mod, falsi). Va mostrato come avviso rosso anche quando l'app non è installata, se l'utente sa da dove viene.

### Permesso "installa app sconosciute"

- `REQUEST_INSTALL_PACKAGES` è già nel manifest. L'utente deve concedere a Memy "Installa app sconosciute". Il controllo è `pm.canRequestPackageInstalls()` e le impostazioni si aprono con `ACTION_MANAGE_UNKNOWN_APP_SOURCES` (già in `openInstallPermissionSettings()`). Il permesso si dà per singola app, non per tutto il telefono. ([Applivery: unknown sources](https://docs.applivery.com/en/app-distribution/platforms/android/unknown-sources/))
- Dopo che l'utente torna dalle impostazioni si ricontrolla `canRequestPackageInstalls()`, e solo allora si crea la sessione.

### OriginOS / vivo (non c'è documentazione ufficiale: va provato sul telefono)

- vivo dichiara che fa una "verifica della firma delle applicazioni" e un "monitoraggio delle installazioni" ([vivo security book](https://privacy.vivo.com.cn/static/pdf/security-book-pdf.pdf)). In pratica, dopo la conferma di Android può comparire **la scansione di sicurezza vivo** (i管家 / gestore della sicurezza), con un risultato del tipo "rischio rilevato" e una casella "ho capito il rischio" da spuntare prima di "Installa". Lo raccontano utenti e guide di terzi: [Reddit r/fossdroid](https://redlib.belloworld.it/r/fossdroid/comments/1ftkcy2/can_you_change_the_package_installer_app), [guida Binance per vivo](https://www.binance.com/en/android-install).
- L'installatore di sistema vivo fa parte del gestore della sicurezza e non va rimosso (rischio di bootloop, secondo lo stesso thread).
- Sulle ROM cinesi può essere chiesta la password dell'account vivo o un'ulteriore verifica. Questo lo controlla il sistema, Memy non può e non deve aggirarlo.
- **Conseguenza per Memy:** dopo `commit()` l'esito può arrivare molto tardi, o non arrivare se l'utente chiude la schermata vivo. Lo stato "installazione in corso" deve avere una via d'uscita ("Annulla" / timeout di qualche minuto), e alla riapertura di Memy si controlla con `getPackageInfo` se l'app risulta installata.
- La "verifica sviluppatore" di Google (dal 30/09/2026 in alcuni paesi, ovunque nel 2027) vale solo per i dispositivi certificati Google. Un telefono cinese senza servizi Google probabilmente non ne è toccato, ma nessuna fonte lo conferma. ([Android developer verification](https://developer.android.com/developer-verification/guides), [The Register](https://www.theregister.com/2025/08/26/android_developer_verification_sideloading))

## 2. Leggere un APK prima di installarlo

L'APK va prima copiato **su file** (in `cacheDir/incoming/`), perché `getPackageArchiveInfo` vuole un percorso, non un `Uri`. Poi:

```kotlin
val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SERVICES
val info = if (Build.VERSION.SDK_INT >= 33)
    pm.getPackageArchiveInfo(apk.path, PackageManager.PackageInfoFlags.of(flags.toLong()))
else pm.getPackageArchiveInfo(apk.path, flags)
    ?: error("Non è un APK valido")              // null = file rotto o non firmato
val app = info.applicationInfo!!.apply { sourceDir = apk.path; publicSourceDir = apk.path } // serve per nome e icona
val nome = app.loadLabel(pm).toString()
val icona = app.loadIcon(pm)
val pacchetto = info.packageName
val versione = "${info.versionName} (${info.longVersionCode})"
val certs = info.signingInfo?.apkContentsSigners.orEmpty()
val sha256 = certs.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString(":") { b -> "%02X".format(b) } }
```

- `GET_SIGNATURES` è deprecato: va usato `GET_SIGNING_CERTIFICATES` (API 28+). ([PackageManager](https://developer.android.com/reference/android/content/pm/PackageManager), [Stack Overflow: firma di un APK non installato](https://kiwix.lema.org/stackoverflow.com_en_all_2023-11/questions/40088528/how-can-we-check-the-signature-of-a-not-yet-installed-apk))
- Il trucco `sourceDir`/`publicSourceDir` serve perché altrimenti `loadIcon`/`loadLabel` restituiscono l'icona e il nome generici. ([ApplicationInfo](https://developer.android.com/reference/android/content/pm/ApplicationInfo))
- Per confrontare la firma con l'app installata: `pm.getPackageInfo(pkg, GET_SIGNING_CERTIFICATES).signingInfo.apkContentsSigners` e confronto degli SHA-256. In alternativa `pm.hasSigningCertificate(pkg, certBytes, CERT_INPUT_RAW_X509)`. Se `signingInfo.hasMultipleSigners()` si confrontano tutti.
- Se `signingInfo` è `null`, Memy mostra "firma non leggibile" e lo tratta come rischio.

### Permessi da segnalare

Regola automatica: per ogni permesso in `info.requestedPermissions`, `pm.getPermissionInfo(nome, 0).protection == PermissionInfo.PROTECTION_DANGEROUS` significa "pericoloso" (posizione, microfono, fotocamera, contatti, SMS, chiamate, ecc.). ([PermissionInfo](https://developer.android.com/reference/android/content/pm/PermissionInfo))

Poi c'è una lista fissa di permessi "speciali" che non sono `dangerous` ma sono i preferiti dei malware. Vanno sempre evidenziati in rosso:

| Permesso / componente | Perché è rischioso |
|---|---|
| servizio con `BIND_ACCESSIBILITY_SERVICE` (si vede con `GET_SERVICES`) | legge lo schermo e preme pulsanti al posto dell'utente |
| `BIND_DEVICE_ADMIN` / receiver amministratore | può bloccare o cancellare il telefono |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | legge tutte le notifiche (codici OTP) |
| `SYSTEM_ALERT_WINDOW` | finestre sopra le altre app (phishing) |
| `REQUEST_INSTALL_PACKAGES`, `REQUEST_DELETE_PACKAGES` | installa o rimuove altre app |
| `READ_SMS`, `RECEIVE_SMS`, `SEND_SMS` | codici bancari, SMS a pagamento |
| `MANAGE_EXTERNAL_STORAGE` | tutti i file |
| `QUERY_ALL_PACKAGES`, `PACKAGE_USAGE_STATS` | sa quali app hai e quando le usi |
| `BIND_VPN_SERVICE` | vede il traffico di rete |
| `WRITE_SETTINGS`, `READ_CALL_LOG`, `CALL_PHONE` | impostazioni, chiamate |

La scheda mostra: "Chiede N permessi, di cui K a rischio:" seguito dall'elenco in italiano. Un'app "torcia" che chiede SMS e accessibilità deve saltare all'occhio.

## 3. APK divisi, XAPK, APKS, APKM e OBB

- **APK divisi (split):** un `base.apk` più vari `split_config.arm64_v8a.apk`, `split_config.it.apk`, `split_config.xxhdpi.apk`. Si installano con **una sola sessione**, una chiamata `session.openWrite(nomeUnico, 0, size)` per ciascun file, poi un `commit()` solo. Tutti devono avere lo stesso pacchetto, la stessa versione e la stessa firma. ([PackageInstaller.Session](https://developer.android.com/reference/android/content/pm/PackageInstaller.Session))
- **Formati contenitore** (sono tutti zip; nessuno è uno standard Google):
  - **XAPK** (APKPure): `manifest.json` (`package_name`, `version_code`, `split_apks: [{file, id}]`, `expansions: [{file, install_path}]`) più gli `.apk` e, a volte, `Android/obb/<pkg>/main.<ver>.<pkg>.obb`. ([guida XAPK](https://www.mintlify.com/KilzaNiko/apk-extractor-adb/features/xapk-compilation), [AnExplorer](https://anexplorer.io/it/files/xapk))
  - **APKS** (SAI / bundletool): zip di `.apk` divisi, a volte con `info.json` o `toc.pb`.
  - **APKM** (APKMirror): zip di `.apk` con `info.json`. Le versioni nuove sono **cifrate** e si aprono solo con l'app di APKMirror, quindi Memy deve dire che non le supporta. ([bestforandroid](https://bestforandroid.com/how-to-install-apk-xapk-files.md))
- **Consiglio pratico:** non serve leggere i manifest. Memy apre lo zip, prende tutti i `*.apk` alla radice (o in una sola sottocartella), li estrae in `cacheDir`, li legge con `getPackageArchiveInfo` e controlla che pacchetto, versione e firma coincidano. Il base è quello senza `splitName`. Gli split di lingua o densità di troppo non fanno danni: Android installa anche quelli. Se dentro ci sono **APK di pacchetti diversi**, è uno zip con più app: Memy chiede quale installare, una sessione per app.
- **OBB:** vanno in `/storage/emulated/0/Android/obb/<pkg>/`. Da Android 11 le app normali **non possono** scrivere nelle cartelle `Android/obb` di altre app, nemmeno con `MANAGE_EXTERNAL_STORAGE`. ([Android 11 storage](https://developer.android.com/about/versions/11/privacy/storage), [Android 11 storage FAQ](https://medium.com/androiddevelopers/android-11-storage-faq-78cefea52b7c)). C'è un'eccezione non confermata per le app con `REQUEST_INSTALL_PACKAGES` ([Stack Overflow](https://kiwix.lema.org/stackoverflow.com_en_all_2023-11/questions/63033786/unable-to-access-obb-directory-on-android-11)). **Raccomandazione:** prima si installa l'APK, poi Memy prova a scrivere l'OBB. Se non ci riesce, lo estrae in `Download/Memy/<nome>/Android/obb/<pkg>/` e spiega all'utente di spostarlo con il file manager di vivo. Le XAPK con OBB sono rare (giochi grandi): non vale la pena fare di più.

## 4. Estrarre zip in sicurezza

### Libreria

Va usato **`org.apache.commons.compress.archivers.zip.ZipFile`** (1.28.0, già in `build.gradle.kts` per Parakeet), aperto **da file**, non da stream:
- legge la directory centrale, quindi si possono elencare le voci e le dimensioni dichiarate **prima** di estrarre (serve per l'anteprima "cosa c'è dentro");
- permette di scegliere la codifica dei nomi;
- `java.util.zip.ZipFile` va bene uguale per gli zip UTF-8, ma non gestisce bene i nomi GBK.

Il file condiviso va copiato a pezzi in `cacheDir/incoming/` (stream, non `ByteArray`). **`readShared` oggi legge tutto in memoria con un limite di 20 MB: per zip e APK serve un ramo separato** che copia su file fino a un limite più alto (consiglio 2 GB) e controlla lo spazio libero.

### Controlli e limiti numerici consigliati

| Rischio | Controllo | Limite consigliato |
|---|---|---|
| **Zip slip** (`../`, percorsi assoluti) | per ogni voce: si rifiuta se il nome contiene `..` come segmento, inizia con `/` o `\`, contiene `:` o `\u0000`. Si normalizza `\` in `/` e alla fine si verifica che `File(dest, nome).canonicalPath` inizi con `dest.canonicalPath + "/"` | voce rifiutata, si estrae il resto e lo si segnala |
| **Symlink** | `entry.isUnixSymlink` porta a saltare la voce | sempre |
| **Zip bomb** | si contano i byte **davvero scritti**, non `entry.size`, che si può falsificare. Ci si ferma appena si supera un limite | per voce 1 GB; totale 4 GB e comunque <= spazio libero - 500 MB |
| Rapporto di compressione | `scritti / compressedSize` | > 100 su voci oltre 1 MB porta a fermarsi |
| Troppe voci | `entries.count()` prima di iniziare | 10.000 |
| Nomi lunghi | lunghezza del singolo segmento | 255 byte; percorso 1.000 caratteri |
| Sovrascrittura | si estrae sempre in una **cartella nuova e vuota** (`<nome-zip>`, `<nome-zip> (2)`, …) | sempre |
| Zip annidati | **non** si estraggono in automatico. Si mostrano come file `.zip`; l'utente può chiedere di aprirli (profondità massima 1). Eccezione: gli `.apk` dentro uno zip si propongono per l'installazione | profondità 1 |
| Zip con password | `zipFile.canReadEntryData(entry) == false` oppure `entry.generalPurposeBit.usesEncryption()` | messaggio "zip protetto da password, non supportato" |

Le linee guida ufficiali Android su zip slip: [Zip Path Traversal](https://developer.android.com/privacy-and-security/risks/zip-path-traversal), [Google Play: Fixing a Zip Path Traversal Vulnerability](https://support.google.com/faqs/answer/9294009). Dicono anche che il controllo regge solo se la cartella di destinazione è "sicura" e vuota.

**Nota Android 14+:** con targetSdk >= 34, `java.util.zip.ZipFile` e `ZipInputStream` lanciano già `ZipException` sui nomi con `..` o che iniziano con `/` ([ZipPathValidator](https://developer.android.com/reference/dalvik/system/ZipPathValidator)). **Questa protezione non copre commons-compress**, quindi il controllo va scritto comunque, ed è una funzione di poche righe da coprire con un test.

### Codifiche dei nomi (cinese, CP437, UTF-8)

- Se il bit 11 del "general purpose flag" è attivo, il nome è UTF-8. Lo fanno quasi tutti i programmi moderni e Android. commons-compress lo rispetta da solo.
- Gli zip creati su **Windows cinese** (WinRAR, 7-Zip vecchi, Esplora risorse) spesso non hanno il bit e usano **GBK (CP936)**. Gli zip occidentali vecchi usano **CP437**. Letti come UTF-8 producono nomi illeggibili.
- **Raccomandazione:** si apre con `ZipFile.builder().setFile(f).setCharset(UTF_8).setUseUnicodeExtraFields(true).get()`. Se un nome contiene il carattere `�` (sostituzione) o il decoder UTF-8 "stretto" fallisce, si riapre con `Charset.forName("GBK")`. Visto il telefono cinese, GBK è il ripiego giusto prima di CP437. ([ZipFile commons-compress](https://commons.apache.org/proper/commons-compress/apidocs/org/apache/commons/compress/archivers/zip/ZipFile.html), [Zip in commons-compress: encoding](https://commons.apache.org/proper/commons-compress/zip.html))

### Zip con password

- commons-compress **non** decifra zip cifrati, né con il vecchio ZipCrypto né con AES ([commons-compress zip](https://commons.apache.org/proper/commons-compress/zip.html)). Nemmeno `java.util.zip` lo fa.
- L'alternativa sarebbe la libreria `net.lingala.zip4j` (Apache 2.0, supporta ZipCrypto e AES), ma è una dipendenza nuova. **Consiglio:** per ora non aggiungerla. Memy riconosce lo zip cifrato, lo dice e suggerisce il file manager di vivo, che di solito apre gli zip con password. Si può aggiungere zip4j solo se l'utente lo chiede davvero spesso.

## 5. Dove salvare i file estratti

- **Download/Memy/<nome-zip>/<sottocartelle>** con MediaStore, come `DocumentStore.save()`: `MediaStore.Downloads.EXTERNAL_CONTENT_URI`, `RELATIVE_PATH = "Download/Memy/<nome-zip>/<sottocartella>/"`, `IS_PENDING = 1` durante la scrittura e poi `0`. La collezione Downloads accetta **qualsiasi tipo di file** e non richiede permessi su Android 10+. Le sottocartelle si creano da sole. ([MediaStore.Downloads](https://developer.android.com/reference/android/provider/MediaStore.Downloads), [Accedere ai file condivisi](https://developer.android.com/training/data-storage/shared/media))
- Se il file esiste già, MediaStore rinomina da solo in "nome (1).ext". Con la cartella nuova per ogni estrazione non succede quasi mai.
- I file con estensioni "di sistema" (`.apk` compreso) si salvano senza problemi in Download. Gli APK però **non** vanno estratti in Download a meno che l'utente lo chieda: per installarli bastano `cacheDir`, da cancellare dopo il commit.
- Il MIME di ogni file si ricava da `MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"`.
- Con il potere `MANAGE_EXTERNAL_STORAGE` già attivo si potrebbe scrivere con `File` normali in qualunque cartella scelta dall'utente. **Consiglio:** si usa MediaStore come default (funziona senza poteri speciali) e si accetta una cartella diversa solo se il potere è attivo, sempre con lo stesso controllo dei percorsi.
- **Spazio:** prima di estrarre si confronta la somma delle dimensioni dichiarate con `StorageManager.getAllocatableBytes(StorageManager.UUID_DEFAULT)` (API 26+) o con `Environment.getExternalStorageDirectory().usableSpace`, lasciando 500 MB di margine. Durante l'estrazione il conteggio dei byte reali fa da seconda barriera. ([StorageManager](https://developer.android.com/reference/android/os/storage/StorageManager))
- I file temporanei (`cacheDir/incoming`, APK estratti) si cancellano a fine operazione. `cacheDir` può essere svuotata dal sistema, quindi non va usata per cose che l'utente deve ritrovare.

## Flusso consigliato per Memy

1. **Condivisione** di `.zip`, `.apk`, `.xapk`, `.apks` o `.apkm`: copia a pezzi in `cacheDir/incoming/` (massimo 2 GB e controllo dello spazio).
2. **Zip:** elenco delle voci, con numero, dimensione totale, cartelle e APK trovati. Memy chiede: "Estraggo in Download/Memy/<nome>?" e/o "Dentro c'è X.apk, vuoi installarlo?".
3. **APK o bundle:** scheda di anteprima (nome, icona, versione, nuova o aggiornamento, firma uguale o diversa, permessi a rischio, origine) e pulsante "Installa".
4. Poi `PackageInstaller` con `USER_ACTION_REQUIRED`: conferma di Android, eventuale scansione vivo, esito nel receiver e messaggio in chat.
5. Pulizia dei file temporanei.

Due tool per l'agente bastano: `zip_list`/`zip_extract` e `apk_inspect`/`apk_install`, con la conferma umana obbligatoria su estrazione e installazione, come per gli altri poteri di Memy. Il modello non deve mai poter installare senza il tocco dell'utente.

## Cosa non è stato verificato

- Il comportamento esatto dell'installatore vivo su OriginOS 7 (scansione, account vivo, esito tardivo): non c'è documentazione ufficiale, va provato sul telefono.
- La scrittura in `Android/obb` di altre app con `REQUEST_INSTALL_PACKAGES`: fonti in contraddizione.
- La soglia minima di `targetSdk` su Android 16 (23 o 24).
