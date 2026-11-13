package local.ocrbridge.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.ocrbridge.Adjustment
import local.ocrbridge.AppViewModel
import local.ocrbridge.BillItem
import local.ocrbridge.MAX_PEOPLE
import local.ocrbridge.Person
import local.ocrbridge.R
import local.ocrbridge.owners
import local.ocrbridge.splitBill
import local.ocrbridge.toCents
import java.util.Locale
import kotlin.math.abs

private val DotSize = 16.dp
private val DotStep = 10.dp
private val RowGap = 2.dp
private const val DOTS_PER_ROW = 3
private const val QUIET_ALPHA = 0.45f

/** Dots overlap three to a row and wrap, in a fixed width so every row's name starts at the same x. */
private val DotsWidth: Dp = DotSize + DotStep * (DOTS_PER_ROW - 1)

@Composable
fun SplitScreen(vm: AppViewModel, onLeave: () -> Unit, onPick: (Boolean) -> Unit, onDone: () -> Unit) {
    val split by remember { derivedStateOf { splitBill(vm.items.toList(), vm.people.map { it.id }, vm.adjustment) } }
    val activePerson = vm.people.firstOrNull { it.id == vm.active } ?: vm.people.first()
    val last = vm.people.last()
    var leaving by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Person?>(null) }
    var editingTotals by remember { mutableStateOf(false) }
    BackHandler { leaving = true }

    Screen {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { leaving = true }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = Ink)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        vm.store.ifEmpty { stringResource(R.string.unknown_store) },
                        fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink, maxLines = 1,
                    )
                    Text(
                        listOf(vm.printedAt, stringResource(R.string.item_count, vm.items.size))
                            .filter { it.isNotEmpty() }.joinToString(" · "),
                        fontSize = 13.sp, color = Muted,
                    )
                }
                TextButton(onClick = { onPick(false) }) { Text(stringResource(R.string.view_receipt), color = Ink) }
            }
            TotalCard(split.itemsCents, split.totalCents, vm.printedTotal, onClick = { editingTotals = true })
            Text(
                stringResource(R.string.split_hint, activePerson.name),
                Modifier.padding(start = Gutter + CardPadding, end = Gutter, top = 12.dp, bottom = 8.dp),
                fontSize = 13.sp, color = Muted,
            )
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = Gutter, end = Gutter, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(vm.items, key = { it.id }) { item ->
                    ItemRow(
                        item, vm.people, vm.active,
                        onClick = { vm.toggle(item.id) },
                        onLongClick = { vm.editItem(item.id) },
                        modifier = Modifier.animateItem(),
                    )
                }
                if (split.totalCents != split.itemsCents) {
                    item(key = "adjustment") { AdjustmentCard(vm.adjustment, split.itemsCents, onClick = { editingTotals = true }) }
                }
                item(key = "add") {
                    Button(
                        onClick = vm::addItem,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(48.dp)
                            .border(1.dp, Line, RoundedCornerShape(14.dp)),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Surface, contentColor = Ink),
                    ) {
                        Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                        Text(stringResource(R.string.add_item), Modifier.padding(start = 8.dp), fontSize = 15.sp)
                    }
                }
            }
            PeopleBar(
                vm.people, split.shares.associate { it.person to it.total }, vm.active, last.id,
                canAdd = vm.people.size < MAX_PEOPLE,
                onSelect = { person -> vm.active = person.id },
                onRemove = { person -> if (vm.people.size > 2) removing = person },
                onAdd = vm::addPerson,
                onDone = onDone,
            )
        }
    }

    vm.draft?.let { EditSheet(vm, onPick = { onPick(true) }) }
    if (editingTotals) TotalsSheet(vm, split.itemsCents, onClose = { editingTotals = false })
    removing?.let { person ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.remove_person_title, person.name)) },
            text = { Text(stringResource(R.string.remove_person_body)) },
            confirmButton = {
                TextButton(onClick = { vm.removePerson(person.id); removing = null }) {
                    Text(stringResource(R.string.remove), color = Danger)
                }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (leaving) {
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text(stringResource(R.string.leave_title)) },
            text = { Text(stringResource(R.string.leave_body)) },
            confirmButton = { TextButton(onClick = { leaving = false; onLeave() }) { Text(stringResource(R.string.leave)) } },
            dismissButton = { TextButton(onClick = { leaving = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** The printed total, laid out like an item row so its amount lines up with the prices below. */
@Composable
private fun TotalCard(items: Long, total: Long, printed: Long?, onClick: () -> Unit) {
    val (note, color) = when {
        printed == null -> stringResource(R.string.total_no_total) to Warn
        abs(total - printed) <= 1 && items == total -> stringResource(R.string.total_match) to Muted
        abs(total - printed) <= 1 ->
            stringResource(R.string.total_match_adjusted, money(items), signedMoney(total - items)) to Muted
        else -> stringResource(R.string.total_mismatch, money(total), money(abs(printed - total))) to Warn
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 4.dp).clip(RoundedCornerShape(14.dp))
            .background(Surface).clickable(onClick = onClick).padding(horizontal = CardPadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.total_title), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Ink)
            Text(note, fontSize = 12.sp, color = color)
        }
        Text(money(printed ?: total), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemRow(
    item: BillItem,
    people: List<Person>,
    active: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ids = people.map { it.id }
    val picked = item.pickers.filter { it in ids }.distinct()
    val mine = active in picked
    // The active person's picks are drawn strong; someone else's stay visible but quiet.
    val owner = if (mine) active else picked.firstOrNull()
    val background by animateColorAsState(
        when {
            owner == null -> Surface
            mine -> personSoftColor(owner)
            else -> personSoftColor(owner).copy(alpha = QUIET_ALPHA)
        },
        label = "row",
    )
    val border by animateColorAsState(
        when {
            owner == null -> Surface
            mine -> personColor(owner)
            else -> personColor(owner).copy(alpha = QUIET_ALPHA)
        },
        label = "rowBorder",
    )
    val borderWidth by animateDpAsState(if (mine) 1.5.dp else 1.dp, label = "rowBorderWidth")
    val sharers = owners(item, ids).size.takeIf { picked.isNotEmpty() } ?: 0
    val notes = buildList {
        if (item.note.isNotEmpty()) add(item.note)
        if (sharers > 1) add(stringResource(R.string.shared_by, sharers, money(item.cents / sharers)))
    }
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(background)
            .border(borderWidth, border, RoundedCornerShape(14.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = CardPadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.width(DotsWidth), verticalArrangement = Arrangement.spacedBy(RowGap)) {
            for (line in picked.chunked(DOTS_PER_ROW)) {
                Box(Modifier.height(DotSize)) {
                    line.forEachIndexed { index, id ->
                        Dot(people.first { it.id == id }, quiet = !mine, ring = background, Modifier.offset(x = DotStep * index))
                    }
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(item.name.ifEmpty { "—" }, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Ink)
            if (notes.isNotEmpty()) Text(notes.joinToString(" · "), fontSize = 12.sp, color = Muted)        }
        Text(money(item.cents), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink)
    }
}

/** A person's initial; a ring in the row's color keeps overlapping dots apart. */
@Composable
private fun Dot(person: Person, quiet: Boolean, ring: Color, modifier: Modifier = Modifier) {
    val color = personColor(person.id)
    Box(
        modifier.size(DotSize).clip(CircleShape).background(ring).padding(1.5.dp).clip(CircleShape)
            .background(if (quiet) color.copy(alpha = 0.75f) else color),
        contentAlignment = Alignment.Center,
    ) {
        Text(person.name.take(1), style = CenteredLetter, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

@Composable
private fun AdjustmentCard(adjustment: Adjustment, items: Long, onClick: () -> Unit) {
    val how = if (adjustment.proportional && items > 0) {
        stringResource(R.string.adjustment_proportional) + " · " +
            String.format(Locale.US, "%+.2f%%", adjustment.cents * 100.0 / items)
    } else {
        stringResource(R.string.adjustment_equal)
    }
    Column(
        Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(14.dp)).background(Surface)
            .clickable(onClick = onClick).padding(horizontal = CardPadding, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.adjustment_name), Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Ink)
            Text(signedMoney(adjustment.cents), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        }
        Text(how, fontSize = 12.sp, color = Muted)
    }
}

private fun parseMoney(text: String): Double? =
    text.trim().replace("$", "").replace(" ", "").replace(',', '.').toDoubleOrNull()

private fun plain(cents: Long) = String.format(Locale.US, "%.2f", cents / 100.0)

/** Edits the printed total and the whole-bill adjustment; a new total sets the adjustment to the gap. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TotalsSheet(vm: AppViewModel, items: Long, onClose: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var total by remember { mutableStateOf(vm.printedTotal?.let(::plain).orEmpty()) }
    var adjustment by remember { mutableStateOf(plain(vm.adjustment.cents)) }
    var proportional by remember { mutableStateOf(vm.adjustment.proportional) }
    var invalid by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheet, containerColor = Surface) {
        Column(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.edit_totals), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text(stringResource(R.string.items_sum, money(items)), fontSize = 13.sp, color = Muted)
            OutlinedTextField(
                total,
                { text ->
                    total = text
                    invalid = false
                    parseMoney(text)?.let { adjustment = plain(toCents(it) - items) }
                },
                Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.total_title)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            OutlinedTextField(
                adjustment, { adjustment = it; invalid = false }, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.adjustment_field)) }, singleLine = true, isError = invalid,
                supportingText = {
                    Text(stringResource(if (invalid) R.string.price_invalid else R.string.adjustment_field_hint))
                },
                // The decimal pad has no minus sign on many keyboards, and a discount is negative.
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.adjustment_by_percent), fontSize = 15.sp, color = Ink)
                    Text(stringResource(R.string.adjustment_by_percent_hint), fontSize = 12.sp, color = Muted)
                }
                Switch(proportional, { proportional = it })
            }
            Button(
                onClick = {
                    val printed = if (total.isBlank()) null else parseMoney(total)
                    val amount = if (adjustment.isBlank()) 0.0 else parseMoney(adjustment)
                    if ((total.isNotBlank() && printed == null) || amount == null) {
                        invalid = true
                    } else {
                        vm.setTotals(printed?.let(::toCents), Adjustment(toCents(amount), proportional))
                        onClose()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(12.dp),
            ) { Text(stringResource(R.string.save), fontWeight = FontWeight.SemiBold) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PeopleBar(
    people: List<Person>,
    totals: Map<Int, Long>,
    active: Int,
    lastId: Int,
    canAdd: Boolean,
    onSelect: (Person) -> Unit,
    onRemove: (Person) -> Unit,
    onAdd: () -> Unit,
    onDone: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(Surface).navigationBarsPadding()) {
        HorizontalDivider(color = Line)
        LazyRow(
            Modifier.padding(top = 12.dp),
            contentPadding = PaddingValues(horizontal = Gutter),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(people, key = { it.id }) { person ->
                val on = person.id == active
                val color = personColor(person.id)
                val background by animateColorAsState(
                    if (on) personSoftColor(person.id) else personSoftColor(person.id).copy(alpha = QUIET_ALPHA),
                    label = "chip",
                )
                val border by animateColorAsState(if (on) color else color.copy(alpha = QUIET_ALPHA), label = "chipBorder")
                val borderWidth by animateDpAsState(if (on) 2.dp else 1.dp, label = "chipBorderWidth")
                Row(
                    Modifier.animateItem().height(60.dp).clip(RoundedCornerShape(14.dp)).background(background)
                        .border(borderWidth, border, RoundedCornerShape(14.dp))
                        .combinedClickable(onClick = { onSelect(person) }, onLongClick = { onRemove(person) })
                        .padding(start = 10.dp, end = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        Modifier.size(28.dp).clip(CircleShape).background(if (on) color else color.copy(alpha = 0.75f)),
                        contentAlignment = Alignment.Center,
                    ) { Text(person.name.take(1), style = CenteredLetter, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                    Column {
                        Text(
                            if (person.id == lastId) stringResource(R.string.takes_rest, person.name) else person.name,
                            fontSize = 12.sp, color = Muted,
                        )
                        AnimatedContent(
                            money(totals[person.id] ?: 0),
                            transitionSpec = {
                                (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut())
                            },
                            label = "amount",
                        ) { amount -> Text(amount, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ink) }
                    }
                }
            }
            if (canAdd) {
                item(key = "add") {
                    IconButton(
                        onClick = onAdd,
                        modifier = Modifier.animateItem().size(width = 56.dp, height = 60.dp)
                            .border(1.dp, Color(0xFFB8B4AB), RoundedCornerShape(14.dp)),
                    ) { Icon(Icons.Filled.Add, stringResource(R.string.add_person), tint = Ink) }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, top = 10.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.people_hint), Modifier.weight(1f), fontSize = 12.sp, color = Muted)
            Button(onClick = onDone, Modifier.height(46.dp), shape = RoundedCornerShape(12.dp)) {
                Text(stringResource(R.string.done), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditSheet(vm: AppViewModel, onPick: () -> Unit) {
    val draft = vm.draft ?: return
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var invalid by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = vm::closeDraft, sheetState = sheet, containerColor = Surface) {
        Column(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                stringResource(if (draft.itemId == null) R.string.add_item else R.string.edit_item),
                fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink,
            )
            OutlinedTextField(
                draft.name, { vm.updateDraft(name = it) }, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.item_name)) }, singleLine = true,
            )
            OutlinedTextField(
                draft.price, { invalid = false; vm.updateDraft(price = it) }, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.item_price)) }, singleLine = true, isError = invalid,
                supportingText = if (invalid) ({ Text(stringResource(R.string.price_invalid)) }) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            Button(
                onClick = onPick,
                modifier = Modifier.fillMaxWidth().height(50.dp).border(1.dp, Line, RoundedCornerShape(12.dp)),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Surface, contentColor = Ink),
            ) { Text(stringResource(R.string.pick_from_photo), fontSize = 15.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (draft.itemId != null) {
                    Button(
                        onClick = vm::deleteDraftItem,
                        modifier = Modifier.weight(1f).height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = DangerBackground, contentColor = Danger),
                    ) { Text(stringResource(R.string.delete_item), fontWeight = FontWeight.SemiBold) }
                }
                Button(
                    onClick = { invalid = !vm.saveDraft() },
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                ) { Text(stringResource(R.string.save), fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}
