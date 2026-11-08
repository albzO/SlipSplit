package local.ocrbridge.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

class BoxStyle(val stroke: Color, val fill: Color, val width: Dp = 1.dp, val dashed: Boolean = false)

/** Where a photo sits once fitted into a canvas, so points move between the two. */
class PhotoFit(val scale: Float, val left: Float, val top: Float) {
    fun toCanvas(x: Float, y: Float) = Offset(left + x * scale, top + y * scale)
    fun toPhoto(point: Offset) = Offset((point.x - left) / scale, (point.y - top) / scale)
}

fun fitPhoto(photo: Bitmap, size: Size, margin: Float = 0f): PhotoFit {
    val scale = minOf((size.width - 2 * margin) / photo.width, (size.height - 2 * margin) / photo.height)
    return PhotoFit(scale, (size.width - photo.width * scale) / 2, (size.height - photo.height * scale) / 2)
}

/** Undoes a zoom about the canvas center and a pan, giving the photo pixel under a touch. */
fun touchToPhoto(point: Offset, size: Size, photo: Bitmap, zoom: Float, pan: Offset): Offset {
    val center = Offset(size.width / 2, size.height / 2)
    val unzoomed = (point - pan - center) / zoom + center
    return fitPhoto(photo, size).toPhoto(unzoomed)
}

/** A receipt photo with its text boxes; [style] returns null for a box to leave out. */
@Composable
fun ReceiptCanvas(
    photo: Bitmap,
    boxes: List<FloatArray>,
    style: (Int) -> BoxStyle?,
    modifier: Modifier = Modifier,
    zoom: Float = 1f,
    pan: Offset = Offset.Zero,
    overlay: DrawScope.(PhotoFit) -> Unit = {},
) {
    val image = remember(photo) { photo.asImageBitmap() }
    Canvas(modifier) {
        val fit = fitPhoto(photo, size)
        withTransform({
            translate(pan.x, pan.y)
            scale(zoom, zoom, center)
        }) {
            drawImage(
                image,
                dstOffset = IntOffset(fit.left.toInt(), fit.top.toInt()),
                dstSize = IntSize((photo.width * fit.scale).toInt(), (photo.height * fit.scale).toInt()),
            )
            val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx() / zoom, 3.dp.toPx() / zoom))
            boxes.forEachIndexed { index, corners ->
                val box = style(index) ?: return@forEachIndexed
                val path = Path().apply {
                    val first = fit.toCanvas(corners[0], corners[1])
                    moveTo(first.x, first.y)
                    for (corner in 1..3) {
                        val point = fit.toCanvas(corners[corner * 2], corners[corner * 2 + 1])
                        lineTo(point.x, point.y)
                    }
                    close()
                }
                drawPath(path, box.fill)
                drawPath(
                    path,
                    box.stroke,
                    style = Stroke(box.width.toPx() / zoom, pathEffect = if (box.dashed) dash else null),
                )
            }
            overlay(fit)
        }
    }
}
