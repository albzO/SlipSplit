package local.ocrbridge.ui

import android.graphics.RectF
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.launch
import local.ocrbridge.AppViewModel
import local.ocrbridge.R
import kotlin.math.max
import kotlin.math.min

private const val MAX_ZOOM = 6f
private val MONEY_TEXT = Regex("^[\$¥￥€£]?\\s*-?[0-9]+(?:[.,][0-9]{1,2})?$")

/** The receipt photo with its text boxes; when [picking], a tapped or framed text fills the draft. */
@Composable
fun PickScreen(vm: AppViewModel, picking: Boolean, onBack: () -> Unit) {
    val reading = vm.reading
    val scope = rememberCoroutineScope()
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var canvas by remember { mutableStateOf(Size.Zero) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var region by remember { mutableStateOf<RectF?>(null) }
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val boxes = remember(reading) { reading?.lines?.mapNotNull { it.corners }.orEmpty() }
    val texts = remember(reading) { reading?.lines?.filter { it.corners != null }?.map { it.text }.orEmpty() }

    Screen(Dark) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(start = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = Color.White)
                }
                Column {
                    Text(
                        stringResource(if (picking) R.string.pick_title else R.string.photo_title),
                        color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(if (picking) R.string.pick_hint else R.string.photo_hint),
                        color = OnDarkMuted, fontSize = 12.sp,
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp).onSizeChanged { canvas = it.toSize() }) {
                val photo = reading?.photo
                if (photo != null) {
                    ReceiptCanvas(
                        photo,
                        boxes,
                        style = { index ->
                            if (index == selected) BoxStyle(Color(0xFFC2560C), Color(0x33C2560C), 2.dp)
                            else BoxStyle(Blue.copy(alpha = 0.6f), Blue.copy(alpha = 0.08f))
                        },
                        modifier = Modifier.fillMaxSize().pointerInput(photo, picking) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                var moved = false
                                var pinched = false
                                val start = touchToPhoto(down.position, canvas, photo, zoom, pan)
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pressed = event.changes.filter { it.pressed }
                                    if (pressed.isEmpty()) break
                                    if (pressed.size > 1) {
                                        pinched = true
                                        zoom = (zoom * event.calculateZoom()).coerceIn(1f, MAX_ZOOM)
                                        pan += event.calculatePan()
                                        event.changes.forEach { it.consume() }
                                    } else if (!pinched) {
                                        val change = pressed.first()
                                        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                                        if (!moved) continue
                                        if (picking) {
                                            // One finger frames a part to read again; two fingers move the photo.
                                            val end = touchToPhoto(change.position, canvas, photo, zoom, pan)
                                            region = RectF(min(start.x, end.x), min(start.y, end.y), max(start.x, end.x), max(start.y, end.y))
                                            selected = null
                                        } else {
                                            pan += change.positionChange()
                                        }
                                        change.consume()
                                    }
                                }
                                if (!moved && !pinched) {
                                    val point = touchToPhoto(down.position, canvas, photo, zoom, pan)
                                    val hit = boxes.indexOfFirst { corners ->
                                        val xs = (0 until 4).map { corners[it * 2] }
                                        val ys = (0 until 4).map { corners[it * 2 + 1] }
                                        point.x in xs.min()..xs.max() && point.y in ys.min()..ys.max()
                                    }
                                    if (hit >= 0) {
                                        selected = hit
                                        region = null
                                        text = texts[hit]
                                    }
                                }
                            }
                        },
                        zoom = zoom,
                        pan = pan,
                        overlay = { fit ->
                            region?.let { frame ->
                                val topLeft = fit.toCanvas(frame.left, frame.top)
                                val bottomRight = fit.toCanvas(frame.right, frame.bottom)
                                drawRect(
                                    Color(0xFFC2560C),
                                    topLeft,
                                    androidx.compose.ui.geometry.Size(bottomRight.x - topLeft.x, bottomRight.y - topLeft.y),
                                    style = Stroke(2.dp.toPx() / zoom),
                                )
                            }
                        },
                    )
                }
            }
            AnimatedVisibility(picking, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                val area = region ?: selected?.let { index ->
                    val corners = boxes[index]
                    val xs = (0 until 4).map { corners[it * 2] }
                    val ys = (0 until 4).map { corners[it * 2 + 1] }
                    RectF(xs.min(), ys.min(), xs.max(), ys.max())
                }
                Column(
                    Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                        .navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.picked), Modifier.weight(1f), fontSize = 13.sp, color = Muted)
                        Text(
                            if (busy) stringResource(R.string.reading_region) else text.ifEmpty { "—" },
                            fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = Ink,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                val frame = area ?: return@Button
                                busy = true
                                scope.launch {
                                    text = vm.readRegion(frame)
                                    busy = false
                                }
                            },
                            enabled = area != null && !busy,
                            modifier = Modifier.weight(1f).height(48.dp).border(1.dp, Line, RoundedCornerShape(12.dp)),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Surface, contentColor = Ink),
                        ) { Text(stringResource(R.string.read_again)) }
                        Button(
                            onClick = {
                                vm.fillDraft(text)
                                onBack()
                            },
                            enabled = text.isNotBlank() && !busy,
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(
                                stringResource(if (MONEY_TEXT.matches(text.trim())) R.string.fill_price else R.string.fill_name),
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
            if (!picking) Spacer(Modifier.navigationBarsPadding())
        }
    }
}
