package com.pluginslab.agentbridge.agent

import android.content.Context
import android.speech.tts.TextToSpeech
import android.os.Handler
import android.os.Looper
import java.util.Locale

/** Android system TTS, no provider token and no forced third-party cloud TTS. */
object VoiceOutput {
    private var synth: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null
    private val main=Handler(Looper.getMainLooper())
    fun say(context: Context, text: String) {
        main.post {
            // Only the final short status; logs and device screen dumps are never spoken.
            val prefs=AgentPrefs(context)
            synth?.setPitch(prefs.speechPitch);synth?.setSpeechRate(prefs.speechRate)
            pending = text.take(1800)
            if(synth == null) {
                synth = TextToSpeech(context.applicationContext) { status ->
                    ready = status == TextToSpeech.SUCCESS
                    if(ready) {
                        synth?.language = Locale("ru", "RU")
                        synth?.setPitch(prefs.speechPitch);synth?.setSpeechRate(prefs.speechRate)
                        flush()
                    }
                }
            } else flush()
        }
    }
    fun stop() { main.post { pending=null;synth?.stop() } }
    private fun flush() {
        if(!ready) return
        val text=pending ?: return
        pending=null
        synth?.speak(text,TextToSpeech.QUEUE_FLUSH,null,"agentbridge-final")
    }
}
