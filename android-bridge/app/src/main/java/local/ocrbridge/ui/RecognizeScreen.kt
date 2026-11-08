package local.ocrbridge.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import android.graphics.Bitmap
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.toSize
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.ocrbridge.AppViewModel
import local.ocrbridge.GROUP_CORRECTED
import local.ocrbridge.GROUP_NONE
import local.ocrbridge.GROUP_TOTALS
import local.ocrbridge.R
import local.ocrbridge.Recognition
import local.ocrbridge.Stage

private const val REVEAL_MS_PER_BOX = 12
private const val REVEAL_MAX_MS = 600
private const val PULSE_MS = 600
private const val FOCUS_MS = 700
private const val MAX_FOCUS_ZOOM = 3.5f
private const val MAX_USER_ZOOM = 6f

@Composable
fun RecognizeScreen(vm: AppViewModel, onCancel: () -> Unit, onDone: () -> Unit) {
    val state by vm.recognition.collectAsState()
    // Fixed prices wait for the user to check them against the photo before the split opens.
    LaunchedEffect(state.stage) {
        if (state.stage == Stage.DONE && state.corrections.isEmpty()) onDone()
    }
    // Boxes appear one after another once detection hands them over.
    val reveal = remember(state.boxes) { Animatable(0f) }
    LaunchedEffect(state.boxes) {
        val organized = state.stage == Stage.ORGANIZE || state.stage == Stage.DONE
        if (organized) reveal.snapTo(state.boxes.size.toFloat())
        else reveal.animateTo(
            state.boxes.size.toFloat(),
            tween(minOf(REVEAL_MAX_MS, REVEAL_MS_PER_BOX * state.boxes.size), easing = LinearEasing),
        )
    }
    Screen {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(start = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, stringResource(R.string.cancel), tint = Ink) }
                Text(stringResource(R.string.recognize_title), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink)
            }
            val review = state.stage == Stage.DONE && state.corrections.isNotEmpty()
            // While the user checks a fix, its box blinks and the photo zooms in on it.
            val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
                0.25f, 1f, infiniteRepeatable(tween(PULSE_MS), RepeatMode.Reverse), label = "pulseAlpha",
            )
            var canvas by remember { mutableStateOf(Size.Zero) }
            val zoom = remember { Animatable(1f) }
            val pan = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
            LaunchedEffect(review, canvas) {
                val photo = state.photo
                if (!review || photo == null || canvas == Size.Zero) return@LaunchedEffect
                val (scale, offset) = focusOn(state, photo, canvas)
                launch { zoom.animateTo(scale, tween(FOCUS_MS)) }
                pan.animateTo(offset, tween(FOCUS_MS))
            }
            val scope = rememberCoroutineScope()
            Box(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clipToBounds()
                    .onSizeChanged { canvas = it.toSize() }
                    // Once zoomed onto a fix, the user can move and zoom the photo freely to check it.
                    .pointerInput(review) {
                        if (!review) return@pointerInput
                        detectTransformGestures { centroid, move, scale, _ ->
                            scope.launch {
                                val center = Offset(canvas.width / 2, canvas.height / 2)
                                val next = (zoom.value * scale).coerceIn(1f, MAX_USER_ZOOM)
                                val applied = next / zoom.value
                                // Keep the point under the fingers still while zooming.
                                val offset = (pan.value + center - centroid) * applied + centroid - center + move
                                zoom.snapTo(next)
                                pan.snapTo(offset)
                            }
                        }
                    },
            ) {
                Crossfade(state.photo, label = "photo") { photo ->
                    if (photo != null) {
                        val current = photo === state.photo
                        ReceiptCanvas(
                            photo,
                            if (current) state.boxes else emptyList(),
                            style = { index -> boxStyle(state, index, reveal.value, if (review) pulse else null) },
                            modifier = Modifier.fillMaxSize(),
                            zoom = zoom.value,
                            pan = pan.value,
                        )
                    }
                }
            }
            ProgressPanel(state, onCancel, onDone)
        }
    }
}

/** Zoom and pan that frame every corrected box in the middle of the canvas. */
private fun focusOn(state: Recognition, photo: Bitmap, canvas: Size): Pair<Float, Offset> {
    val boxes = state.boxes.filterIndexed { index, _ -> state.groups.getOrNull(index) == GROUP_CORRECTED }
    if (boxes.isEmpty()) return 1f to Offset.Zero
    val xs = boxes.flatMap { corners -> (0 until 4).map { corners[it * 2] } }
    val ys = boxes.flatMap { corners -> (0 until 4).map { corners[it * 2 + 1] } }
    val fit = fitPhoto(photo, canvas)
    val topLeft = fit.toCanvas(xs.min(), ys.min())
    val bottomRight = fit.toCanvas(xs.max(), ys.max())
    // Show the price with the line around it: never closer than the box filling a third of the width.
    val width = (bottomRight.x - topLeft.x).coerceAtLeast(1f)
    val height = (bottomRight.y - topLeft.y).coerceAtLeast(1f)
    val scale = minOf(MAX_FOCUS_ZOOM, canvas.width / (width * 3), canvas.height / (height * 3)).coerceAtLeast(1f)
    val target = Offset((topLeft.x + bottomRight.x) / 2, (topLeft.y + bottomRight.y) / 2)
    val center = Offset(canvas.width / 2, canvas.height / 2)
    return scale to (center - target) * scale
}

