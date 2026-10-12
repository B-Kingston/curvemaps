package com.curvemaps.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log

/**
 * Receives a GPX file opened ("Open with") or shared from another app — Files,
 * mail, a chat — and hands it to the planner in CurveMaps' own task, so a
 * second planner never starts inside the other app. The read grant travels
 * with the hand-over. It shows nothing and finishes at once.
 */
class OpenGpxActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = gpxUriOf(intent)
        if (uri == null) {
            Log.w(TAG, "No GPX file in the ${intent?.action} intent")
        } else {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_OPEN_GPX)
                    .setData(uri)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    ),
            )
        }
        finish()
    }

    private companion object {
        const val TAG = "CurveMaps.OpenGpx"
    }
}

/** The file an "Open with" (VIEW) or share (SEND) intent carries, if any. */
internal fun gpxUriOf(intent: Intent?): Uri? = when (intent?.action) {
    Intent.ACTION_VIEW -> intent.data
    Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(Intent.EXTRA_STREAM)
    } ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
    else -> null
}
