package com.noop.ui.fork.lift

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.noop.R
import com.noop.data.LiftProgramItemRow
import com.noop.data.LiftProgramRow
import com.noop.data.LiftSessionRow
import com.noop.data.LiftSetEntity
import com.noop.ui.AppViewModel
import com.noop.ui.Metrics
import com.noop.ui.NoopButton
import com.noop.ui.NoopButtonKind
import com.noop.ui.NoopCard
import com.noop.ui.NoopType
import com.noop.ui.Palette
import com.noop.ui.ScreenScaffold
import com.noop.ui.SectionHeader
import com.noop.ui.SettingsToggleRow
import com.noop.ui.StepperField
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/** Which part of the Lift Log is showing. One route, so upstream's nav graph gains a single entry. */
private sealed interface Pane {
    data object Hub : Pane
    data object Session : Pane
    data class Program(val id: String?) : Pane
    data class Detail(val sessionId: String) : Pane
}

/** Fork-only entry on the Workouts screen. */
@Composable
internal fun LiftLogEntry(onOpen: () -> Unit) {
    NoopButton(
        text = stringResource(R.string.fork_lift_entry),
        leadingIcon = Icons.Filled.FitnessCenter,
        kind = NoopButtonKind.Secondary,
        fullWidth = true,
        onClick = onOpen,
    )
}

@Composable
internal fun LiftLogScreen(vm: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember {
        LiftLogController(
            context.applicationContext, scope,
            saveWorkout = { vm.saveManualWorkout(it) },
            deleteWorkout = { vm.deleteWorkout(it) },
        )
    }
    var pane by remember { mutableStateOf<Pane>(Pane.Hub) }
    LaunchedEffect(Unit) { controller.refreshHub() }
    BackHandler { if (pane == Pane.Hub) onBack() else pane = Pane.Hub }

    when (val p = pane) {
        Pane.Hub -> HubPane(controller, onSession = { pane = Pane.Session }, onPane = { pane = it })
        Pane.Session -> SessionPane(controller, onDone = { pane = Pane.Hub })
        is Pane.Program -> ProgramPane(controller, p.id, onDone = { pane = Pane.Hub })
        is Pane.Detail -> DetailPane(controller, p.sessionId, onDone = { pane = Pane.Hub })
    }
}

// MARK: - Hub

