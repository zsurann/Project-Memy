package com.nexa.assistant.speech

import org.junit.Assume.assumeTrue
import org.junit.Test

class EdgeLatencyProbe {
    @Test
    fun probe() {
        assumeTrue(System.getenv("EDGE_PROBE") == "1")
        val tts = EdgeTts()
        val texts = listOf(
            "Certo, subito.",
            "Domani a Milano ci saranno circa diciotto gradi, con qualche nuvola al mattino e sole nel pomeriggio.",
            "Vuoi che ti imposti anche una sveglia?",
        )
        // Come nell'app: la connessione viene preparata mentre Claude pensa.
        tts.prewarm()
        Thread.sleep(1500)
        repeat(2) {
            for (t in texts) {
                val t0 = System.nanoTime()
                val bytes = tts.synthesize(t, "it-IT-IsabellaNeural")
                println("%3d caratteri -> %5d ms (%d byte)".format(t.length, (System.nanoTime() - t0) / 1_000_000, bytes.size))
            }
        }
    }
}
