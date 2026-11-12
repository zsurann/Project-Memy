# Dove si perde tempo tra la tua voce e la risposta di Memy

Ricerca per l'issue #4. Domanda: quando smetti di parlare, quanto passa prima che Memy emetta il primo suono, e dove va a finire quel tempo?

Come sono state fatte le stime:

- **Misurato davvero**: solo Edge TTS (la sintesi vocale), con il test `EdgeLatencyProbe` e una variante temporanea "a freddo" (poi cancellata). Le misure sono partite da un server in un datacenter, dietro un proxy, non da un telefono in 4G: sul telefono aspettati qualche decina di millisecondi in più per ogni viaggio in rete.
- **Dal codice**: attesa di fine frase, frasi trattenute, divisione in frasi, riproduzione.
- **Da documentazione e benchmark pubblici**: Groq (Whisper) e Claude, perché qui non ci sono chiavi API. Questi numeri sono **stime**, da verificare sul telefono (vedi "Come misurare" in fondo).

## Il riassunto in una tabella

Caso tipico: domanda breve, risposta di una o due frasi senza strumenti, cache del prompt già calda, trascrizione con Whisper su Groq.

| # | Tappa | Tempo stimato | Da dove viene il numero |
|---|---|---|---|
| 1 | Fine frase (Silero VAD aspetta 1 s di silenzio) | **~1.000–1.050 ms** (fisso) | codice: `END_SILENCE_S = 1.0f` |
| 2 | Trascrizione Whisper large-v3 su Groq (invio audio + risposta) | **~300–700 ms**, a volte secondi | doc Groq + benchmark pubblici |
| 2b | In alternativa: Parakeet sul telefono | ~300–1.500 ms (dipende dal telefono) | stima, modello int8 da 0,6 miliardi di parametri su 2–4 core |
| 3 | Claude: primo pezzo di testo (primo token) | Haiku ~0,4–0,8 s · Sonnet ~0,8–1,5 s · **Opus ~1,5–4 s** | doc Anthropic + Artificial Analysis |
| 4 | Frasi trattenute finché la risposta non finisce o supera 220 caratteri | **~300–800 ms** | codice: `SPEAK_AFTER_CHARS = 220` |
| 5 | Edge TTS: sintesi della prima frase (connessione già aperta) | **150–370 ms** (misurato); a freddo 560–1.030 ms | misura |
| 6 | Scrittura del file MP3 + MediaPlayer che si prepara e parte | ~50–150 ms | stima dal codice |
| | **Totale con Sonnet** | **~2,6–4,6 s** (tipico ~3,5 s) | |
| | **Totale con Opus** (il modello predefinito oggi) | **~3,3–7 s** | |
| | **Totale con Haiku** | ~2,2–3,8 s | |

Le tre cose che pesano di più: **il modello scelto** (Opus ragiona sempre prima di parlare), **il secondo di silenzio** per capire che hai finito, e **le frasi trattenute** fino alla fine della risposta.

Una sorpresa nel codice: in `data/MemySettings.kt` il modello predefinito è **Opus** (`enumPref(KEY_MODEL, ClaudeModel.OPUS)`), anche se l'etichetta dice che Sonnet è "il migliore per parlare a voce. Consigliato". Chi non cambia le impostazioni usa il modello più lento.

## Tappa per tappa

### 1. Capire che hai finito di parlare (~1 s)

`VoiceCapture` legge il microfono a finestre di 512 campioni (32 ms) e chiede a Silero VAD se c'è voce. Con `minSilenceDuration = END_SILENCE_S = 1.0` il VAD smette di dire "voce" solo dopo un secondo intero di pausa. Quindi, dopo l'ultima sillaba, Memy aspetta **sempre almeno 1.000 ms più una finestra (32 ms)** prima di fare qualsiasi altra cosa.