@Composable
private fun HubPane(c: LiftLogController, onSession: () -> Unit, onPane: (Pane) -> Unit) {
    val hub = c.hub
    val unit = c.unit
    ScreenScaffold(title = stringResource(R.string.fork_lift_title), subtitle = stringResource(R.string.fork_lift_subtitle)) {
        if (hub.open != null) {
            NoopButton(
                text = stringResource(R.string.fork_lift_resume), leadingIcon = Icons.Filled.PlayArrow,
                fullWidth = true, onClick = { c.resume(onSession) },
            )
        } else {
            NoopButton(
                text = stringResource(R.string.fork_lift_start_empty), leadingIcon = Icons.Filled.PlayArrow,
                fullWidth = true, onClick = { c.start(null, onSession) },
            )
        }

        NoopCard {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                SectionHeader(title = stringResource(R.string.fork_lift_programs))
                hub.programs.forEach { program ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(program.name, style = NoopType.body, color = Palette.textPrimary, modifier = Modifier.weight(1f))
                        if (hub.open == null) {
                            TextButton(onClick = { c.start(program, onSession) }) {
                                Text(stringResource(R.string.fork_lift_start_program), color = Palette.accent)
                            }
                        }
                        TextButton(onClick = { onPane(Pane.Program(program.id)) }) {
                            Text(stringResource(R.string.fork_lift_edit), color = Palette.textSecondary)
                        }
                    }
                }
                NoopButton(
                    text = stringResource(R.string.fork_lift_new_program), leadingIcon = Icons.Filled.Add,
                    kind = NoopButtonKind.Tertiary, onClick = { onPane(Pane.Program(null)) },
                )
            }
        }

        NoopCard {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                SectionHeader(title = stringResource(R.string.fork_lift_recent))
                if (hub.loaded && hub.recent.isEmpty()) {
                    Text(stringResource(R.string.fork_lift_none_recent), style = NoopType.subhead, color = Palette.textSecondary)
                }
                hub.recent.forEach { card ->
                    Column(
                        Modifier.fillMaxWidth().clickable { onPane(Pane.Detail(card.session.id)) }.padding(vertical = Metrics.space4),
                    ) {
                        Text(
                            sessionTitle(card.session),
                            style = NoopType.body, color = Palette.textPrimary,
                        )
                        Text(summaryLine(card.summary, unit), style = NoopType.footnote, color = Palette.textSecondary)
                    }
                }
            }
        }

        NoopCard {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space4)) {
                SectionHeader(title = stringResource(R.string.fork_lift_bests))
                if (hub.loaded && hub.bests.isEmpty()) {
                    Text(stringResource(R.string.fork_lift_none_bests), style = NoopType.subhead, color = Palette.textSecondary)
                }
                hub.bests.forEach { b ->
                    Row {
                        Text(b.exercise, style = NoopType.body, color = Palette.textPrimary, modifier = Modifier.weight(1f))
                        Text(
                            stringResource(
                                R.string.fork_lift_best_line, unit.format(b.e1rmKg), unit.name.lowercase(),
                                unit.format(b.weightKg), b.reps ?: 0,
                            ),
                            style = NoopType.bodyNumber, color = Palette.textSecondary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun sessionTitle(session: LiftSessionRow): String {
    val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(session.startTs * 1000))
    return listOfNotNull(date, session.programName).joinToString(" · ")
}

@Composable
private fun summaryLine(s: LiftLogic.SessionSummary, unit: WeightUnit): String = listOfNotNull(
    stringResource(R.string.fork_lift_summary, s.workingSets, s.exercises),
    s.volumeKg?.let { stringResource(R.string.fork_lift_volume, unit.format(it), unit.name.lowercase()) },
    s.durationSec?.let { stringResource(R.string.fork_lift_duration, it / 60) },
).joinToString(" · ")

// MARK: - Active session

@Composable
private fun SessionPane(c: LiftLogController, onDone: () -> Unit) {
    val a = c.active ?: return
    val unit = c.unit
    var now by remember { mutableLongStateOf(c.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = c.now()
            delay(1000)
        }
    }
    var exerciseText by remember(a.session.id) { mutableStateOf(a.exercise) }
    LaunchedEffect(a.exercise) { exerciseText = a.exercise }

    // The entry fields start from the suggestion, else last time's top set, else an empty bar.
    val seedKg = a.suggestion?.weightKg ?: a.lastTime.lastOrNull()?.weightKg ?: unit.toKg(unit.barWeight)
    val seedReps = a.suggestion?.reps ?: a.lastTime.lastOrNull()?.reps ?: 8
    var weight by remember(a.exercise) { mutableStateOf(unit.roundDisplay(unit.fromKg(seedKg))) }
    var reps by remember(a.exercise) { mutableStateOf(seedReps) }
    var rpe by remember(a.exercise) { mutableStateOf<Double?>(null) }
    var warmup by remember(a.exercise) { mutableStateOf(false) }
    var finishing by remember { mutableStateOf(false) }
    var discarding by remember { mutableStateOf(false) }

    val restFrom = a.sets.lastOrNull()?.endTs
    ScreenScaffold(
        title = a.session.programName ?: stringResource(R.string.fork_lift_session),
        subtitle = listOfNotNull(
            stringResource(R.string.fork_lift_elapsed, LiftLogic.clock(now - a.session.startTs)),
            restFrom?.let { stringResource(R.string.fork_lift_rest, LiftLogic.clock(now - it)) },
        ).joinToString(" · "),
    ) {
        NoopCard {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                LiftTextField(
                    value = exerciseText,
                    label = stringResource(R.string.fork_lift_exercise),
                    onValueChange = { exerciseText = it },
                    onDone = { c.selectExercise(exerciseText) },
                )
                val chips = (a.programItems.map { it.exercise } + a.exercises.map { it.name })
                    .distinct()
                    .filter { exerciseText.isBlank() || it.contains(exerciseText.trim(), ignoreCase = true) }
                    .take(12)
                if (chips.isNotEmpty()) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(Metrics.space8),
                    ) {
                        chips.forEach { name ->
                            NoopButton(
                                text = name,
                                kind = if (name == a.exercise) NoopButtonKind.Primary else NoopButtonKind.Secondary,
                                onClick = { exerciseText = name; c.selectExercise(name) },
                            )
                        }
                    }
                }
                if (a.lastTime.isNotEmpty()) {
                    Text(
                        stringResource(
                            R.string.fork_lift_last_time,
                            LiftLogic.workingSets(a.lastTime).joinToString(", ") { "${unit.format(it.weightKg)}×${it.reps ?: "-"}" },
                        ),
                        style = NoopType.footnote, color = Palette.textSecondary,
                    )
                }
                a.suggestion?.let { s -> SuggestionLine(s, a, unit) }

                Text(stringResource(R.string.fork_lift_weight), style = NoopType.footnote, color = Palette.textSecondary)
                StepperField(
                    value = unit.format(unit.toKg(weight)), accessibility = stringResource(R.string.fork_lift_weight),
                    unit = unit.name.lowercase(),
                    onMinus = { weight = (weight - unit.step).coerceAtLeast(0.0) },
                    onPlus = { weight += unit.step },
                )
                Text(stringResource(R.string.fork_lift_reps), style = NoopType.footnote, color = Palette.textSecondary)
                StepperField(
                    value = reps.toString(), accessibility = stringResource(R.string.fork_lift_reps),
                    onMinus = { reps = (reps - 1).coerceAtLeast(0) }, onPlus = { reps += 1 },
                )
                Text(stringResource(R.string.fork_lift_rpe), style = NoopType.footnote, color = Palette.textSecondary)
                StepperField(
                    value = rpe?.let { "%.1f".format(it).removeSuffix(".0") } ?: stringResource(R.string.fork_lift_unset),
                    accessibility = stringResource(R.string.fork_lift_rpe),
                    onMinus = { rpe = rpe?.let { r -> (r - 0.5).takeIf { it >= 5.0 } } },
                    onPlus = { rpe = ((rpe ?: 6.5) + 0.5).coerceAtMost(10.0) },
                )
                SettingsToggleRow(
                    title = stringResource(R.string.fork_lift_warmup),
                    detail = stringResource(R.string.fork_lift_warmup_detail),
                    checked = warmup, onCheckedChange = { warmup = it },
                )
                NoopButton(
                    text = stringResource(R.string.fork_lift_log_set), fullWidth = true,
                    enabled = exerciseText.isNotBlank(),
                    onClick = { c.logSet(exerciseText, unit.toKg(weight), reps, rpe, warmup) },
                )
                PlatesLine(weight, unit)
            }
        }

        if (a.sets.isNotEmpty()) {
            NoopCard {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space4)) {
                    SectionHeader(title = stringResource(R.string.fork_lift_sets_today))
                    a.sets.asReversed().forEach { s ->
                        SetRow(s, unit, isNewBest = s.id == a.newBestSetId, onDelete = { c.deleteSet(s) })
                    }
                }
            }
        }

        NoopButton(
            text = stringResource(R.string.fork_lift_finish), fullWidth = true,
            enabled = a.sets.isNotEmpty(), onClick = { finishing = true },
        )
        NoopButton(
            text = stringResource(R.string.fork_lift_discard), kind = NoopButtonKind.Destructive,
            fullWidth = true, onClick = { discarding = true },
        )
    }

    if (finishing) FinishDialog(onCancel = { finishing = false }) { srpe, note ->
        finishing = false
        c.finish(srpe, note, onDone)
    }
    if (discarding) ConfirmDialog(
        title = stringResource(R.string.fork_lift_discard),
        body = stringResource(R.string.fork_lift_discard_body),
        confirm = stringResource(R.string.fork_lift_discard),
        onCancel = { discarding = false },
        onConfirm = { discarding = false; c.discard(onDone) },
    )
}

