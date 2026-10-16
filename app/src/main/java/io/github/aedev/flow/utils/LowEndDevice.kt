package io.github.aedev.flow.utils

import android.app.ActivityManager
import android.content.Context
import io.github.aedev.flow.data.local.ThumbnailQuality

/**
 * Whether this device is short on memory, and the lighter defaults to use there.
 *
 * Uses the same threshold the player already applies for its buffers and frame-size cap
 * (`LoadControlFactory`, `VideoSizeCap`): a flagged low-RAM device, or an app heap of 256 MB or
 * less. Only *defaults* come from here — anything the viewer has chosen in Settings is never
 * overridden, so a user with a stored preference sees no change.
 */
object LowEndDevice {
    private const val LOW_HEAP_MB = 256

    @Volatile
    private var cached: Boolean? = null

    fun isLowEnd(context: Context): Boolean =
        cached ?: run {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val lowEnd =
                activityManager?.isLowRamDevice == true ||
                    (activityManager?.memoryClass ?: Int.MAX_VALUE) <= LOW_HEAP_MB
            cached = lowEnd
            lowEnd
        }

    /** 1080p is more than a weak decoder and a small heap hold comfortably; 720p is the safe step down. */
    fun defaultWifiVideoQuality(context: Context): String = if (isLowEnd(context)) "720p" else "1080p"

    /** Full-size thumbnails cost download, decode and bitmap memory on every card in view. */
    fun defaultThumbnailQuality(context: Context): ThumbnailQuality =
        if (isLowEnd(context)) ThumbnailQuality.MEDIUM else ThumbnailQuality.HIGH
}
