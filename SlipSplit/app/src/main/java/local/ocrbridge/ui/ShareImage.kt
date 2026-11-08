package local.ocrbridge.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.ui.graphics.toArgb

private const val WIDTH = 1080
private const val SCALE = 3f
private const val MARGIN = 16 * SCALE
private const val CARD_PADDING = 14 * SCALE
private const val CARD_GAP = 12 * SCALE
private const val CARD_RADIUS = 16 * SCALE
private const val LINE_HEIGHT = 22 * SCALE
private const val BADGE = 32 * SCALE

/** One line of a person's share; muted lines are whole-bill adjustments. */
class ShareImageLine(val name: String, val amount: String, val muted: Boolean = false)

class ShareImagePerson(val name: String, val color: Int, val total: String, val lines: List<ShareImageLine>)

/**
 * Draws the split as one tall picture, laid out like the summary screen, so it can be sent to a
 * chat whole instead of only the part of the list that fits on screen.
 */
fun renderSplitImage(
    title: String,
    subtitle: String,
    total: String,
    people: List<ShareImagePerson>,
    footer: String,
): Bitmap {
    fun paint(size: Float, color: Int, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size * SCALE
        this.color = color
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }
    val ink = Ink.toArgb()
    val muted = Muted.toArgb()
    val titlePaint = paint(20f, ink, bold = true)
    val subtitlePaint = paint(13f, muted)
    val totalPaint = paint(28f, ink, bold = true)
    val namePaint = paint(16f, ink, bold = true)
    val personTotalPaint = paint(22f, ink, bold = true)
    val linePaint = paint(14f, ink)
    val mutedPaint = paint(14f, muted)
    val badgePaint = paint(14f, android.graphics.Color.WHITE, bold = true).apply { textAlign = Paint.Align.CENTER }
    val footerPaint = paint(12f, muted).apply { textAlign = Paint.Align.CENTER }

    val headerHeight = MARGIN + 48 * SCALE + 12 * SCALE
    val cardHeights = people.map { person -> CARD_PADDING * 2 + BADGE + 18 * SCALE + person.lines.size * LINE_HEIGHT }
    val height = (headerHeight + cardHeights.sum() + CARD_GAP * people.size + 40 * SCALE).toInt()

    val bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Background.toArgb())

    val right = WIDTH - MARGIN
    canvas.drawText(total, right - totalPaint.measureText(total), MARGIN + 34 * SCALE, totalPaint)
    val headerWidth = right - MARGIN - totalPaint.measureText(total) - 12 * SCALE
    canvas.drawText(TextUtils.ellipsize(title, titlePaint, headerWidth, TextUtils.TruncateAt.END).toString(), MARGIN + CARD_PADDING, MARGIN + 22 * SCALE, titlePaint)
    canvas.drawText(TextUtils.ellipsize(subtitle, subtitlePaint, headerWidth, TextUtils.TruncateAt.END).toString(), MARGIN + CARD_PADDING, MARGIN + 42 * SCALE, subtitlePaint)

    val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Surface.toArgb() }
    val dividerPaint = Paint().apply { color = Line.toArgb(); strokeWidth = SCALE }
    var top = headerHeight
    people.forEachIndexed { index, person ->
        val bottom = top + cardHeights[index]
        canvas.drawRoundRect(RectF(MARGIN, top, right, bottom), CARD_RADIUS, CARD_RADIUS, cardPaint)
        val left = MARGIN + CARD_PADDING
        val inner = right - CARD_PADDING
        val badgeCenterY = top + CARD_PADDING + BADGE / 2
        canvas.drawCircle(left + BADGE / 2, badgeCenterY, BADGE / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = person.color })
        val letterBaseline = badgeCenterY - (badgePaint.descent() + badgePaint.ascent()) / 2
        canvas.drawText(person.name.take(1), left + BADGE / 2, letterBaseline, badgePaint)
        val nameBaseline = badgeCenterY - (namePaint.descent() + namePaint.ascent()) / 2
        canvas.drawText(person.total, inner - personTotalPaint.measureText(person.total), badgeCenterY - (personTotalPaint.descent() + personTotalPaint.ascent()) / 2, personTotalPaint)
        val nameWidth = inner - personTotalPaint.measureText(person.total) - left - BADGE - 20 * SCALE
        canvas.drawText(TextUtils.ellipsize(person.name, namePaint, nameWidth, TextUtils.TruncateAt.END).toString(), left + BADGE + 10 * SCALE, nameBaseline, namePaint)
        val dividerY = top + CARD_PADDING + BADGE + 9 * SCALE
        canvas.drawLine(left, dividerY, inner, dividerY, dividerPaint)
        var baseline = dividerY + 9 * SCALE + 16 * SCALE
        for (line in person.lines) {
            val textPaint = if (line.muted) mutedPaint else linePaint
            val amountWidth = textPaint.measureText(line.amount)
            canvas.drawText(line.amount, inner - amountWidth, baseline, textPaint)
            val name = TextUtils.ellipsize(line.name, textPaint, inner - left - amountWidth - 12 * SCALE, TextUtils.TruncateAt.END)
            canvas.drawText(name.toString(), left, baseline, textPaint)
            baseline += LINE_HEIGHT
        }
        top = bottom + CARD_GAP
    }
    canvas.drawText(footer, WIDTH / 2f, height - 16 * SCALE, footerPaint)
    return bitmap
}