@Composable
private fun SuggestionLine(s: LiftLogic.Suggestion, a: LiftLogController.Active, unit: WeightUnit) {
    val text = when (s.reason) {
        LiftLogic.Reason.ADD_WEIGHT -> stringResource(R.string.fork_lift_suggest_weight, unit.format(s.weightKg), s.reps ?: 0)
        LiftLogic.Reason.ADD_REPS -> stringResource(R.string.fork_lift_suggest_reps, unit.format(s.weightKg), s.reps ?: 0)
        LiftLogic.Reason.NO_HISTORY -> {
            val item = a.programItems.firstOrNull { it.exercise == a.exercise } ?: return
            val reps = listOfNotNull(item.targetRepsLow, item.targetRepsHigh).distinct().joinToString("-").ifEmpty { "-" }
            stringResource(R.string.fork_lift_suggest_target, unit.format(item.targetWeightKg), reps)
        }
        LiftLogic.Reason.REPEAT -> return
    }
    Text(text, style = NoopType.footnote, color = Palette.accent)
}

@Composable
private fun PlatesLine(weightDisplay: Double, unit: WeightUnit) {
    val load = LiftLogic.plates(weightDisplay, unit)
    val u = unit.name.lowercase()
    val text = when {
        load.belowBar -> stringResource(R.string.fork_lift_plates_below, unit.format(unit.toKg(unit.barWeight)), u)
        load.perSide.isEmpty() && load.remainder == 0.0 ->
            stringResource(R.string.fork_lift_plates_none, unit.format(unit.toKg(unit.barWeight)), u)
        else -> stringResource(R.string.fork_lift_plates) + ": " +
            load.perSide.joinToString(" + ") { unit.format(unit.toKg(it)) }
    }
    Text(text, style = NoopType.footnote, color = Palette.textSecondary)
    if (!load.belowBar && load.remainder > 0) {
        Text(
            stringResource(R.string.fork_lift_plates_left, unit.format(unit.toKg(load.remainder)), u),
            style = NoopType.footnote, color = Palette.statusWarning,
        )
    }
}

