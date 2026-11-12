# Cosa renderebbe Memy davvero più intelligente

Ricerca per l'issue #11. Ho letto il codice (`agent/MemyAgent.kt`, `agent/SystemPrompt.kt`, `tools/*.kt`, `access/MemyAccessibilityService.kt`, `data/MemoryStore.kt`), la documentazione aggiornata dell'API di Claude (skill `claude-api`, novembre 2026) e come lavorano gli altri agenti che usano telefoni e computer. Il codice dell'app non è stato modificato.

## In breve

Memy ha già una base solida: streaming, cache del prompt, conferme per le azioni delicate, schermo restituito dopo ogni azione, interruzione a voce, memoria. Il modello non è il limite principale. Quello che manca è soprattutto:

1. **un metodo per usare le app** scritto nel prompt (piano → azione → verifica → recupero), con esempi;
2. **contesto automatico**: oggi Memy non sa in che app sei quando parli, né in che città sei;
3. **il ragionamento su Sonnet quando usa le app**: oggi Sonnet gira con `between_tools`, cioè di fatto senza pensare, anche nei compiti di più passaggi;
4. **un passaggio Haiku→Sonnet più furbo**, che oggi spreca un giro di rete e la cache;
5. **strumenti che aiutano a verificare e a recuperare dagli errori**;
6. **una memoria che ricorda anche le conversazioni**, non solo una lista di fatti.

Tutti e sei costano poco: da 0 a circa 2 centesimi in più per ogni compito sulle app, e quasi sempre meno di un secondo in più (alcuni fanno anzi risparmiare tempo).

## Cosa fa oggi Memy (quello che conta per l'intelligenza)

| Aspetto | Oggi nel codice | Cosa manca o cosa non va |
|---|---|---|
| Modello | Automatico: parte da Haiku 5.5; passa a Sonnet 5.5 se Haiku chiama `think_harder` o se usa uno strumento dello schermo (`MemyAgent.escalate`) | Per passare a Sonnet Haiku deve prima fare una chiamata (~0,5–1 s sprecati). Al cambio di modello la cache non vale più (la cache è per modello) e Sonnet rilegge tutto da zero. Ogni nuova richiesta riparte da Haiku, anche se è il seguito di un compito appena fatto su Sonnet. |
| Ragionamento | Effort LOW per tutti; Haiku `disabled`, Sonnet `between_tools` | Su Sonnet `between_tools` spegne il ragionamento: proprio nei compiti sulle app (più passaggi, decisioni) Memy non pensa mai. La guida Anthropic consiglia `medium` per l'uso di strumenti in più passaggi e `low` per la chat. Con il ragionamento spento i modelli a volte scrivono la chiamata allo strumento nel testo invece di eseguirla. |
| Prompt | Buona parte su voce, silenzio, sicurezza (~2.000 token) | Nessun esempio concreto; per le app solo «open_app, poi read_screen…». Nessuna regola su come pianificare, cosa controllare prima di dire «Fatto», come uscire da un vicolo cieco (popup, login, schermata sbagliata). |
| Contesto automatico | Solo data e ora in testa a ogni messaggio; ricerca web con paese e fuso orario | Non sa l'app in primo piano (eppure l'accessibilità la conosce già: `currentPackage`), né la città (la ricerca web accetta anche `city`/`region`). «Rispondi a questo», «cosa c'è scritto qui?» richiedono un passaggio in più o vengono fraintesi. |
| Schermo | `read_screen` restituisce fino a 140 elementi numerati (~1.500–2.000 token); dopo ogni tocco/scrittura torna lo schermo nuovo | Elenco piatto: non dice qual è il titolo della schermata, quale campo ha il cursore, quali elementi stanno nella stessa riga di una lista. Se un elemento non si trova, l'errore chiede di rileggere invece di restituire subito lo schermo nuovo (un giro in più). |
| Strumenti | 52 strumenti, schemi JSON semplici | Nessuno `strict: true`; alcune descrizioni brevissime (es. `scroll`, `create_calendar_event`) senza dire quando usarli e cosa restituiscono. |
| Memoria | Fino a 100 frasi in coda al prompt di sistema; `remember` / `forget` | Ricorda fatti, non conversazioni («cosa ti avevo chiesto ieri?»). Non aggiorna un fatto vecchio («ora lavoro a Torino» si aggiunge a «lavoro a Milano»). |
| Cronologia | 15 minuti, max 80 messaggi, `trimHistory` toglie i messaggi più vecchi | Va bene oggi. Ma se si accende il ragionamento, togliere messaggi dall'inizio è una «modifica della cronologia»: i blocchi di ragionamento successivi diventano non validi (errore 400 sugli account nuovi). Va gestito insieme all'intervento 3. |

