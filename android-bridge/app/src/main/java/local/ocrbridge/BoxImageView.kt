package local.ocrbridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** A text box and the color it is outlined in. */
class ColoredBox(val box: IntArray, val color: Int)

private const val MAX_ASPECT = 1.4f
private const val STROKE_DP = 1.5f

/** Draws the photo with the detected text boxes on top, fitted to the view's width. */
class BoxImageView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private var bitmap: Bitmap? = null
    private var boxes: List<ColoredBox> = emptyList()
    private val box = RectF()
    private val image = RectF()
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE_DP * resources.displayMetrics.density
    }

    fun show(photo: Bitmap, textBoxes: List<ColoredBox>) {
        bitmap = photo
        boxes = textBoxes
        visibility = VISIBLE
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val photo = bitmap
        val aspect = if (photo == null) 0f else photo.height.toFloat() / photo.width
        setMeasuredDimension(width, (width * minOf(aspect, MAX_ASPECT)).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val photo = bitmap ?: return
        val scale = minOf(width.toFloat() / photo.width, height.toFloat() / photo.height)
        val left = (width - photo.width * scale) / 2
        val top = (height - photo.height * scale) / 2
        image.set(left, top, left + photo.width * scale, top + photo.height * scale)
        canvas.drawBitmap(photo, Rect(0, 0, photo.width, photo.height), image, imagePaint)
        // Gray first, so colored outlines stay on top where boxes touch.
        val unused = context.getColor(R.color.unused_box)
        for (item in boxes.sortedBy { it.color != unused }) {
            box.set(
                left + item.box[0] * scale,
                top + item.box[1] * scale,
                left + item.box[2] * scale,
                top + item.box[3] * scale,
            )
            boxPaint.color = item.color
            canvas.drawRect(box, boxPaint)
        }
    }
}