@Composable
private fun SetRow(s: LiftSetEntity, unit: WeightUnit, isNewBest: Boolean, onDelete: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(
                    R.string.fork_lift_set_line, s.exercise, unit.format(s.weightKg), unit.name.lowercase(),
                    s.reps?.toString() ?: stringResource(R.string.fork_lift_unset),
                ) + (s.rpe?.let { stringResource(R.string.fork_lift_set_rpe, "%.1f".format(it).removeSuffix(".0")) } ?: ""),
                style = NoopType.body, color = if (s.isWarmup) Palette.textTertiary else Palette.textPrimary,
            )
            val notes = listOfNotNull(
                if (s.isWarmup) stringResource(R.string.fork_lift_warmup_short) else null,
                if (isNewBest) stringResource(R.string.fork_lift_new_best) else null,
                s.restSec?.let { stringResource(R.string.fork_lift_rest, LiftLogic.clock(it.toLong())) },
            )
            if (notes.isNotEmpty()) {
                Text(
                    notes.joinToString(" · "), style = NoopType.caption,
                    color = if (isNewBest) Palette.statusPositive else Palette.textSecondary,
                )
            }
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.fork_lift_delete), tint = Palette.textTertiary)
            }
        }
    }
}

@Composable
private fun FinishDialog(onCancel: () -> Unit, onSave: (Double?, String?) -> Unit) {
    var srpe by rememberSaveable { mutableStateOf<Int?>(null) }
    var note by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Palette.surfaceOverlay,
        title = { Text(stringResource(R.string.fork_lift_finish), style = NoopType.title2, color = Palette.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                Text(stringResource(R.string.fork_lift_finish_body), style = NoopType.subhead, color = Palette.textSecondary)
                Text(stringResource(R.string.fork_lift_finish_rpe), style = NoopType.footnote, color = Palette.textSecondary)
                StepperField(
                    value = srpe?.toString() ?: stringResource(R.string.fork_lift_unset),
                    accessibility = stringResource(R.string.fork_lift_finish_rpe),
                    onMinus = { srpe = srpe?.let { (it - 1).takeIf { v -> v >= 1 } } },
                    onPlus = { srpe = ((srpe ?: 6) + 1).coerceAtMost(10) },
                )
                LiftTextField(note, stringResource(R.string.fork_lift_note), onValueChange = { note = it })
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(srpe?.toDouble(), note) }) {
                Text(stringResource(R.string.fork_lift_save), color = Palette.accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.fork_lift_cancel), color = Palette.textSecondary) }
        },
    )
}

