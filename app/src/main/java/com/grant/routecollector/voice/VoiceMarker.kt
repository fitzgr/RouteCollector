package com.grant.routecollector.voice

import android.content.Intent
import android.speech.RecognizerIntent

object VoiceMarker {
    fun createIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a marker, e.g. 'camera intersection' or 'speed changes to 80'")
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
    }
}
