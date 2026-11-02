package local.ocrbridge

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.hypot

/** Four draggable document corners; preview mode supports pinch zoom and pan. */
class QuadCropView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private var photo: Bitmap? = null
    var corners = FloatArray(8)
        private set
    var onCornerInteraction: (() -> Unit)? = null
    private var editing = true
    private val imageRect = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val density = resources.displayMetrics.density
    private var active = -1
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private var fingerX = 0f
    private var fingerY = 0f
    private val lensBounds = RectF()
    private var afterDraw: (() -> Unit)? = null
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private val pinch = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoom = (zoom * detector.scaleFactor).coerceIn(1f, 5f)
            if (zoom == 1f) { panX = 0f; panY = 0f }
            invalidate()
            return true
        }
    })

    fun show(bitmap: Bitmap, edit: Boolean, points: FloatArray? = null, onDisplayed: (() -> Unit)? = null) {
        photo = bitmap
        editing = edit
        active = -1
        afterDraw = onDisplayed
        corners = points?.copyOf() ?: Perspective.fullImage(bitmap.width, bitmap.height)
        zoom = 1f; panX = 0f; panY = 0f
        invalidate()
    }

    private fun fit() {
        val bitmap = photo ?: return
        val margin = 24f * density
        val scale = minOf((width - margin * 2).coerceAtLeast(1f) / bitmap.width,
            (height - margin * 2).coerceAtLeast(1f) / bitmap.height)
        val left = (width - bitmap.width * scale) / 2
        val top = (height - bitmap.height * scale) / 2
        imageRect.set(left, top, left + bitmap.width * scale, top + bitmap.height * scale)
    }

    override fun onDraw(canvas: Canvas) {
        val bitmap = photo ?: return
        fit()
        canvas.save()
        if (!editing) {
            canvas.translate(width / 2f + panX, height / 2f + panY)
            canvas.scale(zoom, zoom)
            canvas.translate(-width / 2f, -height / 2f)
        }
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawBitmap(bitmap, null, imageRect, paint)
        canvas.restore()
        // Schedule work only after this image has participated in a rendered frame.
        afterDraw?.let { callback -> afterDraw = null; post { callback() } }
        if (!editing) return
        val scale = imageRect.width() / bitmap.width
        val path = Path()
        for (i in 0..3) {
            val x = imageRect.left + corners[i * 2] * scale
            val y = imageRect.top + corners[i * 2 + 1] * scale
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        val shade = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(imageRect, Path.Direction.CW)
            addPath(path)
        }
        paint.color = 0x88000000.toInt()
        canvas.drawPath(shade, paint)
        paint.color = context.getColor(R.color.accent)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f * density
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        for (i in 0..3) {
            val x = imageRect.left + corners[i * 2] * scale
            val y = imageRect.top + corners[i * 2 + 1] * scale
            paint.color = Color.WHITE
            canvas.drawCircle(x, y, 10f * density, paint)
            paint.color = context.getColor(R.color.accent)
            canvas.drawCircle(x, y, 6f * density, paint)
        }
        if (active >= 0) drawMagnifier(canvas, bitmap, scale)
    }

    /** Show the exact corner under a crosshair, clear of the user's finger. */
    private fun drawMagnifier(canvas: Canvas, bitmap: Bitmap, imageScale: Float) {
        val radius = minOf(56f * density, width / 4f, height / 4f)
        val padding = 8f * density
        val distance = radius + 64f * density
        val positions = arrayOf(
            PointF(fingerX, fingerY - distance), PointF(fingerX, fingerY + distance),
            PointF(fingerX + distance, fingerY), PointF(fingerX - distance, fingerY),
        ).map {
            PointF(it.x.coerceIn(radius + padding, width - radius - padding),
                it.y.coerceIn(radius + padding, height - radius - padding))
        }
        val center = positions.firstOrNull { hypot(it.x - fingerX, it.y - fingerY) >= radius + 40f * density }
            ?: positions.maxBy { hypot(it.x - fingerX, it.y - fingerY) }
        lensBounds.set(center.x - radius, center.y - radius, center.x + radius, center.y + radius)
        paint.style = Paint.Style.FILL
        paint.color = 0xCC000000.toInt()
        canvas.drawCircle(center.x, center.y, radius + 4f * density, paint)
        canvas.save()
        canvas.clipPath(Path().apply { addCircle(center.x, center.y, radius, Path.Direction.CW) })
        canvas.drawColor(0xFF101918.toInt())
        canvas.translate(center.x, center.y)
        // Magnify relative to the fitted photo, including at full-resolution image edges.
        val lensScale = maxOf(imageScale * 3f, 1f)
        canvas.scale(lensScale, lensScale)
        canvas.translate(-corners[active * 2], -corners[active * 2 + 1])
        paint.color = Color.WHITE
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        canvas.restore()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        paint.color = Color.WHITE
        canvas.drawCircle(center.x, center.y, radius, paint)
        // A hollow center leaves the paper edge visible for precise placement.
        paint.color = context.getColor(R.color.accent)
        val gap = 4f * density
        val arm = 16f * density
        canvas.drawLine(center.x - arm, center.y, center.x - gap, center.y, paint)
        canvas.drawLine(center.x + gap, center.y, center.x + arm, center.y, paint)
        canvas.drawLine(center.x, center.y - arm, center.x, center.y - gap, paint)
        canvas.drawLine(center.x, center.y + gap, center.x, center.y + arm, paint)
        canvas.drawCircle(center.x, center.y, 2f * density, paint)
        paint.style = Paint.Style.FILL
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val bitmap = photo ?: return false
        if (!isEnabled) return false
        if (!editing) {
            pinch.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_MOVE && !pinch.isInProgress && zoom > 1f) {
                panX += event.x - lastX; panY += event.y - lastY
                panX = panX.coerceIn(-width * (zoom - 1f) / 2, width * (zoom - 1f) / 2)
                panY = panY.coerceIn(-height * (zoom - 1f) / 2, height * (zoom - 1f) / 2)
                invalidate()
            }
            lastX = event.x; lastY = event.y
            if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            return true
        }
        fit()
        val scale = imageRect.width() / bitmap.width
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                active = (0..3).minByOrNull { i ->
                    hypot(event.x - imageRect.left - corners[i * 2] * scale,
                        event.y - imageRect.top - corners[i * 2 + 1] * scale)
                } ?: -1
                if (active >= 0 && hypot(event.x - imageRect.left - corners[active * 2] * scale,
                        event.y - imageRect.top - corners[active * 2 + 1] * scale) > 40 * density) active = -1
                if (active >= 0) {
                    onCornerInteraction?.invoke()
                    dragOffsetX = imageRect.left + corners[active * 2] * scale - event.x
                    dragOffsetY = imageRect.top + corners[active * 2 + 1] * scale - event.y
                    fingerX = event.x; fingerY = event.y
                    parent?.requestDisallowInterceptTouchEvent(true)
                    invalidate()
                }
                return active >= 0
            }
            MotionEvent.ACTION_MOVE -> if (active >= 0) {
                val candidate = corners.copyOf()
                fingerX = event.x; fingerY = event.y
                candidate[active * 2] = ((event.x + dragOffsetX - imageRect.left) / scale).coerceIn(0f, bitmap.width - 1f)
                candidate[active * 2 + 1] = ((event.y + dragOffsetY - imageRect.top) / scale).coerceIn(0f, bitmap.height - 1f)
                if (Perspective.valid(candidate)) {
                    corners = candidate
                    onCornerInteraction?.invoke()
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                active = -1
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