@Composable
private fun ConfirmDialog(title: String, body: String, confirm: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Palette.surfaceOverlay,
        title = { Text(title, style = NoopType.title2, color = Palette.textPrimary) },
        text = { Text(body, style = NoopType.subhead, color = Palette.textSecondary) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = Palette.statusCritical) } },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.fork_lift_cancel), color = Palette.textSecondary) }
        },
    )
}

// MARK: - Session detail

@Composable
private fun DetailPane(c: LiftLogController, sessionId: String, onDone: () -> Unit) {
    var loaded by remember { mutableStateOf<Pair<LiftSessionRow, List<LiftSetEntity>>?>(null) }
    var deleting by remember { mutableStateOf(false) }
    LaunchedEffect(sessionId) { loaded = c.sessionDetail(sessionId) }
    val (session, sets) = loaded ?: return
    val unit = c.unit
    ScreenScaffold(title = sessionTitle(session), subtitle = summaryLine(LiftLogic.summarize(session, sets), unit)) {
        NoopCard {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space4)) {
                sets.forEach { SetRow(it, unit, isNewBest = false, onDelete = null) }
                session.note?.let { Text(it, style = NoopType.subhead, color = Palette.textSecondary) }
            }
        }
        NoopButton(
            text = stringResource(R.string.fork_lift_delete), kind = NoopButtonKind.Destructive,
            fullWidth = true, onClick = { deleting = true },
        )
    }
    if (deleting) ConfirmDialog(
        title = stringResource(R.string.fork_lift_delete_title),
        body = stringResource(R.string.fork_lift_delete_body),
        confirm = stringResource(R.string.fork_lift_delete),
        onCancel = { deleting = false },
        onConfirm = { deleting = false; c.deleteFinished(session, sets, onDone) },
    )
}

// MARK: - Program editor

private class LineDraft(item: LiftProgramItemRow) {
    var exercise by mutableStateOf(item.exercise)
    var sets by mutableStateOf(item.targetSets ?: 3)
    var low by mutableStateOf(item.targetRepsLow ?: 8)
    var high by mutableStateOf(item.targetRepsHigh ?: 10)
    var weightKg by mutableStateOf(item.targetWeightKg)
    var rest by mutableStateOf(item.restSec ?: 120)
}

