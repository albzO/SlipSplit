package local.ocrbridge

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import java.util.UUID

/** Single-use, bounded in-process handoff: never parcel a full bitmap through Binder. */
internal object ApprovedPhotoHandoff {
    const val TOKEN = "approved_photo_token"
    const val CORNERS = "approved_photo_corners"
    const val POINT_WIDTH = "approved_point_width"
    const val POINT_HEIGHT = "approved_point_height"
    private const val MAX_AGE_MS = 120_000L
    private data class Entry(val token: String, val bitmap: Bitmap, val created: Long)
    private var pending: Entry? = null
    private val cleanup = android.os.Handler(android.os.Looper.getMainLooper())

    @Synchronized fun put(bitmap: Bitmap): String {
        val token = UUID.randomUUID().toString()
        pending = Entry(token, bitmap, SystemClock.elapsedRealtime())
        cleanup.postDelayed({ discard(token) }, MAX_AGE_MS)
        return token
    }

    @Synchronized private fun discard(token: String) {
        // Drop the reference, without recycling a bitmap another screen may still draw.
        if (pending?.token == token) pending = null
    }

    @Synchronized fun take(token: String?): Bitmap? {
        val entry = pending ?: return null
        if (entry.token != token) return null
        pending = null
        return entry.bitmap.takeIf { SystemClock.elapsedRealtime() - entry.created <= MAX_AGE_MS }
    }

    /** If Android killed the process during the Activity transition, rebuild from the
     * retained source and tiny recipe instead of requiring a PNG write on every tap. */
    fun restore(context: Context, intent: Intent): Bitmap {
        val source = intent.getStringExtra(PhotoReviewActivity.ORIGINAL)?.let(Uri::parse)
            ?: error("照片已失效，请重新选图")
        val corners = intent.getFloatArrayExtra(CORNERS) ?: error("照片已失效，请重新选图")
        val width = intent.getIntExtra(POINT_WIDTH, 0)
        val height = intent.getIntExtra(POINT_HEIGHT, 0)
        require(corners.size == 8 && width > 1 && height > 1) { "照片已失效，请重新选图" }
        val original = loadReceiptPhoto(context, source)
        val rectified = try {
            val points = FloatArray(8) { i ->
                if (i % 2 == 0) corners[i] * (original.width - 1f) / (width - 1f)
                else corners[i] * (original.height - 1f) / (height - 1f)
            }
            Perspective.rectify(original, points)
        } finally {
            original.recycle()
        }
        var keepRectified = false
        try {
            val result = when (intent.getStringExtra(PhotoReviewActivity.BACKEND)) {
                "dewarpnet" -> DewarpNet(context).use { it.flatten(rectified) }
                "uvdoc" -> Dewarper(context).use { it.flatten(rectified) }
                else -> rectified
            }
            keepRectified = result === rectified
            return result
        } finally {
            if (!keepRectified) rectified.recycle()
        }
    }
}
