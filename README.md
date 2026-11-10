# Memy — assistente vocale IA per Android

Memy è un'assistente personale per Android. Le parli, il "cervello" (Claude di Anthropic) capisce cosa vuoi e Memy
agisce sul telefono: apre e **usa le app al posto tuo**, legge e risponde alle notifiche, gestisce sveglie, calendario,
promemoria, chiamate e messaggi. Poi ti risponde con una voce naturale di ElevenLabs.

Pensata per l'installazione diretta tramite APK, senza Play Store, e compatibile con telefoni **senza servizi Google**
(ad esempio vivo con OriginOS versione Cina).

![Interfaccia di Memy](docs/memy-design.png)

## Cosa sa fare

| Area | Esempi |
|---|---|
| Conversazione | "Ciao Memy, come stai?", domande di ogni tipo, con **ricerca web** (meteo, notizie, risultati) su tutti i modelli |
| Usare le app | "Scrivi a Marco su WhatsApp che arrivo tra dieci minuti", "Cerca un video di ricette su YouTube", "Attiva il Bluetooth" |
| Notifiche | "Leggimi le notifiche", "Rispondi a Giulia che va bene", "Cancella tutte le notifiche", "Che canzone è questa?" |
| Orologio e agenda | "Svegliami alle 7", "Timer di 10 minuti", "Cosa ho in calendario?", "Crea un evento giovedì alle 15", "Ricordami di chiamare mamma alle 18" |
| Telefono | "Chiama Marco", "Manda un SMS a Giulia", "Leggi gli ultimi SMS", "Chi mi ha chiamato?" |
| Telefono e sistema | Torcia, volume, luminosità, silenzioso, Non disturbare, musica, fotocamera, appunti, email, condivisione |
| Luoghi | "Dove mi trovo?", "Portami in piazza Duomo" |
| Memoria | "Ricordati che mia sorella si chiama Giulia": Memy lo ricorda anche nelle conversazioni future |

Le azioni con effetti reali (inviare, chiamare, pagare, eliminare, pubblicare) chiedono sempre conferma.

## Come funziona

```
Microfono → riconoscimento vocale → Claude (in streaming, con strumenti e ricerca web)
         → azioni sul telefono → voce ElevenLabs, frase per frase
```

- **Cervello**: Claude Opus 5.5, Sonnet 5.5 o Haiku 5.5, a scelta. Tutti possono cercare sul web.
- **Voce**: ElevenLabs. Nelle impostazioni scegli la voce ascoltando le anteprime; quelle segnate "Gratuita"
  funzionano con il piano gratuito. Se la chiave manca o i crediti finiscono, Memy usa la voce di riserva del telefono.
- **Ascolto**: Google sul telefono (se presente), ElevenLabs Scribe (molto preciso, usa i crediti) oppure Vosk offline.
- **Conversazione continua**: dopo ogni risposta torna ad ascoltarti. Se tocchi la sfera mentre parla, la interrompi.

## I "poteri"

In *Impostazioni → Poteri* decidi quanto può fare Memy. Ognuno si attiva dalle impostazioni di sistema.

| Potere | Cosa permette |
|---|---|
| **Controllo dello schermo** (accessibilità) | Leggere lo schermo e usare qualsiasi app: toccare, scrivere, scorrere, premere i tasti di sistema |
| **Notifiche** | Leggere le notifiche, rispondere ai messaggi, sapere cosa è in riproduzione |
| **Impostazioni di sistema** | Regolare la luminosità |
| **Non disturbare** | Silenzioso e Non disturbare |
| **Permessi** | Microfono, contatti, telefono, SMS, chiamate recenti, calendario, posizione, notifiche |

Sicurezza:

- Memy agisce solo quando glielo chiedi.
- Prima di un tocco che invia, paga, elimina o pubblica compare una richiesta di conferma. Lo decide sia Claude sia un
  controllo automatico sul testo del pulsante.
- Mentre lavora in un'altra app, in alto resta l'indicatore "Memy al lavoro": toccandolo la fermi.
- Memy non inserisce password, PIN o dati di pagamento.
- Le app che proteggono lo schermo (come quelle bancarie) non sono leggibili.

Su Android 13 e successivi, se l'interruttore dell'accessibilità è bloccato:
*Impostazioni → App → Memy → ⋮ → Consenti impostazioni con restrizioni*.

