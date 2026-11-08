package local.ocrbridge.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import local.ocrbridge.AppViewModel
import local.ocrbridge.QuadCropView
import local.ocrbridge.R

@Composable
fun CropScreen(vm: AppViewModel, onBack: () -> Unit, onStart: (FloatArray) -> Unit) {
    var view by remember { mutableStateOf<QuadCropView?>(null) }
    val photo = vm.preview
    val corners = vm.corners
    Screen(Dark) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(start = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = Color.White)
                }
                Text(stringResource(R.string.crop_title), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                AnimatedContent(photo != null, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "photo") { loaded ->
                    if (!loaded) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(color = Color.White)
                            Text(stringResource(R.string.crop_loading), color = OnDarkMuted, fontSize = 14.sp)
                        }
                    } else {
                        AndroidView(
                            factory = { context ->
                                QuadCropView(context, null).apply { onCornerInteraction = vm::touchCorners }
                                    .also { view = it }
                            },
                            update = { cropView ->
                                // Show only when the photo or the found corners change, never over a drag.
                                val shown = photo to corners
                                if (photo != null && cropView.tag != shown) {
                                    cropView.tag = shown
                                    cropView.show(photo, true, corners)
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            val hint = when {
                photo == null -> ""
                vm.detecting -> stringResource(R.string.crop_detecting)
                vm.edgesFound -> stringResource(R.string.crop_found)
                else -> stringResource(R.string.crop_not_found)
            }
            Text(
                hint,
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                color = OnDarkMuted,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center,
            )
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = onBack,
                    modifier = Modifier.weight(1f).height(52.dp).border(1.dp, Color(0xFF4A4E55), RoundedCornerShape(14.dp)),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Dark, contentColor = Color.White),
                ) { Text(stringResource(R.string.retake), fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                Button(
                    onClick = { view?.let { onStart(it.corners.copyOf()) } },
                    enabled = photo != null,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Ink),
                ) { Text(stringResource(R.string.start_reading), fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}
