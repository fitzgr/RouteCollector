package com.grant.routecollector.voice

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent

object VoiceMarker {
    const val REQUEST_CODE = 9001

    fun start(activity: Activity) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a marker, e.g. 'camera intersection' or 'speed changes to 80'")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        activity.startActivityForResult(intent, REQUEST_CODE)
    }
}
