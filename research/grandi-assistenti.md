# Cosa sanno fare ChatGPT, Claude e Gemini che Memy non sa

Ticket wayfinder: issue #2. Ricerca fatta il 12 novembre 2026.

## In breve

Memy copre già bene la parte "fai le cose sul telefono": app, messaggi, notifiche, chiamate, sveglie,
promemoria, calendario del telefono, documenti, ricerca web e controllo dello schermo. Su questo è alla pari
o più avanti delle app ChatGPT e Claude, che sul telefono non toccano le altre app.

Quello che le manca riguarda soprattutto:

1. **Vedere il mondo** con la fotocamera, non solo lo schermo.
2. **Prendere l'iniziativa**: compiti programmati e il riassunto del mattino.
3. **Ricordare le conversazioni**, non solo una lista di fatti.
4. **Entrare nei servizi**: posta vera, calendari online, cloud.
5. **Fare da interprete** e gestire le chiamate.

Quasi tutto si può fare senza servizi Google, con le API Claude (che leggono immagini), il servizio di
accessibilità, CameraX, il ricevitore di notifiche, sherpa-onnx e Groq. Le eccezioni sono parlare dentro una
chiamata telefonica (Android non lo permette alle app normali) e la generazione di immagini (Claude non le
crea).

## Cosa fa già Memy (punto di partenza)

Dal [README](../README.md) e dagli strumenti in `app/src/main/java/com/nexa/assistant/tools/`:

- **Telefono:** `open_app`, `list_apps`, `make_call`, `send_sms`, `read_sms`, `read_call_log`, `find_contact`,
  torcia, volume, luminosità, suoneria, Non disturbare, impostazioni, appunti, condivisione testo.
- **Tempo:** `set_alarm`, `set_timer`, `set_reminder`/`list_reminders`/`delete_reminder`,
  `read_calendar`, `create_calendar_event` (calendario del telefono).
- **Notifiche:** leggerle, rispondere, aprirle, cancellarle; brano in riproduzione, controllo musica.
- **Schermo:** `read_screen`, `look_at_screen` (screenshot letto da Claude), `tap_element`, `tap_position`,
  `type_text`, `scroll`, `press_button`, `wait`.
- **Documenti:** creare, modificare, leggere, aprire, condividere, cancellare PDF e Word.
- **Mondo:** ricerca web (strumento lato server di Claude), posizione, navigazione, `open_web`.
- **Memoria:** `remember` / `forget`, cioè una lista di fatti brevi.
- **Fotocamera:** `open_camera` apre solo l'app, senza guardare cosa inquadra.
- **Voce:** parola di attivazione sul telefono, risposta frase per frase, interruzione a voce.

## Cosa offrono oggi gli altri (novembre 2026)