@Composable
private fun ProgramPane(c: LiftLogController, programId: String?, onDone: () -> Unit) {
    var program by remember { mutableStateOf<LiftProgramRow?>(null) }
    var name by remember { mutableStateOf("") }
    val lines = remember { mutableStateListOf<LineDraft>() }
    val unit = c.unit
    LaunchedEffect(programId) {
        val (p, items) = c.programForEdit(programId)
        program = p
        name = p.name
        lines.clear()
        lines.addAll(items.map(::LineDraft))
    }
    val p = program ?: return
    fun blank() = LiftProgramItemRow("", p.deviceId, p.id, 0, "", 3, 8, 10, null, null, 120, null)

    ScreenScaffold(title = name.ifBlank { stringResource(R.string.fork_lift_new_program) }) {
        LiftTextField(name, stringResource(R.string.fork_lift_program_name), onValueChange = { name = it })
        lines.forEachIndexed { index, line ->
            NoopCard {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                    LiftTextField(line.exercise, stringResource(R.string.fork_lift_exercise), onValueChange = { line.exercise = it })
                    NumberRow(stringResource(R.string.fork_lift_sets), line.sets.toString(), { line.sets = (line.sets - 1).coerceAtLeast(1) }, { line.sets += 1 })
                    NumberRow(stringResource(R.string.fork_lift_reps_low), line.low.toString(), { line.low = (line.low - 1).coerceAtLeast(1) }, {
                        line.low += 1
                        if (line.high < line.low) line.high = line.low
                    })
                    NumberRow(stringResource(R.string.fork_lift_reps_high), line.high.toString(), { line.high = (line.high - 1).coerceAtLeast(line.low) }, { line.high += 1 })
                    NumberRow(
                        stringResource(R.string.fork_lift_target_weight),
                        line.weightKg?.let { "${unit.format(it)} ${unit.name.lowercase()}" } ?: stringResource(R.string.fork_lift_unset),
                        {
                            val cur = line.weightKg?.let(unit::fromKg) ?: return@NumberRow
                            line.weightKg = (cur - unit.step).takeIf { it > 0 }?.let(unit::toKg)
                        },
                        { line.weightKg = unit.toKg((line.weightKg?.let(unit::fromKg) ?: unit.barWeight - unit.step) + unit.step) },
                    )
                    NumberRow(stringResource(R.string.fork_lift_rest_sec), line.rest.toString(), { line.rest = (line.rest - 15).coerceAtLeast(0) }, { line.rest += 15 })
                    NoopButton(
                        text = stringResource(R.string.fork_lift_remove), kind = NoopButtonKind.Tertiary,
                        onClick = { lines.removeAt(index) },
                    )
                }
            }
        }
        NoopButton(
            text = stringResource(R.string.fork_lift_add_line), leadingIcon = Icons.Filled.Add,
            kind = NoopButtonKind.Secondary, fullWidth = true, onClick = { lines.add(LineDraft(blank())) },
        )
        NoopButton(
            text = stringResource(R.string.fork_lift_save), fullWidth = true, enabled = name.isNotBlank(),
            onClick = {
                val items = lines.map { l ->
                    blank().copy(
                        exercise = l.exercise, targetSets = l.sets, targetRepsLow = l.low, targetRepsHigh = l.high,
                        targetWeightKg = l.weightKg, restSec = l.rest,
                    )
                }
                c.saveProgram(p.copy(name = name), items, onDone)
            },
        )
        if (programId != null) {
            NoopButton(
                text = stringResource(R.string.fork_lift_archive), kind = NoopButtonKind.Destructive,
                fullWidth = true, onClick = { c.archiveProgram(programId, onDone) },
            )
        }
    }
}

@Composable
private fun NumberRow(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = NoopType.subhead, color = Palette.textSecondary, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(Metrics.space8))
        StepperField(value = value, accessibility = label, onMinus = onMinus, onPlus = onPlus)
    }
}

@Composable
private fun LiftTextField(
    value: String,
    label: String,
    onValueChange: (String) -> Unit,
    onDone: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onDone?.invoke() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Palette.accent,
            unfocusedBorderColor = Palette.hairline,
            focusedContainerColor = Palette.surfaceInset,
            unfocusedContainerColor = Palette.surfaceInset,
            focusedTextColor = Palette.textPrimary,
            unfocusedTextColor = Palette.textPrimary,
            focusedLabelColor = Palette.accent,
            unfocusedLabelColor = Palette.textSecondary,
            cursorColor = Palette.accent,
        ),
    )
}
