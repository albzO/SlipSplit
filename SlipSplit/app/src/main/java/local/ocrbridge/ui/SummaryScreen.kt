package local.ocrbridge.ui

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.ocrbridge.AppViewModel
import local.ocrbridge.R
import local.ocrbridge.ShareLine
import local.ocrbridge.splitBill

@Composable
fun SummaryScreen(vm: AppViewModel, onBack: () -> Unit, onFinish: () -> Unit) {
    val split = remember { splitBill(vm.items.toList(), vm.people.map { it.id }, vm.adjustment) }
    val people = vm.people.associateBy { it.id }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val adjustmentLabel = stringResource(R.string.adjustment_line)
    val fraction = stringResource(R.string.share_fraction)
    val personTotal = stringResource(R.string.person_total)
    fun lineName(line: ShareLine) =
        if (line.sharedBy > 1) String.format(fraction, line.item.name, line.sharedBy) else line.item.name

    Screen {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(start = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = Ink)
                }
                Text(stringResource(R.string.summary_title), Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink)
                val counts = stringResource(R.string.summary_counts, vm.people.size, vm.items.size)
                val footer = stringResource(R.string.share_footer)
                val shareTitle = stringResource(R.string.share_title)
                IconButton(onClick = {
                    val blocks = split.shares.mapNotNull { share ->
                        val person = people[share.person] ?: return@mapNotNull null
                        val lines = share.lines.map { ShareImageLine(lineName(it), money(it.cents)) } +
                            listOfNotNull(
                                ShareImageLine(adjustmentLabel, signedMoney(share.adjustment), muted = true)
                                    .takeIf { share.adjustment != 0L },
                            )
                        ShareImagePerson(person.name, personColor(person.id).toArgb(), money(share.total), lines)
                    }
                    val title = vm.store.ifEmpty { "SlipSplit" }
                    val subtitle = listOf(vm.printedAt, counts).filter { it.isNotEmpty() }.joinToString(" · ")
                    scope.launch {
                        val file = withContext(Dispatchers.Default) {
                            val image = renderSplitImage(title, subtitle, money(split.totalCents), blocks, footer)
                            val folder = File(context.cacheDir, "exports").apply { mkdirs() }
                            File(folder, "split.png").also { target ->
                                target.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                image.recycle()
                            }
                        }
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                        val send = Intent(Intent.ACTION_SEND).setType("image/png")
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        send.clipData = ClipData.newRawUri(file.name, uri)
                        context.startActivity(Intent.createChooser(send, shareTitle))
                    }
                }) { Icon(Icons.Filled.Share, stringResource(R.string.share_image), tint = Ink) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = Gutter + CardPadding, vertical = 10.dp), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(listOf(vm.store, vm.printedAt).filter { it.isNotEmpty() }.joinToString(" · "), fontSize = 13.sp, color = Muted)
                    Text(stringResource(R.string.summary_counts, vm.people.size, vm.items.size), fontSize = 13.sp, color = Muted)
                }
                Text(money(split.totalCents), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Ink)
            }
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(start = Gutter, end = Gutter, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(split.shares, key = { it.person }) { share ->
                    val person = people[share.person] ?: return@items
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface)
                            .padding(horizontal = CardPadding, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(32.dp).clip(CircleShape).background(personColor(person.id)),
                                contentAlignment = Alignment.Center,
                            ) { Text(person.name.take(1), style = CenteredLetter, color = Color.White, fontWeight = FontWeight.Bold) }
                            Text(person.name, Modifier.weight(1f).padding(start = 10.dp), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                            Text(money(share.total), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
                        }
                        HorizontalDivider(color = Line)
                        for (line in share.lines) {
                            Row {
                                Text(lineName(line), Modifier.weight(1f), fontSize = 14.sp, color = Ink)
                                Text(money(line.cents), fontSize = 14.sp, color = Ink)
                            }
                        }
                        if (share.adjustment != 0L) {
                            Row {
                                Text(adjustmentLabel, Modifier.weight(1f), fontSize = 14.sp, color = Muted)
                                Text(signedMoney(share.adjustment), fontSize = 14.sp, color = Muted)
                            }
                        }
                    }
                }
            }
            Column(
                Modifier.fillMaxWidth().background(Surface).navigationBarsPadding()
                    .padding(start = Gutter, end = Gutter, top = 12.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SecondaryButton(stringResource(R.string.back_to_edit), onBack, Modifier.weight(1f))
                    val copied = stringResource(R.string.copied)
                    SecondaryButton(stringResource(R.string.copy_text), {
                        val text = buildString {
                            append(listOf(vm.store, vm.printedAt, money(split.totalCents)).filter { it.isNotEmpty() }.joinToString(" "))
                            for (share in split.shares) {
                                val person = people[share.person] ?: continue
                                append("\n").append(String.format(personTotal, person.name, money(share.total)))
                                for (line in share.lines) append("\n  ").append(lineName(line)).append(" ").append(money(line.cents))
                                if (share.adjustment != 0L) append("\n  ").append(adjustmentLabel).append(" ").append(signedMoney(share.adjustment))
                            }
                        }
                        clipboard.setText(AnnotatedString(text))
                        Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                    }, Modifier.weight(1f))
                }
                Button(
                    onClick = onFinish,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                ) { Text(stringResource(R.string.finish), fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp).border(1.dp, Line, RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Surface, contentColor = Ink),
    ) { Text(text, fontSize = 15.sp) }
}
