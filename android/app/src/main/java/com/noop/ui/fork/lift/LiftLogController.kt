package com.noop.ui.fork.lift

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.noop.data.LiftExerciseRow
import com.noop.data.LiftProgramItemRow
import com.noop.data.LiftProgramRow
import com.noop.data.LiftSessionRow
import com.noop.data.LiftSetEntity
import com.noop.data.WhoopDatabase
import com.noop.data.WorkoutRow
import com.noop.ui.UnitPrefs
import com.noop.ui.UnitSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Screen state for the Lift Log. All database work runs on IO; state is published on the caller's
 * (main) dispatcher. The database is the source of truth: an in-progress session is a `liftSession`
 * row with no end, so leaving the screen or the app never loses a logged set.
 *
 * Workout writes go through the app's own manual-workout path ([saveWorkout] / [deleteWorkout]) so the
 * paired `workout` row is rescored from the strap and mirrored to Health Connect exactly like any
 * manual workout.
 */
internal class LiftLogController(
    context: Context,
    private val scope: CoroutineScope,
    private val saveWorkout: (WorkoutRow) -> Unit,
    private val deleteWorkout: (WorkoutRow) -> Unit,
    private val store: LiftStore = LiftStore(RoomLiftSql(WhoopDatabase.get(context))),
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    val unit: WeightUnit = if (UnitPrefs.system(context) == UnitSystem.IMPERIAL) WeightUnit.LB else WeightUnit.KG
    private val device = LiftLog.DEVICE_ID

    data class SessionCard(val session: LiftSessionRow, val summary: LiftLogic.SessionSummary)

    data class Hub(
        val open: LiftSessionRow? = null,
        val programs: List<LiftProgramRow> = emptyList(),
        val recent: List<SessionCard> = emptyList(),
        val bests: List<LiftLogic.PersonalBest> = emptyList(),
        val loaded: Boolean = false,
    )

    data class Active(
        val session: LiftSessionRow,
        val sets: List<LiftSetEntity>,
        val programItems: List<LiftProgramItemRow>,
        val exercises: List<LiftExerciseRow>,
        val exercise: String,
        val lastTime: List<LiftSetEntity>,
        val suggestion: LiftLogic.Suggestion?,
        val newBestSetId: String? = null,
    )

    var hub by mutableStateOf(Hub())
        private set
    var active by mutableStateOf<Active?>(null)
        private set

    private fun launch(block: suspend () -> Unit) {
        scope.launch { runCatching { block() } }
    }

    fun refreshHub() = launch {
        hub = withContext(Dispatchers.IO) {
            Hub(
                open = store.openSession(device),
                programs = store.programs(device),
                recent = store.recentSessions(device).map { SessionCard(it, LiftLogic.summarize(it, store.sets(it.id))) },
                bests = LiftLogic.personalBests(store.allFinishedSets(device)),
                loaded = true,
            )
        }
    }

    fun start(program: LiftProgramRow?, then: () -> Unit) = launch {
        val session = withContext(Dispatchers.IO) {
            store.openSession(device) ?: store.startSession(device, clock(), program?.id, program?.name)
        }
        loadActive(session, preferredExercise = null)
        then()
    }

    fun resume(then: () -> Unit) = launch {
        val session = withContext(Dispatchers.IO) { store.openSession(device) } ?: return@launch
        loadActive(session, preferredExercise = null)
        then()
    }

    private suspend fun loadActive(session: LiftSessionRow, preferredExercise: String?, newBestSetId: String? = null) {
        active = withContext(Dispatchers.IO) {
            val sets = store.sets(session.id)
            val items = session.programId?.let(store::programItems).orEmpty()
            val exercise = preferredExercise
                ?: sets.lastOrNull()?.exercise
                ?: items.firstOrNull()?.exercise
                ?: ""
            val last = if (exercise.isBlank()) emptyList() else store.lastPerformance(device, exercise, session.id)
            Active(
                session = session,
                sets = sets,
                programItems = items,
                exercises = store.exercises(device),
                exercise = exercise,
                lastTime = last,
                suggestion = if (exercise.isBlank()) null
                else LiftLogic.suggest(items.firstOrNull { it.exercise == exercise }, last, unit),
                newBestSetId = newBestSetId,
            )
        }
    }

    fun selectExercise(name: String) = launch {
        val a = active ?: return@launch
        loadActive(a.session, name.trim())
    }

    fun logSet(exerciseName: String, weightKg: Double?, reps: Int?, rpe: Double?, warmup: Boolean) = launch {
        val a = active ?: return@launch
        val exercise = exerciseName.trim()
        if (exercise.isEmpty()) return@launch
        val newBest = withContext(Dispatchers.IO) {
            val ex = store.touchExercise(device, exercise, clock())
            val set = store.logSet(a.session, exercise, weightKg, reps, rpe, warmup, clock(), ex.primaryMuscle)
            set.id.takeIf { LiftLogic.isNewBest(set, store.allFinishedSets(device)) }
        }
        loadActive(a.session, exercise, newBest)
    }

    fun deleteSet(set: LiftSetEntity) = launch {
        val a = active ?: return@launch
        withContext(Dispatchers.IO) { store.deleteSet(set) }
        loadActive(a.session, a.exercise)
    }

    fun finish(sessionRpe: Double?, note: String?, then: () -> Unit) = launch {
        val a = active ?: return@launch
        val row = withContext(Dispatchers.IO) {
            val done = store.finishSession(a.session.id, clock().coerceAtLeast(a.session.startTs + 1), sessionRpe, note)
                ?: return@withContext null
            LiftLogic.workoutRow(done, store.sets(done.id), unit)
        }
        row?.let(saveWorkout)
        active = null
        refreshHub()
        then()
    }

    fun discard(then: () -> Unit) = launch {
        val a = active ?: return@launch
        withContext(Dispatchers.IO) { store.deleteSession(a.session.id) }
        active = null
        refreshHub()
        then()
    }

    suspend fun sessionDetail(id: String): Pair<LiftSessionRow, List<LiftSetEntity>>? = withContext(Dispatchers.IO) {
        store.session(id)?.let { it to store.sets(it.id) }
    }

    fun deleteFinished(session: LiftSessionRow, sets: List<LiftSetEntity>, then: () -> Unit) = launch {
        val row = LiftLogic.workoutRow(session, sets, unit)
        withContext(Dispatchers.IO) { store.deleteSession(session.id) }
        row?.let(deleteWorkout)
        refreshHub()
        then()
    }

    suspend fun programForEdit(id: String?): Pair<LiftProgramRow, List<LiftProgramItemRow>> = withContext(Dispatchers.IO) {
        val now = clock()
        val existing = id?.let { pid -> store.programs(device, includeArchived = true).firstOrNull { it.id == pid } }
        if (existing != null) existing to store.programItems(existing.id)
        else LiftProgramRow(java.util.UUID.randomUUID().toString(), device, "", null, now, now) to emptyList()
    }

    fun saveProgram(program: LiftProgramRow, items: List<LiftProgramItemRow>, then: () -> Unit) = launch {
        if (program.name.isBlank()) return@launch
        withContext(Dispatchers.IO) {
            store.saveProgram(program.copy(updatedAt = clock()), items.filter { it.exercise.isNotBlank() })
        }
        refreshHub()
        then()
    }

    fun archiveProgram(id: String, then: () -> Unit) = launch {
        withContext(Dispatchers.IO) { store.archiveProgram(id, clock()) }
        refreshHub()
        then()
    }

    fun now(): Long = clock()
}