- **ChatGPT:** voce con video dalla fotocamera e condivisione schermo, anche se il nuovo modello GPT-Live non
  le supporta ancora ([OpenAI, voce](https://help.openai.com/en/articles/9617425-chatgpt-voice);
  [The Decoder](https://the-decoder.com/openai-adds-live-video-and-screen-sharing-to-advanced-voice-mode/)).
  Ha una memoria che legge tutte le chat passate e si aggiorna col tempo
  ([OpenAI, "memory dreaming"](https://openai.com/index/chatgpt-memory-dreaming);
  [Business Today](https://www.businesstoday.in/technology/news/story/openai-upgrades-chatgpt-with-smarter-history-search-and-memory-511406-2026-01-19)).
  Ha compiti programmati, che dal giugno 2026 hanno preso anche il posto del riassunto quotidiano Pulse
  ([OpenAI, Pulse](https://help.openai.com/articles/12293630-chatgpt-pulse);
  [Gigazine](https://gigazine.net/gsc_news/en/20260619-chatgpt-scheduled-tasks/)).
  Si collega a posta e calendario iCloud (27 ottobre)
  ([note di rilascio](https://help.openai.com/en/articles/6825453-chatgpt-release-notes)).
  Altre novità: Images 2.6, slide, modalità "Work" per compiti lunghi
  ([Enterprise AI World](https://www.enterpriseaiworld.com/Articles/News/News/OpenAI-Rolls-Out-ChatGPT-Work-Powered-by-Latest-Model-GPT-5.6-175678.aspx)).
- **Claude (app):** la voce usa Opus e Sonnet e i connettori, così può leggere e scrivere nella posta, nel
  calendario, in Slack e in Docs ([The Decoder](https://the-decoder.com/claudes-voice-mode-now-runs-on-anthropics-most-capable-models-across-all-platforms/);
  [TechRadar](https://www.techradar.com/computing/artificial-intelligence/claude-ai-adds-a-genuinely-useful-voice-mode-to-its-mobile-app-that-can-look-inside-your-inbox-and-calendar)).
  Ricorda la cronologia delle chat ([note di rilascio](https://support.claude.com/en/articles/12138966-release-notes)).
  Ha connettori per Spotify, Uber e altri servizi ([Dataconomy](https://dataconomy.com/2026/04/24/anthropic-launches-new-app-connectors-for-claude-users/)).
- **Gemini su Android:** Gemini Live con fotocamera e schermo condivisi, più Guided Vision (dal 1 ottobre
  2026), che descrive la scena a voce e guida l'inquadratura ([Google](https://blog.google/innovation-and-ai/products/gemini-app/guided-vision-gemini-live/)).
  Altre funzioni:
  - Daily Brief, un riassunto mattutino di posta, agenda e cose da fare.
  - Gemini Spark, un agente che lavora 24 ore su 24 nel cloud, anche a telefono spento
    ([The Next Web](https://thenextweb.com/news/google-gemini-app-daily-brief-redesign-io-2026);
    [Tom's Guide](https://www.tomsguide.com/ai/google-gemini/google-unveils-gemini-spark-a-24-7-personal-ai-agent-that-could-be-a-game-changer-for-agentic-ai)).
  - Gemini Intelligence, che usa app di cibo e taxi per conto tuo
    ([Neowin](https://www.neowin.net/news/googles-new-gemini-agent-on-android-can-now-use-3rd-party-apps-to-run-errands-for-you/)).
  - Traduzione dal vivo in Gemini Live ([Android Authority](https://androidauthority.com/gemini-live-ui-design-live-translation-3705267)).
  - Sui Pixel: traduzione delle chiamate, filtro chiamate, Magic Cue e "Call for Me", che fa telefonate al
    posto tuo ([Google, chiamate Pixel](https://blog.google/products/pixel/calling-updates-pixel-10/);
    [Android Authority, Call for Me](https://www.androidauthority.com/google-pixel-11-gemini-call-for-me-3714744/)).
- **Siri (iOS 27):** cerca nel "contesto personale" (posta, messaggi, file, foto), capisce cosa c'è sullo
  schermo e agisce nelle app. Il motore è basato su Gemini e per ora parla solo inglese
  ([MacRumors](https://macrumors.com/guide/ios-27-siri);
  [PhoneArena](https://www.phonearena.com/news/siris-biggest-change-in-a-decade-is-here-narrowing-the-gap-on-gemini_id183192)).

Nota: molte fonti sono articoli di stampa. Date e piani a pagamento possono variare per Paese e dispositivo.

## Capacità mancanti

Legenda. **Valore**: quanto conta nell'uso quotidiano a voce. **Fattibilità** e **costo**: con i mezzi che
Memy ha già. I costi API sono indicativi: un'immagine pesa circa 1.000-1.600 token in ingresso.

| # | Capacità | Chi ce l'ha | Cosa fa | Valore | Fattibile in Memy senza Google? Come | Difficoltà / costo |
|---|---|---|---|---|---|---|
| 1 | Vista dalla fotocamera | ChatGPT, Gemini Live, Guided Vision | «Cosa vedo?», «leggimi questa etichetta», «che pianta è?», descrizione della scena a voce | **Alto** | Sì. CameraX scatta un fotogramma alla domanda, oppure uno ogni pochi secondi, e lo manda a Claude come immagine. Si riusa la strada di `look_at_screen`. | Media. Pochi centesimi a domanda; il video continuo costa di più. |
| 2 | Compiti programmati e brief del mattino | ChatGPT Scheduled Tasks, Gemini Daily Brief / Spark | «Ogni mattina alle 7 dimmi meteo, agenda e notizie», «avvisami se il prezzo scende» | **Alto** | Sì. AlarmManager/WorkManager lancia l'agente all'ora stabilita, con ricerca web, calendario e notifiche, poi parla o lascia una notifica. Si estendono i promemoria esistenti. | Media. Un turno di Claude per ogni esecuzione. Attenzione al risparmio energetico di OriginOS, che chiude le app in background. |
| 3 | Memoria delle conversazioni passate | ChatGPT (cronologia + "dreaming"), Claude | «Come si chiamava il ristorante di cui parlavamo martedì?», contesto che si aggiorna col tempo | **Alto** | Sì. Si salvano sul telefono i riassunti delle conversazioni, che Claude scrive a fine sessione, con uno strumento `search_history`. I fatti vecchi si aggiornano. | Bassa-media. Costo minimo con Haiku. |
| 4 | Posta vera (leggere, cercare, rispondere) | Claude e ChatGPT con connettori, Gemini, Siri | «Ho mail dalla banca?», «rispondi a Luca che va bene giovedì» | **Medio-alto** | Sì, senza Google: IMAP/SMTP con password per app (Outlook, QQ, 163, iCloud). `compose_email` oggi apre solo l'app di posta. In alternativa si leggono già le notifiche della posta. | Media-alta. Va gestita la password in modo sicuro (Keystore). |
| 5 | Interprete dal vivo | Gemini Live, Pixel Voice Translate | Conversazione a due lingue: ascolta, traduce e parla nell'altra lingua | **Medio-alto** (viaggi, stranieri) | Sì, faccia a faccia: Whisper su Groq riconosce la lingua, Claude traduce, le voci Edge parlano in quella lingua. Serve una "modalità interprete" che alterna le lingue. | Bassa-media. Dentro una chiamata no, vedi #11. |
| 6 | «Condividi con Memy» (foto, link, file) | ChatGPT, Claude, Gemini | Dalla galleria o da un'altra app: «riassumi questo articolo», «cosa c'è in questa foto», «aggiungi questo biglietto al calendario» | **Medio** | Sì. Un `intent-filter` SEND/SEND_MULTIPLE apre una conversazione con l'allegato. Claude legge immagini e PDF. | Bassa. |
| 7 | Ricerca approfondita con resoconto | ChatGPT Work/Deep Research, Gemini, Claude | Domanda complessa, molte fonti, risposta con fonti e PDF | **Medio** | Sì. Ricerca web lato server più `create_document`; serve un ciclo più lungo con un tetto di passi e un riassunto a voce a fine lavoro. | Bassa. Costa decine di centesimi per ricerca con Opus. |
| 8 | Annuncio e filtro chiamate | Pixel Call Screen, Siri (iOS 26), Galaxy | «Ti chiama Marco», blocco dello spam, riassunto delle chiamate perse | **Medio** | In parte. `CallScreeningService` (Memy deve essere scelta come app di filtro) può identificare, rifiutare o silenziare e Memy annuncia a voce. Non può rispondere al posto tuo. | Bassa-media. |
| 9 | Cerca nella mia vita (contesto personale) | Siri iOS 27, Gemini Personal Intelligence | «Che codice mi aveva mandato il corriere?», «trova la foto della ricevuta» | **Medio** | In parte. Si può archiviare sul telefono lo storico delle notifiche e degli SMS e cercarci dentro. Per le foto servirebbe un indice delle immagini sul telefono, più pesante. | Media. Dati sensibili: vanno tenuti solo sul telefono. |
| 10 | Calendari e cloud online | Claude/ChatGPT (Google, iCloud, Notion), Gemini | Agenda condivisa, file nel cloud | **Medio-basso** | Sì per CalDAV (iCloud, Outlook tramite sync) e WebDAV. Il calendario del telefono è già coperto se l'account si sincronizza. | Media. |
| 11 | Chiamate al posto tuo / traduzione in chiamata | Pixel "Call for Me", Voice Translate | Telefona al ristorante, aspetta in linea, traduce chi parla | **Basso-medio** | **No**, non con un'app normale. Android non permette di immettere audio in una chiamata GSM senza privilegi di sistema. Si potrebbe fare solo con un servizio telefonico VoIP esterno (tipo Twilio), a pagamento. | Molto alta. |
| 12 | Voce "nativa" più espressiva | GPT-Live, Gemini Live native audio | Tono, emozioni, risate, risposta quasi istantanea | **Medio** | Solo in parte. Claude non ha un modello voce-voce; la catena Whisper → Claude → Edge resta. Si migliora la latenza (già in streaming) e lo stile SSML di Edge. | Media, guadagno limitato. |
| 13 | Generazione di immagini | ChatGPT Images 2.6, Gemini | «Disegnami un biglietto d'auguri» | **Basso** a voce | Non con Claude né con Groq. Servirebbe un'altra API. Claude può fare grafica SVG/HTML nei documenti. | Media più un fornitore nuovo. |
| 14 | Agente nel cloud a telefono spento | Gemini Spark, ChatGPT Work | Lavora per ore anche a schermo spento | **Basso-medio** | No così com'è: il controllo dello schermo richiede lo schermo acceso. Ricerca e documenti invece possono già girare in background (vedi #2 e #7). | Alta. |
| 15 | Connettori a servizi (Spotify, Uber, Notion…) | Claude, ChatGPT, Gemini | Azioni dirette nei servizi | **Basso** per Memy | Già coperto in buona parte: Memy usa le app vere con l'accessibilità. Le API dirette sarebbero più veloci ma servono chiavi per ogni servizio. | Varia. |

## Classifica: le 8 capacità più utili da aggiungere

1. **Vista dalla fotocamera** (#1). È la differenza più visibile rispetto a ChatGPT e Gemini, utile ogni
   giorno, e si appoggia a cose che Memy ha già (immagini a Claude).
2. **Compiti programmati e brief del mattino** (#2). Rende Memy proattiva; parte dai promemoria esistenti.
   Bisogna provare la resistenza al risparmio energetico di OriginOS.
3. **Memoria delle conversazioni** (#3). Costo basso e grande effetto "mi conosce". Estende
   `remember`/`forget`.
4. **«Condividi con Memy»** (#6). Lavoro piccolo che apre foto, PDF e link da qualsiasi app.
5. **Interprete dal vivo** (#5). Tutto il necessario c'è già (Whisper multilingue, Claude, voci Edge); manca
   solo la modalità.
6. **Posta vera via IMAP/SMTP** (#4). Senza Google è l'unico modo serio per leggere e cercare nella posta.
7. **Annuncio e filtro chiamate** (#8). Utile a mani occupate, API Android standard.
8. **Ricerca approfondita con resoconto** (#7). Quasi gratis da costruire sugli strumenti esistenti.

Da non fare per ora: chiamate al posto tuo (#11, bloccato da Android), generazione di immagini (#13, serve
un altro fornitore), agente nel cloud (#14).