private fun boxStyle(state: Recognition, index: Int, reveal: Float, pulse: Float?): BoxStyle? {
    if (index >= reveal) return null
    if (state.stage == Stage.ORGANIZE || state.stage == Stage.DONE) {
        // While a fix is checked, everything but it fades back.
        val quiet = if (pulse != null) 0.35f else 1f
        return when (val group = state.groups.getOrElse(index) { GROUP_NONE }) {
            GROUP_TOTALS -> BoxStyle(Ink.copy(alpha = quiet), Ink.copy(alpha = 0.08f * quiet), 1.5.dp)
            // Only the outline blinks; a fill would hide the very digits to check.
            GROUP_CORRECTED -> BoxStyle(Fixed.copy(alpha = pulse ?: 1f), Color.Transparent, 3.dp)
            GROUP_NONE -> BoxStyle(Color(0xFFC9C6BE).copy(alpha = quiet), Color.Transparent)
            else -> BoxStyle(groupColor(group).copy(alpha = quiet), groupColor(group).copy(alpha = 0.16f * quiet), 1.5.dp)
        }
    }
    val reading = state.stage == Stage.READ
    return when {
        reading && index == state.read -> BoxStyle(Blue, Blue.copy(alpha = 0.3f), 2.dp)
        index < state.read && state.stage != Stage.DETECT -> BoxStyle(Blue, Blue.copy(alpha = 0.12f))
        else -> BoxStyle(Color(0xFF9AA0A8), Color(0xFF9AA0A8).copy(alpha = 0.12f), dashed = true)
    }
}

@Composable
private fun ProgressPanel(state: Recognition, onCancel: () -> Unit, onDone: () -> Unit) {
    val total = state.boxes.size
    val review = state.stage == Stage.DONE && state.corrections.isNotEmpty()
    val (status, color) = if (review) {
        stringResource(R.string.review_title, state.corrections.size) to Warn
    } else when (state.stage) {
        Stage.FLATTEN -> stringResource(R.string.stage_flatten) to Ink
        Stage.DETECT -> stringResource(if (state.pass == 1) R.string.stage_detect else R.string.stage_detect_again) to Ink
        Stage.READ -> stringResource(if (state.pass == 1) R.string.stage_read else R.string.stage_read_again) to Ink
        Stage.LEVEL -> stringResource(R.string.stage_level) to Warn
        Stage.ORGANIZE -> stringResource(R.string.stage_organize) to Ink
        Stage.DONE -> stringResource(
            if (state.matches) R.string.stage_done_match else R.string.stage_done_check,
            state.itemCount,
        ) to Good
        Stage.FAILED -> stringResource(R.string.stage_failed, state.error.orEmpty()) to Danger
    }
    val counter = when (state.stage) {
        Stage.READ -> stringResource(R.string.lines_read, state.read, total)
        Stage.LEVEL -> stringResource(R.string.lines_read, total, total)
        Stage.ORGANIZE, Stage.DONE -> stringResource(R.string.lines_total, total)
        else -> ""
    }
    val first = when {
        state.pass > 1 || state.stage == Stage.LEVEL -> 1f
        state.stage == Stage.READ && total > 0 -> state.read.toFloat() / total
        state.stage == Stage.ORGANIZE || state.stage == Stage.DONE -> 1f
        else -> 0f
    }
    val second = when {
        state.pass < 2 -> 0f
        state.stage == Stage.READ && total > 0 -> state.read.toFloat() / total
        state.stage == Stage.ORGANIZE || state.stage == Stage.DONE -> 1f
        else -> 0f
    }
    val firstShown by animateFloatAsState(first, tween(120), label = "first")
    val secondShown by animateFloatAsState(second, tween(120), label = "second")
    val firstColor by animateColorAsState(if (state.stage == Stage.LEVEL) Color(0xFFC2560C) else Blue, label = "barColor")
    Column(
        Modifier.fillMaxWidth().background(Surface).navigationBarsPadding()
            .padding(start = Gutter, end = Gutter, top = 14.dp, bottom = 20.dp)
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(status, Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = color)
            Text(counter, fontSize = 13.sp, color = Muted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Bar(firstShown, firstColor)
                if (state.twoPasses) Text(stringResource(R.string.pass_first), fontSize = 11.sp, color = Muted)
            }
            AnimatedVisibility(state.twoPasses, Modifier.weight(1f), enter = expandHorizontally() + fadeIn()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Bar(secondShown, Blue)
                    Text(stringResource(R.string.pass_second), fontSize = 11.sp, color = Muted)
                }
            }
        }
        if (review) {
            for (fix in state.corrections) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(fix.name, Modifier.weight(1f), fontSize = 14.sp, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(money(fix.readAs), fontSize = 14.sp, color = Muted, textDecoration = TextDecoration.LineThrough)
                    Text(" → " + money(fix.fixed), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Fixed)
                }
            }
            Text(stringResource(R.string.review_hint), fontSize = 12.sp, color = Muted)
            Button(onClick = onDone, Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp)) {
                Text(stringResource(R.string.review_confirm), fontWeight = FontWeight.SemiBold)
            }
        } else Column(Modifier.height(76.dp), verticalArrangement = Arrangement.Bottom) {
            state.recent.forEachIndexed { index, text ->
                Text(
                    text,
                    Modifier.alpha(0.35f + 0.65f * (index + 1) / state.recent.size),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (state.stage == Stage.FAILED) {
            Button(onClick = onCancel, Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp)) {
                Text(stringResource(R.string.back))
            }
        }
        Spacer(Modifier.height(0.dp))
    }
}

@Composable
private fun Bar(progress: Float, color: Color) {
    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
        color = color,
        trackColor = Track,
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}
