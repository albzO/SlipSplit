package local.ocrbridge.ui

import android.content.ClipData
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch
import local.ocrbridge.AppViewModel
import local.ocrbridge.R

@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val models = listOf(
        Triple("tiny", R.string.model_tiny, R.string.model_tiny_hint),
        Triple("small", R.string.model_small, R.string.model_small_hint),
        Triple("medium", R.string.model_medium, R.string.model_medium_hint),
    )
    val empty = stringResource(R.string.export_empty)
    val shareTitle = stringResource(R.string.export_title)
    Screen {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(start = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = Ink)
                }
                Text(stringResource(R.string.settings), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink)
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding()
                    .padding(horizontal = Gutter, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Section(stringResource(R.string.settings_language)) {
                    val languages = listOf(
                        "system" to stringResource(R.string.ui_language_system),
                        "zh" to stringResource(R.string.ui_language_zh),
                        "en" to stringResource(R.string.ui_language_en),
                    )
                    languages.forEachIndexed { index, (tag, name) ->
                        if (index > 0) HorizontalDivider(color = Line)
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.chooseUiLanguage(tag) }
                                .padding(horizontal = CardPadding, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(name, Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                            RadioButton(vm.uiLanguage == tag, { vm.chooseUiLanguage(tag) }, colors = RadioButtonDefaults.colors(selectedColor = Ink))
                        }
                    }
                }
                Section(stringResource(R.string.settings_model)) {
                    models.forEachIndexed { index, (tier, name, hint) ->
                        if (index > 0) HorizontalDivider(color = Line)
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.chooseTier(tier) }.padding(horizontal = CardPadding, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(name), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                                Text(stringResource(hint), fontSize = 13.sp, color = Muted)
                            }
                            RadioButton(vm.tier == tier, { vm.chooseTier(tier) }, colors = RadioButtonDefaults.colors(selectedColor = Ink))
                        }
                    }
                }
                Section(stringResource(R.string.settings_image)) {
                    Column(Modifier.padding(horizontal = CardPadding, vertical = 14.dp)) {
                        Text(stringResource(R.string.flatten_title), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                        Text(stringResource(R.string.flatten_hint), fontSize = 13.sp, color = Muted)
                    }
                }
                Section(stringResource(R.string.settings_debug)) {
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.chooseSaveRuns(!vm.saveRuns) }.padding(horizontal = CardPadding, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.save_runs), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                            Text(stringResource(R.string.save_runs_hint), fontSize = 13.sp, color = Muted)
                        }
                        Switch(vm.saveRuns, vm::chooseSaveRuns)
                    }
                    HorizontalDivider(color = Line)
                    Text(
                        stringResource(R.string.export_runs),
                        Modifier.fillMaxWidth().clickable {
                            scope.launch {
                                val archive = vm.packRuns()
                                if (archive == null) {
                                    Toast.makeText(context, empty, Toast.LENGTH_SHORT).show()
                                    return@launch
                                }
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", archive)
                                val send = Intent(Intent.ACTION_SEND).setType("application/zip")
                                    .putExtra(Intent.EXTRA_STREAM, uri)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                send.clipData = ClipData.newRawUri(archive.name, uri)
                                context.startActivity(Intent.createChooser(send, shareTitle))
                            }
                        }.padding(horizontal = CardPadding, vertical = 16.dp),
                        fontSize = 15.sp, color = Blue,
                    )
                }
                Text(
                    stringResource(R.string.version_note),
                    Modifier.fillMaxWidth(),
                    fontSize = 12.sp, color = Muted, textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.padding(horizontal = CardPadding), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Muted)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface)) { content() }
    }
}