## Tabella degli interventi

Prezzi usati (per milione di token, novembre 2026): Haiku 5.5 $0,10 in / $0,50 out, lettura cache $0,01; Sonnet 5.5 $2 in / $10 out, scrittura cache $2,50, lettura cache $0,10; ricerca web $10 ogni 1.000 ricerche. Prefisso fisso (istruzioni + strumenti) stimato ~7.000–9.000 token. Un compito tipico sulle app = 6–12 passaggi. I secondi sono stime da misurare, non misure.

| # | Intervento | Beneficio | Velocità | Soldi | Difficoltà |
|---|---|---|---|---|---|
| 1 | **Prompt: metodo per usare le app** (piano breve, una azione alla volta, verifica, recupero da popup/login/schermata sbagliata, quando fermarsi) **+ 3–4 esempi** brevi | Alto | 0 s (testo in cache) | +500–800 token in cache: ~$0,00001 a richiesta su Haiku, ~$0,0001 su Sonnet | Bassa |
| 2 | **Prompt: regole per la ricerca e per finire il lavoro** (frasi consigliate da Anthropic per Haiku 5.5: «i tuoi dati sono vecchi, cerca prezzi/orari/risultati anche se sei sicura»; «continua finché hai finito, fermati solo se serve l'utente») | Medio-alto | +0 s; qualche ricerca in più (+1–2 s quando cerca) | +$0,01 ogni ricerca in più | Bassa |
| 3 | **Sonnet con ragionamento adattivo a effort `low`** al posto di `between_tools` (e `medium` solo per compiti lunghi sulle app, con l'effort per messaggio) | Alto sulle app | +0 s sulle cose semplici (a `low` di solito non pensa); +1–3 s sui passaggi difficili | ~+$0,002–0,01 per passaggio che pensa; ~+$0,01–0,03 per compito | Media (vanno rimandati indietro i blocchi di ragionamento e sistemato `trimHistory`) |
| 4 | **Passaggio Haiku→Sonnet più furbo**: decidere *prima* della chiamata con regole semplici (parole come «scrivi a», «apri», «su WhatsApp», richieste lunghe, seguito di un compito appena fatto su Sonnet) e restare su Sonnet per il resto della conversazione | Medio-alto | −0,5/−1,5 s sui compiti difficili (niente giro `think_harder`) | Alcune richieste semplici vanno su Sonnet: +$0,001–0,003 l'una; si risparmia la riscrittura della cache doppia | Bassa |
| 5 | **Contesto automatico** in testa al messaggio (non nel prompt di sistema, per non rompere la cache): app in primo piano e titolo della schermata; città approssimativa nella ricerca web (`user_location.city/region`) | Alto | 0 s (dati già disponibili sul telefono) | +50–150 token a richiesta: ~$0,00002 Haiku | Bassa |
| 5b | Variante: anche **lo schermo intero** in automatico quando l'utente dice «questo / qui / sullo schermo» | Medio | −1 passaggio (−0,7–1,5 s) quando serve | +1.500–2.000 token: ~$0,0002 Haiku, ~$0,005 Sonnet | Bassa (attenzione alla privacy: solo su richiesta deittica) |
| 6 | **Strumenti che aiutano a verificare e recuperare**: in caso di errore restituire subito lo schermo nuovo; dopo un tocco dire cosa è cambiato (schermata nuova? stesso schermo?); `read_screen` con titolo, campo col cursore, elementi raggruppati per riga di lista | Alto | −0,5–1 s per errore evitato | +10–20% token per schermo (~+$0,0005 per compito su Sonnet) | Media |
| 7 | **Memoria migliore**: riassunto di ogni conversazione finita (una chiamata Haiku quando scade la conversazione) + `remember` che sostituisce un fatto vecchio invece di aggiungerlo | Medio-alto | 0 s per l'utente (il riassunto si fa dopo) | ~$0,0005–0,001 a conversazione | Media |
| 8 | **Descrizioni e schemi degli strumenti**: `strict: true` + `additionalProperties: false`, descrizioni che dicono quando usarlo, cosa restituisce, cosa fare se fallisce; messaggi d'errore con il passo successivo | Medio | 0 s | 0 (testo in cache) | Bassa |
| 9 | **Piccola batteria di test** (30–50 richieste vere a voce + 10 compiti sulle app, col server finto che il codice già supporta via `baseUrl`) per misurare ogni cambiamento | Alto (rende misurabile tutto il resto) | Nessun effetto sull'app | ~$0,50–3 a esecuzione dei test | Media |
| 10 | **Strumento di uso del computer di Anthropic** (`computer_toolset_20260801`) solo per le app senza testo leggibile (giochi, mappe, app Flutter), al posto di `look_at_screen` + `tap_position` | Medio | +1–2 s per passaggio (screenshot) | ~600–1.500 token per screenshot: ~$0,002–0,004 per passaggio su Sonnet | Alta |
| 11 | **Advisor tool**: Haiku esegue e chiede consiglio a Sonnet solo nei punti difficili (alternativa al punto 4) | Medio, da provare | +1–3 s quando chiede consiglio | Paga solo i consigli | Media |
| 12 | **Haiku con ragionamento adattivo a `low`** invece di `disabled` | Basso-medio | +0–0,5 s (a `low` spesso non pensa) | Trascurabile | Bassa |
| 13 | **Context editing / compattazione lato server** | Basso oggi (conversazioni da 15 minuti) | 0 s | Riscrive la cache: può costare più di quanto fa risparmiare | Media; serve solo come sostituto sicuro di `trimHistory` se si fa il punto 3 |
| 14 | **Tool search** (caricare gli strumenti a richiesta) | Basso: 52 strumenti stanno in cache e costano pochissimo | +0,5–1 s quando cerca uno strumento | ≈0 | Media. Sconsigliato |
| 15 | **Memory tool di Anthropic** (`memory_20250818`, cartella di file) | Basso-medio: più flessibile ma ogni lettura è un giro in più | +0,5–1 s quando consulta | ≈0 | Media. Meglio il punto 7 per la voce |
| 16 | **Citazioni** (`citations` sui documenti) | Basso per la voce; utile solo per «da dove l'hai preso?» su PDF | 0 s | ≈0 | Bassa |
| 17 | **Programmatic tool calling** (Claude scrive codice che chiama più strumenti) | Basso per la voce | +1–2 s (avvio contenitore) | Tempo di contenitore + token | Alta. Sconsigliato |

## Dettagli degli interventi principali

### 1. Un metodo per usare le app, con esempi

Gli agenti migliori sui telefoni separano esplicitamente **pianificare, agire, verificare e riflettere**: Mobile-Agent-v3 ha un «Manager» che pianifica e un «Reflector» che controlla l'esito di ogni passo (73,3% su AndroidWorld); l'agente M3A di Google usa la riflessione sugli errori passati. Memy può ottenere buona parte di questo con il prompt, senza agenti in più. Proposta di sezione (da adattare):

```
Usare le app, passo per passo:
- Prima di iniziare decidi in silenzio i passaggi (es. apri WhatsApp → cerca Marco → scrivi → invia).
- Una azione alla volta. Dopo ogni azione guarda lo schermo restituito e chiediti: sono dove mi aspettavo?
- Se compare un popup, un banner o una richiesta di permesso, chiudilo o accettalo se è innocuo, poi continua.
- Se sei nella schermata sbagliata, torna indietro (press_button back) invece di toccare a caso.
- Se lo stesso tentativo fallisce due volte, prova un'altra strada (cerca, scorri, look_at_screen); dopo tre strade diverse fermati e di' cosa non va.
- Prima di dire "Fatto" controlla sullo schermo che il risultato ci sia davvero (il messaggio compare nella chat, l'interruttore è attivo).
- Se serve un login, una password o un pagamento, fermati e chiedi all'utente di farlo lui.
```

Più 3–4 esempi brevi del tipo «richiesta → strumenti usati → risposta a voce» (un messaggio WhatsApp, un interruttore nelle impostazioni, un'informazione cercata dentro un'app, una domanda semplice senza strumenti). Gli esempi vanno nel prompt di sistema, che è in cache: costo quasi zero.

### 2. Frasi consigliate da Anthropic per Haiku 5.5

La guida di Haiku 5.5 dice che a effort `low` e con prompt lunghi il modello a volte salta una ricerca o si ferma prima. Testi consigliati (da tradurre):

- dopo la data: «I tuoi dati di addestramento finiscono molto prima di oggi. Prezzi, orari, risultati, cariche e tutto ciò che è "l'ultimo" possono essere cambiati: cercali prima di rispondere, anche se ti senti sicura. Quando la risposta dipende da dove si trova l'utente, metti la sua città nella ricerca.»
- «Continua finché tutto ciò che l'utente ha chiesto è fatto e controllato; fermati a chiedere solo se non puoi andare avanti senza di lui o prima di un passo rischioso.»

Da evitare: «cerca sempre», che fa cercare anche quando non serve.

### 3. Lasciar pensare Sonnet quando usa le app

Oggi `buildParams` manda a Sonnet `thinking: between_tools`, che **spegne il ragionamento**. Per la chat va bene, ma i compiti sulle app sono proprio quelli in cui un minimo di ragionamento evita errori. La guida di Sonnet 5.5: `low` per la chat, `medium` per l'uso di strumenti in più passaggi; a `low` il modello salta il ragionamento sulle richieste semplici, quindi non rallenta le cose facili.

Proposta: Sonnet con `thinking: {type: "adaptive"}` ed effort `low`; per i compiti lunghi sulle app passare a `medium` con l'**effort per messaggio** (beta `mid-conversation-output-config-2026-07-01`), che non rompe la cache.

Attenzione, da fare insieme:
- i blocchi `thinking` vanno rimandati indietro così come sono (oggi `response.toParam()` li conserva già);
- `trimHistory` toglie messaggi dall'inizio: con il ragionamento acceso è una modifica della cronologia e i blocchi successivi non valgono più (errore 400 sugli account creati dopo il 31/08/2026). Soluzioni: compattazione/context editing lato server, oppure togliere i blocchi di ragionamento dai turni rimasti dopo un taglio, oppure ricominciare la conversazione con un riassunto;
- `between_tools` non si può combinare con l'effort per messaggio: si passa all'adattivo.

### 4. Passaggio Haiku → Sonnet

Oggi: Haiku riceve la richiesta, capisce che è difficile, chiama `think_harder` (un giro intero di rete, ~0,5–1 s), poi Sonnet rilegge tutta la conversazione senza cache (la cache è per modello) e in più gli strumenti cambiano (sparisce `think_harder`, cambia la ricerca web), quindi anche il prefisso cambia. Alla richiesta successiva si riparte da Haiku, anche se è «e adesso mandalo anche a Giulia».

Proposta semplice:
- **regole prima della chiamata** (nel codice, costo zero): se la frase nomina un'app o un'azione su un'app («su WhatsApp», «apri», «scrivi a… su», «cerca su Amazon»), se è lunga, o se un documento è richiesto → Sonnet subito;
- **Sonnet resta** per il resto della conversazione (15 minuti) una volta salito: i seguiti sono quasi sempre dello stesso tipo e la cache di Sonnet resta calda;
- `think_harder` resta come rete di sicurezza per i casi che le regole non prendono.

Alternativa da provare dopo: l'**advisor tool** (Haiku 5.5 può avere Sonnet 5.5 come consigliere) per restare su Haiku e chiedere aiuto solo nei punti difficili.

### 5. Contesto automatico

Il servizio di accessibilità conosce già l'app in primo piano (`currentPackage`). Basta aggiungerla al prefisso del messaggio, accanto a data e ora, per esempio `[giovedì 12 novembre 2026, ore 18:30 · app aperta: WhatsApp, chat «Marco»]`. Così «rispondigli che arrivo», «cosa dice qui?», «salvalo» funzionano al primo colpo. Il nome dell'app con il titolo della schermata costa ~30–80 token.

Posizione: la ricerca web oggi riceve solo paese e fuso orario. `UserLocation` accetta anche `city` e `region`: usando l'ultima posizione approssimativa nota (senza GPS, nessuna attesa) meteo, «farmacia aperta qui vicino», orari dei treni diventano precisi. Il contesto va nel messaggio dell'utente, **non** nel prompt di sistema, per non rompere la cache.

### 6. Verifica e recupero negli strumenti

Gli strumenti di Memy restituiscono già lo schermo dopo ogni azione (bene: è quello che fanno Claude computer use e Gemini). Piccole migliorie:
- quando `tap_element` o `type_text` non trovano l'elemento, restituire **subito lo schermo nuovo** nell'errore invece di dire «rileggi lo schermo» (un passaggio in meno);
- dopo un tocco, una riga «schermata cambiata: sì/no, nuova app: …» aiuta il modello a capire se il tocco ha funzionato;
- in `read_screen`: titolo della schermata, campo con il cursore, elementi di una riga di lista raggruppati (oggi «nome», «ultimo messaggio», «ora» sono tre righe separate e il modello deve indovinare che vanno insieme);
- AndroidWorld mostra che testo + immagine non batte sempre il solo testo: tenere `read_screen` come via principale e l'immagine solo quando serve, come oggi.

### 7. Memoria che ricorda le conversazioni

Oggi la memoria è una lista di fatti nel prompt di sistema (veloce, nessun giro in più: va bene per la voce). Due aggiunte:
- quando una conversazione scade (15 minuti), una chiamata Haiku la riassume in 1–2 frasi («12/11: ha chiesto di mandare a Marco l'indirizzo del ristorante») e la salva; gli ultimi 10–20 riassunti entrano nel prompt. Costo ~$0,001 a conversazione, zero attesa per l'utente;
- `remember` con un parametro facoltativo `replaces` per sostituire un fatto vecchio invece di accumularne due contraddittori.

Il memory tool di Anthropic è pensato per agenti che leggono file: per la voce ogni consultazione sarebbe un giro in più. Non serve qui.

### 8. Schemi e descrizioni degli strumenti

Dalla guida Anthropic sugli strumenti: descrivili come a un collega nuovo (quando usarlo, cosa restituisce, limiti) e fai messaggi d'errore che dicono cosa fare dopo. Con `strict: true` e `additionalProperties: false` i parametri arrivano sempre nel formato giusto. Le descrizioni sono nella parte in cache: costo zero. Gli strumenti vanno elencati sempre nello stesso ordine e non vanno cambiati a metà conversazione.

## Cosa fanno gli altri

- **Claude (computer use)**: screenshot → azione → nuovo screenshot, con zoom per i dettagli; Anthropic consiglia di verificare il risultato dopo ogni passo. Sonnet 5.5 a effort `high` completa molti più compiti di computer use di Sonnet 5 con meno di un terzo dei token.
- **Gemini** (Gemini Intelligence su Android, strumento computer use in Gemini 3.5 Flash): lavora dagli screenshot, chiede conferma prima delle azioni delicate e si ferma se riconosce un tentativo di prompt injection.
- **ChatGPT agent / Operator**: screenshot + cursore virtuale; conferma per le azioni importanti, «takeover» per password e carte (l'utente scrive, l'agente non guarda), «watch mode» sui siti delicati. Memy ha già le conferme e il divieto di inserire password: è allineata.
- **Agenti di ricerca su Android** (Mobile-Agent-v3, M3A su AndroidWorld): ruoli separati di pianificazione, azione e riflessione; la riflessione e la memoria dei passi fatti sono ciò che alza di più la riuscita.

## Classifica dei 6 interventi migliori

| Posto | Intervento | Perché | Costo |
|---|---|---|---|
| 1 | Prompt con metodo per le app + esempi + regole di ricerca e di fine lavoro (1+2) | Il guadagno più grande al costo più basso: è dove gli agenti migliori differiscono da Memy oggi | 0 s; ~$0,0001 a richiesta |
| 2 | Contesto automatico: app in primo piano + città nella ricerca web (5) | Fa capire «questo», «qui», «vicino a me» al primo colpo; dati già disponibili | 0 s; ~$0,00002 a richiesta |
| 3 | Sonnet con ragionamento adattivo a `low` (`medium` per compiti lunghi) (3) | Oggi Sonnet non pensa mai, proprio nei compiti difficili | +0 s sulle cose semplici, +1–3 s sui passaggi difficili; ~+$0,01–0,03 per compito |
| 4 | Passaggio a Sonnet deciso prima della chiamata e Sonnet «che resta» (4) | Più rapido e meno errori di instradamento; la cache resta calda | −0,5/−1,5 s sui compiti difficili; ~+$0,002 sulle richieste semplici instradate su Sonnet |
| 5 | Strumenti che aiutano a verificare e recuperare (6) | Meno «Fatto» detti a vuoto, meno giri persi dopo un errore | −0,5–1 s per errore; ~+$0,0005 per compito |
| 6 | Memoria delle conversazioni + sostituzione dei fatti (7) | È la cosa che l'utente percepisce di più come «intelligenza» nel tempo | 0 s; ~$0,001 a conversazione |

**Da fare insieme a tutti:** la piccola batteria di test (punto 9). Senza, non si può sapere se un cambiamento al prompt o all'effort ha aiutato o peggiorato. I test esistenti (`MemyAgentTest`) usano già un server finto: si possono estendere con 30–50 richieste vere registrate. Costo ~$0,50–3 a esecuzione con il modello vero.

**Da non fare ora:** tool search, programmatic tool calling, memory tool di Anthropic, citazioni (poco utili per la voce o più lenti); context editing solo se serve per il punto 3.

## Fonti

- Documentazione API Claude (skill `claude-api`, aggiornata al 3/11/2026): guide di migrazione a [Sonnet 5.5 e Haiku 5.5](https://platform.claude.com/docs/en/about-claude/models/overview), [ragionamento adattivo](https://platform.claude.com/docs/en/build-with-claude/adaptive-thinking), [effort](https://platform.claude.com/docs/en/build-with-claude/effort), [prompt caching](https://platform.claude.com/docs/en/build-with-claude/prompt-caching), [messaggi di sistema a metà conversazione](https://platform.claude.com/docs/en/build-with-claude/mid-conversation-system-messages), [context editing](https://platform.claude.com/docs/en/build-with-claude/context-editing), [memory tool](https://platform.claude.com/docs/en/agents-and-tools/tool-use/memory-tool), [tool search](https://platform.claude.com/docs/en/agents-and-tools/tool-use/tool-search-tool), [computer use](https://platform.claude.com/docs/en/agents-and-tools/tool-use/computer-use-tool), [citazioni](https://platform.claude.com/docs/en/build-with-claude/citations), [prezzi](https://platform.claude.com/docs/en/about-claude/pricing), [ridurre la latenza](https://platform.claude.com/docs/en/test-and-evaluate/strengthen-guardrails/reduce-latency)
- Anthropic, [Writing effective tools for agents](https://www.anthropic.com/engineering/writing-tools-for-agents) e [Building effective agents](https://www.anthropic.com/research/building-effective-agents)
- [Mobile-Agent-v3 (arXiv 2508.15144)](https://arxiv.org/abs/2508.15144)
- [AndroidWorld (arXiv 2405.14573)](https://arxiv.org/abs/2405.14573) e [pagina Google Research](https://research.google/pubs/androidworld-an-open-world-for-autonomous-agents/)
- Gemini su Android: [Engadget, Gemini Intelligence](https://engadget.com/2170770/gemini-intelligence-brings-app-automation-to-android), [Android Authority, computer use in Gemini 3.5 Flash](https://www.androidauthority.com/gemini-3-5-flash-computer-use-model-3681381/), [Philipp Schmid, controllare Android con Gemini](https://www.philschmid.de/gemini-android-use)
- OpenAI: [Introducing Operator](https://openai.com/index/introducing-operator/), [ChatGPT agent (help center)](https://help.openai.com/en/articles/11752874-chatgpt-agent)

Nota: i secondi indicati sono stime (non ho misurato su un telefono vero); i costi sono calcolati dai prezzi di listino e dalle dimensioni stimate di prompt e schermate.