Per confronto, i sistemi vocali di solito usano 500–700 ms per gli assistenti e 250–400 ms per comandi brevi ([CallSphere, taratura del VAD](https://callsphere.ai/blog/vw8c-vad-voice-activity-detection-tuning-2026)); il valore predefinito di Silero in LiveKit è 0,55 s ([LiveKit turn detector](https://docs.livekit.io/agents/logic-structure/turns/turn-detector/)); AssemblyAI va da 160 ms a 1.280 ms a seconda del preset ([AssemblyAI, turn detection](https://www.assemblyai.com/docs/streaming/turn-detection)). Un secondo è prudente: non ti taglia mentre pensi, ma costa caro.

Effetto collaterale: quel secondo di silenzio finisce dentro il file WAV mandato a Groq (32 KB in più da caricare, vedi tappa 2).

### 2. Trascrivere (Whisper su Groq ~300–700 ms)

`GroqWhisper` manda un WAV a 16 kHz, 16 bit, mono (32 KB per ogni secondo di audio), con `response_format = verbose_json`.

- **Calcolo del modello**: quasi niente. Groq dichiara che whisper-large-v3 va 189–299 volte più veloce del tempo reale ([doc Groq speech-to-text](https://console.groq.com/docs/speech-to-text), [annuncio Groq 299x](https://groq.com/groq-runs-whisper-large-v3-at-a-164x-speed-factor-according-to-new-artificial-analysis-benchmark/)): una frase di 5 secondi richiede circa 17–26 ms di calcolo.
- **Rete**: è qui che va il tempo. Una frase di 4 s + 1 s di silenzio = ~160 KB da caricare: in 4G (5–10 Mbit/s in upload) sono ~130–260 ms, più il viaggio di andata e ritorno verso Groq e, se la connessione non è già aperta, la stretta di mano TLS. Benchmark di terzi indicano 300–800 ms per clip brevi ([Tap2Talk](https://tap2talk.pages.dev/blog/groq-fastest-dictation-app)) e 631 ms per 5 s di audio con il modello turbo ([JigsawStack](https://jigsawstack.com/blog/jigsawstack-vs-groq-vs-assemblyai-vs-openai-speech-to-text-benchmark-comparison)).
- **Code lunghe**: sul forum Groq c'è chi ha visto richieste da oltre 60 secondi che con un nuovo tentativo finivano in 5 ([community Groq](https://community.groq.com/t/whisper-taking-incredibly-long-over-60s-retrying-takes-less-than-10s/445)). Il timeout di lettura in Memy è 20 s.
- **verbose_json**: non costa tempo apprezzabile (qualche KB di JSON in più). Serve a scartare le parole inventate sul rumore: va tenuto.
- **Whisper turbo**: secondo le tabelle di Groq va 216x contro 189x (o 247x contro 300x in un'altra loro tabella), con un errore sulle parole che sale dal 10,3% al 12% ([doc Groq](https://console.groq.com/docs/speech-to-text), [guida ASR Groq](https://groq.com/GroqDocs/Groq%20ASR%20Model%20Guide.pdf)). Sulle frasi brevi di Memy farebbe risparmiare **circa 5–20 ms**: troppo poco per perdere precisione in italiano. **Non conviene.**

Mentre Groq lavora, Memy prepara già la connessione per la voce (`voice.prewarm()` in `onProcessing`): bene così.

### 3. Claude: primo token (0,4–4 s secondo il modello)

`MemyAgent.buildParams` manda: il prompt di sistema (~6.700 caratteri), **52 strumenti** con le loro descrizioni, la cronologia e, se attiva, la ricerca web. In tutto probabilmente 8–12 mila token di prefisso (stima: non è stato possibile contarli con l'API). Effort basso per tutti; thinking `between_tools` su Sonnet (nessun ragionamento prima di rispondere), `disabled` su Haiku, nessuna impostazione su Opus, che **ragiona sempre** e non permette di spegnerlo ([doc Anthropic, modelli](https://platform.claude.com/docs/en/about-claude/models/overview)).

Stime del primo token, con la cache calda:

- **Haiku 5.5** ("Fastest" per Anthropic): ~0,4–0,8 s. Per riferimento, Artificial Analysis dava 0,52 s a Haiku 4.5 senza ragionamento; Haiku 5.5 scrive ~237 token al secondo ([Artificial Analysis, Anthropic](https://artificialanalysis.ai/providers/anthropic)).
- **Sonnet 5.5** ("Fast"): ~0,8–1,5 s. Artificial Analysis riporta 1,05 s al primo token di risposta per Sonnet 5.5 con effort basso ([Artificial Analysis, Sonnet 5.5](https://www.artificialanalysis.ai/models/releases/claude-sonnet-5-5)).
- **Opus 5.5** ("Moderate"): ~1,5–4 s, perché prima di scrivere ragiona, anche con effort basso, e il ragionamento si paga in token generati (~97 token/s secondo [SmartScope](https://smartscope.blog/en/blog/claude-opus-5-5-cost-speed-benchmarks-2026/)). Non abbiamo trovato una misura pubblica affidabile del primo token di Opus 5.5 con effort basso: questa è la stima più incerta della tabella.

**Cache del prompt.** `cacheControl` automatico sull'intera richiesta: il prefisso (strumenti + sistema + cronologia) viene riletto dalla cache. Il prompt di sistema è stabile (l'orario va nel messaggio dell'utente, non nel prompt di sistema: scelta giusta). Anthropic misura un primo token più basso del 31% su un prompt da 10 mila token (1,6 s → 1,1 s) e del 79% su 100 mila ([Anthropic, prompt caching](https://www.anthropic.com/news/prompt-caching)). Per Memy vuol dire **circa 300–500 ms risparmiati quando la cache è calda**. Ma la cache dura 5 minuti ([doc prompt caching](https://platform.claude.com/docs/en/build-with-claude/prompt-caching)), mentre Memy tiene la conversazione per 15 minuti (`CONVERSATION_TIMEOUT_MS`): la prima domanda dopo una pausa di 5 minuti o più paga sempre la cache fredda, più una nuova connessione TLS se OkHttp l'ha chiusa.

**Fast mode** (solo Opus 5.5, prezzo doppio): accelera la scrittura fino a 2,5 volte, **non il primo token** ([doc fast mode](https://platform.claude.com/docs/en/build-with-claude/fast-mode)). Aiuterebbe solo il ragionamento di Opus e la tappa 4.

### 4. Frasi trattenute (~300–800 ms)

`streamOnce` non legge subito le frasi: le tiene da parte finché il testo non supera **220 caratteri** (`SPEAK_AFTER_CHARS`) oppure finché il messaggio non finisce. Serve a non leggere ad alta voce frasi tipo "ora apro WhatsApp" se poi arriva uno strumento.

Il problema: il prompt di sistema chiede risposte di "una frase, al massimo due", cioè di solito **meno di 220 caratteri**. Quindi nella maggior parte delle risposte Memy aspetta che **Claude abbia scritto tutta la risposta** prima di dire la prima parola. Per 40–60 token: ~0,2–0,3 s con Haiku, ~0,4–0,6 s con Sonnet e Opus, più la chiusura dello stream. Per risposte più lunghe aspetta i primi 220 caratteri (~60 token, ~0,6 s).

La divisione in frasi (`SentenceSplitter`) invece non costa niente: lavora sul testo man mano, e la prima frase può essere corta (6 caratteri).

### 5. Sintesi con Edge TTS (150–370 ms misurati)

Misure reali con la voce `it-IT-IsabellaNeural` (4 giri per testo):

| Testo | Audio prodotto | A freddo (nuova connessione) | Connessione preparata prima |
|---|---|---|---|
| "Certo, subito." (14 caratteri) | 2,4 s | 556–1.033 ms | **153–237 ms** |
| "Vuoi che ti imposti anche una sveglia?" (38) | 3,0 s | 629–927 ms | **151–374 ms** |
| Frase sul meteo (101) | 6,8 s | 603–700 ms | **193–621 ms** |

Con il test originale `EdgeLatencyProbe` (connessioni preparate a catena come nell'app): 14 caratteri 183–282 ms, 38 caratteri 181–645 ms, 101 caratteri 967–983 ms.

Cosa vuol dire:

- **Il preriscaldamento funziona**: fa risparmiare 400–700 ms rispetto a una connessione nuova (la sola stretta di mano TLS verso `speech.platform.bing.com` qui misura ~300 ms).
- **Memy aspetta tutto l'audio della frase** prima di suonare (`done.await` aspetta il messaggio `turn.end`, poi scrive un file). Più lunga è la prima frase, più si aspetta: fino a ~1 s per 100 caratteri. Il servizio però manda l'audio a pezzi man mano ([edge-tts, libreria di riferimento](https://github.com/rany2/edge-tts)), quindi si potrebbe iniziare a suonare dal primo pezzo.
- La connessione preparata scade dopo 20 s (`MAX_IDLE_MS`): se Claude ci mette di più (ricerca web, strumenti), la prima frase paga di nuovo l'apertura (~0,5 s in più).

### 6. File MP3 e MediaPlayer (~50–150 ms)

`VoicePlayer.playFile` scrive l'MP3 nella cache, crea un `MediaPlayer` nuovo, chiama `prepareAsync()` e parte in `onPrepared`. Su file locale la preparazione di solito richiede alcune decine di millisecondi, più la latenza di uscita audio di Android ([MediaPlayer, doc Android](https://developer.android.com/reference/android/media/MediaPlayer)). Stima, non misurata.

## Le leve per ridurre il tempo

Guadagni stimati sul caso tipico. Nessuna è stata provata sul telefono: vanno misurate (vedi in fondo) e la qualità va controllata a orecchio.

| Leva | Guadagno stimato | Cosa costa |
|---|---|---|
| **A. Sonnet come modello predefinito** al posto di Opus | **−700 / −2.500 ms** | Meno profondità nei ragionamenti difficili. Costa la metà ($2/$10 contro $4/$20 per milione di token). L'utente può sempre scegliere Opus. |
| **B. Non trattenere la prima frase** quando non sembra un annuncio di azione (oppure abbassare `SPEAK_AFTER_CHARS` a ~60–80 e interrompere la voce se poi arriva uno strumento) | **−300 / −800 ms** | Ogni tanto potresti sentire "ora apro WhatsApp" prima dell'azione. Si limita con il prompt (che già chiede di lavorare in silenzio) e fermando la voce all'arrivo di `tool_use`. Costo in soldi: zero. |
| **C. Fine frase più rapida**: `END_SILENCE_S` da 1,0 a 0,6–0,7 s, oppure adattiva (0,5 s se il testo parziale di Parakeet sembra una frase finita, 1 s altrimenti) | **−300 / −500 ms** | Rischio di tagliarti mentre pensi. La versione adattiva riduce il rischio ma richiede Parakeet scaricato. Costo in soldi: zero. |
| **D. Riempitivo vocale**: un "Mmh…", "Allora…" o "Vediamo…" già sintetizzato e salvato sul telefono, suonato subito dopo la fine della frase (solo per domande, non per comandi) | **tempo percepito −1.500 / −3.000 ms**, tempo reale 0 | Può risultare ripetitivo o finto se usato sempre; da variare e usare solo quando si prevede un'attesa (Opus, ricerca web). Costo in soldi: zero. |
| **E. Preriscaldare tutto all'inizio dell'ascolto**: alla parola di attivazione mandare a Claude una richiesta `max_tokens: 0` con gli stessi strumenti e prompt (scalda cache e connessione TLS) e aprire la connessione a Groq | **−200 / −600 ms** sulla prima domanda dopo una pausa; 0 se la cache era già calda | Pochi centesimi: con Sonnet una scrittura in cache da ~10 mila token costa ~$0,025, ma la si pagherebbe comunque alla prima domanda; se la cache è calda la lettura costa ~$0,001. In alternativa la cache da 1 ora (scrittura a 2x). |
| F. Suonare l'audio di Edge mentre arriva (streaming verso AudioTrack o ExoPlayer, senza file e senza aspettare `turn.end`) | −100 / −500 ms (di più sulle prime frasi lunghe) | Lavoro di sviluppo medio: serve un decodificatore MP3 in streaming o un formato PCM. Costo in soldi: zero. |
| G. Partire in anticipo: a 0,5 s di silenzio avviare trascrizione e Claude "di prova", e annullarli se riprendi a parlare | −400 / −500 ms | Richieste sprecate quando riprendi a parlare (soldi e limite gratuito Groq di 20 richieste al minuto); logica di annullamento delicata. |
| H. Risposta locale per comandi semplici (sveglia, timer, torcia, volume) con un riconoscitore di frasi sul telefono | −1.500 / −3.000 ms su quei comandi | Riconoscitore da scrivere e mantenere; rischio di capire male; due comportamenti diversi da tenere coerenti. Fa risparmiare i token di quei comandi. |
| I. Haiku come scelta rapida | −300 / −700 ms rispetto a Sonnet | Meno affidabile con 52 strumenti e richieste ambigue. Costa 20 volte meno di Sonnet. |
| J. Prompt più corto / strumenti caricati solo se servono (tool search) | −50 / −300 ms quando la cache è fredda, quasi niente con cache calda | Rischio che Claude non trovi lo strumento giusto; cambiare il prompt richiede di riverificare la qualità. |
| K. Togliere il secondo di silenzio in coda dal WAV prima dell'invio, o comprimere l'audio (FLAC) | −30 / −150 ms in 4G | Quasi nessuno; da verificare che Whisper non tagli l'ultima parola (il codice dice che un po' di silenzio aiuta: lasciarne ~200 ms). |
| L. Fast mode su Opus | −200 / −1.000 ms solo con Opus | Prezzo doppio ($8/$40). Non accelera il primo token. |
| M. Whisper turbo | −5 / −20 ms | Più errori in italiano (dal 10,3% al 12% di parole sbagliate). **Sconsigliato.** |

### Le 5 leve migliori

1. **Sonnet come predefinito (A)**: il guadagno più grande, risparmia anche soldi; basta cambiare una riga in `MemySettings.kt`.
2. **Non trattenere la prima frase (B)**: 0,3–0,8 s su quasi ogni risposta, gratis.
3. **Fine frase più rapida o adattiva (C)**: 0,3–0,5 s su ogni turno, gratis, con un po' di rischio di tagliarti.
4. **Riempitivo vocale (D)**: elimina il silenzio percepito, gratis, ma va usato con misura.
5. **Preriscaldare Claude e Groq all'inizio dell'ascolto (E)**: toglie la penalità della prima domanda dopo una pausa, per pochi centesimi.

Con A + B + C il caso tipico passa da ~3,5–5 s (Opus oggi) a **~2–2,5 s**; con D l'utente sente qualcosa dopo ~1 s.

## Come misurare sul telefono

Per sostituire le stime con numeri veri basterebbe registrare nel log, per ogni turno, questi istanti: fine voce rilevata dal VAD, testo di Whisper ricevuto, primo evento di testo da Claude (e `usage.cache_read_input_tokens` per vedere se la cache ha funzionato), prima frase rilasciata, audio Edge ricevuto, `MediaPlayer.start()`. Conviene confrontare le varianti alternandole nella stessa sessione, perché la velocità dei servizi cambia nel corso della giornata.

Nota: le pagine di Artificial Analysis non erano raggiungibili da questa sessione; i loro numeri vengono dai risultati di ricerca, non dalla lettura diretta della pagina.

## Fonti

- Anthropic: [Panoramica dei modelli](https://platform.claude.com/docs/en/about-claude/models/overview) · [Prompt caching (doc)](https://platform.claude.com/docs/en/build-with-claude/prompt-caching) · [Prompt caching (annuncio con misure)](https://www.anthropic.com/news/prompt-caching) · [Fast mode](https://platform.claude.com/docs/en/build-with-claude/fast-mode) · [Effort](https://platform.claude.com/docs/en/build-with-claude/effort) · [Thinking](https://platform.claude.com/docs/en/build-with-claude/thinking) · [Ridurre la latenza](https://platform.claude.com/docs/en/test-and-evaluate/strengthen-guardrails/reduce-latency)
- Groq: [Speech to text](https://console.groq.com/docs/speech-to-text) · [Guida ai modelli ASR](https://groq.com/GroqDocs/Groq%20ASR%20Model%20Guide.pdf) · [Whisper large-v3 a 299x](https://groq.com/groq-runs-whisper-large-v3-at-a-164x-speed-factor-according-to-new-artificial-analysis-benchmark/) · [Whisper turbo](https://groq.com/whisper-large-v3-turbo-now-available-on-groq-combining-speed-quality-for-speech-recognition/) · [Forum: richieste lente](https://community.groq.com/t/whisper-taking-incredibly-long-over-60s-retrying-takes-less-than-10s/445)
- Benchmark di terzi: [Artificial Analysis, Anthropic](https://artificialanalysis.ai/providers/anthropic) · [Artificial Analysis, Sonnet 5.5](https://www.artificialanalysis.ai/models/releases/claude-sonnet-5-5) · [SmartScope, Opus 5.5](https://smartscope.blog/en/blog/claude-opus-5-5-cost-speed-benchmarks-2026/) · [JigsawStack STT](https://jigsawstack.com/blog/jigsawstack-vs-groq-vs-assemblyai-vs-openai-speech-to-text-benchmark-comparison) · [Tap2Talk](https://tap2talk.pages.dev/blog/groq-fastest-dictation-app)
- Fine frase: [LiveKit turn detector](https://docs.livekit.io/agents/logic-structure/turns/turn-detector/) · [AssemblyAI turn detection](https://www.assemblyai.com/docs/streaming/turn-detection) · [CallSphere, taratura VAD](https://callsphere.ai/blog/vw8c-vad-voice-activity-detection-tuning-2026) · [Hugging Face, playbook latenza voce](https://huggingface.co/blog/dvalle08/voice-agent-latency-playbook)
- Edge TTS e Android: [edge-tts](https://github.com/rany2/edge-tts) · [MediaPlayer](https://developer.android.com/reference/android/media/MediaPlayer)
