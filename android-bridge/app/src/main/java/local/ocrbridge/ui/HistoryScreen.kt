package local.ocrbridge.ui

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.ConfigurationCompat
import local.ocrbridge.AppViewModel
import local.ocrbridge.R
import local.ocrbridge.SavedBill
import java.text.SimpleDateFormat
import java.util.Date

/** Past splits by month, newest first; tapping one opens it in the split screen to edit. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(vm: AppViewModel, onBack: () -> Unit, onOpen: (SavedBill) -> Unit) {
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: java.util.Locale.getDefault()
    val monthFormat = remember(locale) { SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "yMMMM"), locale) }
    val dayFormat = remember(locale) { SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "MMMdHHmm"), locale) }
    var deleting by remember { mutableStateOf<SavedBill?>(null) }
    BackHandler(onBack = onBack)

    Screen {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(start = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.back), tint = Ink)
                }
                Text(stringResource(R.string.history_title), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink)
            }
            if (vm.history.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 40.dp).navigationBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.history_empty_title), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                    Text(stringResource(R.string.history_empty_body), fontSize = 13.sp, color = Muted, textAlign = TextAlign.Center)
                }
                return@Column
            }
            val months = vm.history.groupBy { monthFormat.format(Date(it.savedAt)) }
            LazyColumn(
                Modifier.fillMaxSize().navigationBarsPadding(),
                contentPadding = PaddingValues(start = Gutter, end = Gutter, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for ((month, bills) in months) {
                    item(key = "month-$month") {
                        Text(
                            month,
                            Modifier.padding(start = CardPadding, top = 12.dp),
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Muted,
                        )
                    }
                    items(bills, key = { it.id }) { bill ->
                        BillCard(
                            bill,
                            stringResource(R.string.history_meta, dayFormat.format(Date(bill.savedAt)), bill.items.size),
                            Modifier.animateItem().combinedClickable(onClick = { onOpen(bill) }, onLongClick = { deleting = bill }),
                        )
                    }
                }
                item(key = "hint") {
                    Text(
                        stringResource(R.string.history_hint),
                        Modifier.fillMaxWidth().padding(top = 12.dp),
                        fontSize = 12.sp, color = Muted, textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }

    deleting?.let { bill ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.history_delete_title)) },
            text = { Text(stringResource(R.string.history_delete_body)) },
            confirmButton = {
                TextButton(onClick = { vm.deleteHistory(bill.id); deleting = null }) {
                    Text(stringResource(R.string.remove), color = Danger)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BillCard(bill: SavedBill, meta: String, modifier: Modifier) {
    val split = remember(bill) { bill.split }
    val people = bill.people.associateBy { it.id }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface)
            .padding(horizontal = CardPadding, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    bill.store.ifEmpty { stringResource(R.string.unknown_store) },
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(meta, fontSize = 12.sp, color = Muted)
            }
            Text(money(split.totalCents), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (share in split.shares) {
                val person = people[share.person] ?: continue
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(Background).padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        Modifier.size(22.dp).clip(CircleShape).background(personColor(person.id)),
                        contentAlignment = Alignment.Center,
                    ) { Text(person.name.take(1), style = CenteredLetter, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                    Text(money(share.total), fontSize = 13.sp, color = Ink)
                }
            }
        }
    }
}
