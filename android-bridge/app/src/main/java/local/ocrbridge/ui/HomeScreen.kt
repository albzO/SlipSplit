package local.ocrbridge.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import local.ocrbridge.AppViewModel
import local.ocrbridge.R
import local.ocrbridge.ReceiptLanguage
import java.io.File

private val HISTORY_SWIPE = 60.dp
private const val BOB_MS = 700

@Composable
fun HomeScreen(vm: AppViewModel, onPhoto: (Uri) -> Unit, onSettings: () -> Unit, onHistory: () -> Unit) {
    val context = LocalContext.current
    val captureUri = remember {
        val file = File(context.cacheDir, "photos/capture.jpg").apply { parentFile?.mkdirs() }
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        if (taken) onPhoto(captureUri)
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(onPhoto)
    }
    // An upward swipe anywhere on the start screen pulls the history up, once there is any.
    val hasHistory = vm.history.isNotEmpty()
    val swipe = if (!hasHistory) Modifier else Modifier.pointerInput(Unit) {
        var travelled = 0f
        detectVerticalDragGestures(
            onDragStart = { travelled = 0f },
            onDragEnd = { if (-travelled > HISTORY_SWIPE.toPx()) onHistory() },
        ) { change, amount ->
            travelled += amount
            change.consume()
        }
    }
    Screen {
        Column(Modifier.fillMaxSize().navigationBarsPadding().then(swipe)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.app_name), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings), tint = Ink)
                }
            }
            Column(
                Modifier.weight(1f).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterVertically),
            ) {
                Text(stringResource(R.string.home_headline), fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, color = Ink)
                LanguagePicker(vm.language, vm::chooseLanguage)
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { camera.launch(captureUri) },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) { ButtonLabel(CameraIcon, stringResource(R.string.camera)) }
                    Button(
                        onClick = { gallery.launch("image/*") },
                        modifier = Modifier.fillMaxWidth().height(56.dp).border(1.dp, Line, RoundedCornerShape(16.dp)),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Surface, contentColor = Ink),
                    ) { ButtonLabel(GalleryIcon, stringResource(R.string.gallery)) }
                }
            }
            AnimatedVisibility(hasHistory, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                HistoryHandle(onHistory)
            }
            Text(
                stringResource(R.string.home_footer),
                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 20.dp),
                fontSize = 12.sp,
                color = Muted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** A bobbing arrow that says history is one swipe up; tapping it works too. */
@Composable
private fun HistoryHandle(onHistory: () -> Unit) {
    val bob by rememberInfiniteTransition(label = "bob").animateFloat(
        0f, -6f, infiniteRepeatable(tween(BOB_MS), RepeatMode.Reverse), label = "bobOffset",
    )
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onHistory).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.KeyboardArrowUp, null,
            Modifier.size(28.dp).graphicsLayer { translationY = bob.dp.toPx() },
            tint = Ink,
        )
        Text(stringResource(R.string.history_swipe), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Ink)
    }
}

@Composable
private fun ButtonLabel(icon: ImageVector, text: String) {
    Icon(icon, null, Modifier.size(22.dp))
    Spacer(Modifier.width(10.dp))
    Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun LanguagePicker(selected: ReceiptLanguage, onSelect: (ReceiptLanguage) -> Unit) {
    val options = listOf(
        Triple(ReceiptLanguage.AUTO, R.string.language_auto, R.string.language_auto_hint),
        Triple(ReceiptLanguage.ENGLISH, R.string.language_english, R.string.language_english_hint),
        Triple(ReceiptLanguage.CHINESE, R.string.language_chinese, R.string.language_chinese_hint),
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.language_title), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Muted)
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Track).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for ((language, label, _) in options) {
                val on = language == selected
                val color by animateColorAsState(if (on) Surface else Track, label = "language")
                Box(
                    Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(9.dp)).background(color)
                        .clickable { onSelect(language) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(label),
                        fontSize = 15.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (on) Ink else Muted,
                    )
                }
            }
        }
        Text(stringResource(options.first { it.first == selected }.third), fontSize = 13.sp, color = Muted)
    }
}
