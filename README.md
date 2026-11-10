# Nexa — assistente vocale IA per Android

Nexa è un assistente vocale personale per Android. Gli parli, il "cervello" (Claude di Anthropic) capisce cosa vuoi e
Nexa agisce sul telefono: apre app, imposta sveglie, chiama, manda SMS, accende la torcia e tanto altro.
Poi ti risponde a voce.

Pensata per l'installazione diretta tramite APK, senza Play Store, e compatibile con telefoni **senza servizi Google**
(ad esempio vivo con OriginOS versione Cina).

## Cosa sa fare (Fase 1)

| Comando di esempio | Cosa fa |
|---|---|
| "Che ore sono?" / "Quanta batteria ho?" | Legge data, ora, batteria e rete |
| "Apri WhatsApp" | Apre qualsiasi app installata |
| "Svegliami domani alle 7" | Imposta una sveglia (anche ripetuta) |
| "Timer di 10 minuti" | Avvia un timer |
| "Chiama Marco" | Cerca in rubrica e chiama, **dopo la tua conferma** |
| "Scrivi a Giulia che arrivo tra 5 minuti" | Prepara l'SMS e lo invia **dopo la tua conferma** |
| "Accendi la torcia" | Torcia accesa o spenta |
| "Alza il volume della musica al 70%" | Volume di media, suoneria, sveglia e notifiche |
| "Metti in pausa" / "Canzone successiva" | Controlla la musica in qualsiasi app |
| "Apri le impostazioni del Wi-Fi" | Apre le sezioni delle impostazioni |
| "Cerca su internet ricette di tiramisù" | Apre il browser |
| "Portami in piazza Duomo a Milano" | Apre le mappe |
| "Crea un evento dal dentista giovedì alle 15" | Prepara l'evento nel calendario |
| Domande generali | Risponde direttamente |

Le azioni delicate (chiamate e SMS) chiedono sempre conferma: rispondi "sì" o "no" a voce, oppure usa i pulsanti.

## Come funziona

```
Microfono → riconoscimento vocale (offline, Vosk) → Claude (decide quali strumenti usare)
         → strumenti sul telefono → risposta di Claude → sintesi vocale
```

- **Riconoscimento vocale offline (Vosk)**: funziona senza Google e senza internet. Al primo avvio scarica il modello
  italiano, circa 50 MB, una volta sola.
- **Cervello**: Claude Haiku 5.5 di default (veloce ed economico). Nelle impostazioni puoi passare a Sonnet 5.5 o a Opus 5.5.
- **Voce**: usa il motore di sintesi vocale installato sul telefono.

## Costi

Serve una chiave API di Anthropic: l'abbonamento Claude Pro **non** include l'API. L'API si paga a consumo, con una
ricarica prepagata. Con Haiku 5.5 un comando costa circa 0,0005 $: con 50 comandi al giorno si spende circa 1 € al mese.

## Installazione sul telefono

1. **Scarica l'APK**: dal telefono apri la pagina *Releases* di questo repository GitHub ("Nexa — ultima versione")
   e tocca `Nexa.apk`.
2. **Installa**: apri il file. Su OriginOS, se compare il "controllo di sicurezza" o il blocco delle app esterne,
   consenti l'installazione da questa fonte. Se il sistema vuole un account vivo per verificare l'app, puoi saltare
   o disattivare la verifica in *Impostazioni → Sicurezza*.
3. **Sblocca le impostazioni con restrizioni** (Android 13 e successivi, solo se un permesso risulta bloccato):
   *Impostazioni → App → Nexa → ⋮ (in alto a destra) → Consenti impostazioni con restrizioni*.
4. **Apri Nexa** e segui le impostazioni, numerate da 1 a 7:
   1. Incolla la chiave API (la crei su <https://console.anthropic.com/settings/keys>, dopo aver ricaricato qualche euro).
   2. Tocca **Scarica voce italiana**.
   3. Controlla la **voce di risposta**. Se il telefono non ha una voce italiana, installa un motore di sintesi
      vocale, come *sherpa-onnx TTS Engine* (con una voce italiana) o *RHVoice*, e impostalo come motore
      preferito nelle impostazioni di sintesi vocale.
   4. Scegli il modello (Haiku è consigliato).
   5. Concedi i permessi.
   6. **Ottimizzazione OriginOS**: consenti il consumo della batteria in background, attiva l'avvio automatico e
      blocca Nexa nelle app recenti (tieni premuta la scheda e tocca il lucchetto).
5. **Avvio rapido**: apri le Impostazioni rapide (scorri dall'alto), tocca modifica e aggiungi il riquadro
   **"Ehi Nexa"**. Un tocco e Nexa ti ascolta.

Per gli aggiornamenti basta scaricare e installare il nuovo `Nexa.apk` sopra quello vecchio: impostazioni e voce
restano dove sono.

## Compilare da soli

Servono JDK 17 o successivo e l'SDK Android (piattaforma 36).

```bash
./gradlew :app:testDebugUnitTest   # test dell'agente (con un server Claude finto)
./gradlew :app:assembleRelease     # APK in app/build/outputs/apk/release/
```

Ogni push su GitHub compila l'APK in automatico (`.github/workflows/build-apk.yml`) e lo pubblica nella release
"ultima-versione".

### Firma dell'APK

Il file `keystore/nexa-debug.jks` è una chiave di firma **di sviluppo** inclusa nel repository: così ogni APK
compilato si installa sopra il precedente. Se il repository è pubblico, chiunque può firmare un APK "Nexa" con la
stessa chiave. Per uso personale va bene, ma puoi usare una chiave privata: crea un nuovo keystore e imposta le
variabili d'ambiente `NEXA_KEYSTORE_PASSWORD`, `NEXA_KEY_ALIAS` e `NEXA_KEY_PASSWORD`, ad esempio come
*secrets* di GitHub Actions. Dopo il cambio di chiave dovrai disinstallare una volta la versione vecchia.

## Struttura del codice

```
app/src/main/java/com/nexa/assistant/
├── NexaApp.kt              # Application: contiene impostazioni e controller
├── NexaController.kt       # Coordina ascolto → Claude → azioni → voce, e le conferme sì/no
├── MainActivity.kt         # Avvio, permesso microfono, avvio da assistente o riquadro rapido
├── NexaTileService.kt      # Riquadro "Ehi Nexa" nelle Impostazioni rapide
├── agent/
│   ├── NexaAgent.kt        # Ciclo con Claude (SDK ufficiale Anthropic): strumenti e risultati
│   └── SystemPrompt.kt     # Personalità e regole di Nexa
├── tools/
│   ├── NexaTool.kt         # Interfaccia degli strumenti
│   └── PhoneTools.kt       # Gli strumenti della Fase 1
├── speech/                 # Vosk (offline), riconoscitore di sistema, sintesi vocale
├── data/                   # Impostazioni e chiave API cifrata con l'Android Keystore
└── ui/                     # Interfaccia Jetpack Compose
```

Per aggiungere uno strumento basta implementare `NexaTool` e registrarlo in `phoneTools()`.

## Prossime fasi

- **Fase 2**: lettura e risposta alle notifiche (WhatsApp, Telegram…), calendario completo, memoria delle preferenze.
- **Fase 3**: agente sullo schermo con il servizio di accessibilità. Nexa legge lo schermo, tocca e scrive nelle app
  al posto tuo ("apri WhatsApp e scrivi a Marco…").
- **Fase 4**: sempre in ascolto con la parola di attivazione "Ehi Nexa" e ruolo di assistente predefinito.
- **Fase 5** (facoltativa): Shizuku per i comandi di sistema avanzati.