## Costi

Servono due chiavi, che restano cifrate solo sul telefono:

- **Claude (Anthropic)**, obbligatoria: <https://console.anthropic.com/settings/keys>. Si paga a consumo con una
  ricarica prepagata; l'abbonamento Claude Pro non include l'API. Una risposta breve costa indicativamente
  0,0005 $ con Haiku, 0,004 $ con Sonnet e 0,008 $ con Opus. I compiti in cui Memy usa un'app richiedono molti
  passaggi e costano di più.
- **ElevenLabs**, per la voce: <https://elevenlabs.io/app/settings/api-keys>. Il piano gratuito ha un numero limitato
  di crediti al mese; i crediti rimasti si vedono in *Impostazioni → Voce*.

## Installazione sul telefono

1. Dal telefono apri la pagina *Releases* di questo repository ("Memy — ultima versione") e scarica `Memy.apk`.
2. Apri il file e consenti l'installazione da questa fonte.
3. Apri Memy e inserisci la chiave di Claude (*Impostazioni → Chiavi*).
4. Aggiungi la chiave ElevenLabs e scegli la voce (*Impostazioni → Voce*).
5. Attiva i poteri che vuoi (*Impostazioni → Poteri*).
6. In *Impostazioni → Sempre pronta* segui i passaggi per OriginOS, così il telefono non chiude Memy.
7. Aggiungi il riquadro **"Ehi Memy"** alle Impostazioni rapide: un tocco e Memy ti ascolta.

## Aggiornamenti automatici

Ogni push su GitHub compila l'APK e aggiorna la release "ultima-versione" (`Memy.apk` e `version.json`).
All'apertura Memy controlla se c'è una versione più nuova, la scarica e la installa. La prima volta Android chiede di
consentire a Memy di installare app.

## Note sul cambio di nome

L'app si chiamava Nexa. Per non perdere impostazioni, chiavi e aggiornamenti, alcuni nomi interni restano quelli
storici: l'identificativo `com.nexa.assistant`, il file delle preferenze e la chiave di firma `keystore/nexa-debug.jks`.
L'aggiornamento cerca la release prima in `zsurann/Memy` e poi in `zsurann/Project-Nexa`, quindi funziona sia prima
sia dopo la rinomina del repository.

## Compilare da soli

Servono JDK 17 o successivo e l'SDK Android (piattaforma 36).

```bash
./gradlew :app:testDebugUnitTest --tests 'com.nexa.assistant.agent.*' --tests 'com.nexa.assistant.speech.*' --tests 'com.nexa.assistant.tools.*'
./gradlew :app:testDebugUnitTest --tests '*ScreenshotTest*'   # immagini dell'interfaccia in app/build/screenshots
./gradlew :app:assembleRelease                                # APK in app/build/outputs/apk/release/
```

Il file `keystore/nexa-debug.jks` è una chiave di firma di sviluppo inclusa nel repository, così ogni APK si installa
sopra il precedente. Il repository è pubblico, quindi chiunque potrebbe firmare un APK con la stessa chiave: per uso
personale va bene, ma puoi sostituirla impostando `NEXA_KEYSTORE_PASSWORD`, `NEXA_KEY_ALIAS` e `NEXA_KEY_PASSWORD`
(dopo il cambio va disinstallata una volta la versione vecchia).

## Struttura del codice

```
app/src/main/java/com/nexa/assistant/
├── MemyApp.kt, MemyController.kt, MainActivity.kt, MemyTileService.kt
├── agent/      # Ciclo con Claude in streaming, prompt di sistema, divisione in frasi
├── tools/      # Strumenti: base, schermo, notifiche, dati personali, sistema
├── access/     # Servizio di accessibilità e lettura delle notifiche
├── speech/     # ElevenLabs (voce, voci, Scribe), Vosk, riconoscimento di sistema
├── reminders/  # Promemoria
├── update/     # Aggiornamento automatico da GitHub
├── data/       # Impostazioni, memoria, chiavi cifrate
└── ui/         # Interfaccia Jetpack Compose
```

Per aggiungere uno strumento basta implementare `MemyTool` e registrarlo in `MemyController`.

## Prossimi passi

- Parola di attivazione "Ehi Memy" sempre in ascolto e ruolo di assistente predefinito.
- Routine ("quando arrivo a casa…") e azioni programmate.
