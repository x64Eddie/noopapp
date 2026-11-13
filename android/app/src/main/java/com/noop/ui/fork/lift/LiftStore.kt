package com.noop.ui.fork.lift

import com.noop.data.LiftExerciseRow
import com.noop.data.LiftProgramItemRow
import com.noop.data.LiftProgramRow
import com.noop.data.LiftSessionRow
import com.noop.data.LiftSetEntity
import java.util.UUID

/**
 * Reads and writes upstream's lift tables (`liftExercise`, `liftProgram`, `liftProgramItem`,
 * `liftSession`, `liftSet`). Column lists are spelled out, never `SELECT *`, so a schema change upstream
 * fails loudly here rather than shifting columns silently.
 *
 * Every row carries [deviceId]. The Lift Log writes under the manual-workout namespace
 * ([LiftLog.DEVICE_ID]) so a session pairs 1:1 with the manual `workout` row saved for it, and history
 * survives a strap re-pair.
 */
class LiftStore(private val sql: LiftSql, private val newId: () -> String = { UUID.randomUUID().toString() }) {

    // MARK: exercises

    fun exercises(deviceId: String): List<LiftExerciseRow> = sql.query(
        "SELECT id, deviceId, name, primaryMuscle, secondaryMuscles, createdAt, lastUsedTs FROM liftExercise " +
            "WHERE deviceId = ? ORDER BY lastUsedTs IS NULL, lastUsedTs DESC, name COLLATE NOCASE",
        arrayOf(deviceId),
    ) { r ->
        LiftExerciseRow(
            id = r.string(0)!!, deviceId = r.string(1)!!, name = r.string(2)!!, primaryMuscle = r.string(3),
            secondaryMuscles = r.string(4), createdAt = r.long(5)!!, lastUsedTs = r.long(6),
        )
    }

    /** Returns the exercise with this exact name, creating it when new, and stamps it as just used. */
    fun touchExercise(deviceId: String, name: String, now: Long, primaryMuscle: String? = null): LiftExerciseRow {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "exercise name must not be blank" }
        return sql.transaction {
            val existing = exercises(deviceId).firstOrNull { it.name == trimmed }
            if (existing != null) {
                sql.exec("UPDATE liftExercise SET lastUsedTs = ? WHERE id = ?", arrayOf(now, existing.id))
                existing.copy(lastUsedTs = now)
            } else {
                val row = LiftExerciseRow(newId(), deviceId, trimmed, primaryMuscle, null, now, now)
                sql.exec(
                    "INSERT INTO liftExercise (id, deviceId, name, primaryMuscle, secondaryMuscles, createdAt, " +
                        "lastUsedTs) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    arrayOf(row.id, row.deviceId, row.name, row.primaryMuscle, row.secondaryMuscles, row.createdAt, row.lastUsedTs),
                )
                row
            }
        }
    }

    fun setExerciseMuscle(id: String, primaryMuscle: String?) {
        sql.exec("UPDATE liftExercise SET primaryMuscle = ? WHERE id = ?", arrayOf(primaryMuscle, id))
    }

    // MARK: programs

    fun programs(deviceId: String, includeArchived: Boolean = false): List<LiftProgramRow> = sql.query(
        "SELECT id, deviceId, name, note, createdAt, updatedAt, archived FROM liftProgram " +
            "WHERE deviceId = ? AND (archived = 0 OR ?) ORDER BY updatedAt DESC",
        arrayOf(deviceId, if (includeArchived) 1 else 0),
    ) { r ->
        LiftProgramRow(
            id = r.string(0)!!, deviceId = r.string(1)!!, name = r.string(2)!!, note = r.string(3),
            createdAt = r.long(4)!!, updatedAt = r.long(5)!!, archived = r.long(6) == 1L,
        )
    }

    fun programItems(programId: String): List<LiftProgramItemRow> = sql.query(
        "SELECT id, deviceId, programId, ord, exercise, targetSets, targetRepsLow, targetRepsHigh, targetRpe, " +
            "targetWeightKg, restSec, note FROM liftProgramItem WHERE programId = ? ORDER BY ord",
        arrayOf(programId),
    ) { r ->
        LiftProgramItemRow(
            id = r.string(0)!!, deviceId = r.string(1)!!, programId = r.string(2)!!, ord = r.long(3)!!.toInt(),
            exercise = r.string(4)!!, targetSets = r.long(5)?.toInt(), targetRepsLow = r.long(6)?.toInt(),
            targetRepsHigh = r.long(7)?.toInt(), targetRpe = r.double(8), targetWeightKg = r.double(9),
            restSec = r.long(10)?.toInt(), note = r.string(11),
        )
    }

    /** Writes the program and replaces its item list in one transaction. Item ids and ords are reassigned. */
    fun saveProgram(program: LiftProgramRow, items: List<LiftProgramItemRow>): LiftProgramRow = sql.transaction {
        sql.exec(
            "INSERT INTO liftProgram (id, deviceId, name, note, createdAt, updatedAt, archived) VALUES (?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(id) DO UPDATE SET name = excluded.name, note = excluded.note, " +
                "updatedAt = excluded.updatedAt, archived = excluded.archived",
            arrayOf(
                program.id, program.deviceId, program.name.trim(), program.note, program.createdAt,
                program.updatedAt, if (program.archived) 1 else 0,
            ),
        )
        sql.exec("DELETE FROM liftProgramItem WHERE programId = ?", arrayOf(program.id))
        items.forEachIndexed { index, item ->
            sql.exec(
                "INSERT INTO liftProgramItem (id, deviceId, programId, ord, exercise, targetSets, targetRepsLow, " +
                    "targetRepsHigh, targetRpe, targetWeightKg, restSec, note) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf(
                    newId(), program.deviceId, program.id, index, item.exercise.trim(), item.targetSets,
                    item.targetRepsLow, item.targetRepsHigh, item.targetRpe, item.targetWeightKg, item.restSec, item.note,
                ),
            )
        }
        program
    }

    fun archiveProgram(id: String, now: Long) {
        sql.exec("UPDATE liftProgram SET archived = 1, updatedAt = ? WHERE id = ?", arrayOf(now, id))
    }

    // MARK: sessions

    private val sessionColumns = "id, deviceId, startTs, endTs, sport, programId, programName, sessionRpe, note"

    private fun sessionOf(r: LiftRow) = LiftSessionRow(
        id = r.string(0)!!, deviceId = r.string(1)!!, startTs = r.long(2)!!, endTs = r.long(3),
        sport = r.string(4)!!, programId = r.string(5), programName = r.string(6), sessionRpe = r.double(7),
        note = r.string(8),
    )

    fun session(id: String): LiftSessionRow? =
        sql.query("SELECT $sessionColumns FROM liftSession WHERE id = ?", arrayOf(id), ::sessionOf).firstOrNull()

    /** The session still running, if any (endTs not yet set). */
    fun openSession(deviceId: String): LiftSessionRow? = sql.query(
        "SELECT $sessionColumns FROM liftSession WHERE deviceId = ? AND endTs IS NULL ORDER BY startTs DESC LIMIT 1",
        arrayOf(deviceId), ::sessionOf,
    ).firstOrNull()

    fun recentSessions(deviceId: String, limit: Int = 30): List<LiftSessionRow> = sql.query(
        "SELECT $sessionColumns FROM liftSession WHERE deviceId = ? AND endTs IS NOT NULL ORDER BY startTs DESC LIMIT ?",
        arrayOf(deviceId, limit), ::sessionOf,
    )

    /**
     * Starts a session. `(deviceId, startTs, sport)` is UNIQUE (it pairs with `workout`), so a second start
     * in the same second moves forward a second rather than failing.
     */
    fun startSession(
        deviceId: String,
        startTs: Long,
        programId: String? = null,
        programName: String? = null,
        sport: String = LiftLog.SPORT,
    ): LiftSessionRow = sql.transaction {
        var ts = startTs
        while (sql.query(
                "SELECT 1 FROM liftSession WHERE deviceId = ? AND startTs = ? AND sport = ?",
                arrayOf(deviceId, ts, sport),
            ) { 1 }.isNotEmpty()
        ) ts += 1
        val row = LiftSessionRow(newId(), deviceId, ts, null, sport, programId, programName, null, null)
        sql.exec(
            "INSERT INTO liftSession ($sessionColumns) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf(row.id, row.deviceId, row.startTs, null, row.sport, row.programId, row.programName, null, null),
        )
        row
    }

    fun finishSession(id: String, endTs: Long, sessionRpe: Double?, note: String?): LiftSessionRow? {
        sql.exec(
            "UPDATE liftSession SET endTs = ?, sessionRpe = ?, note = ? WHERE id = ?",
            arrayOf(endTs, sessionRpe, note?.takeIf { it.isNotBlank() }, id),
        )
        return session(id)
    }

    /** Deletes the session and its sets together, so no orphan set is left behind. */
    fun deleteSession(id: String) = sql.transaction {
        sql.exec("DELETE FROM liftSet WHERE sessionId = ?", arrayOf(id))
        sql.exec("DELETE FROM liftSession WHERE id = ?", arrayOf(id))
    }

    // MARK: sets

    private val setColumns = "id, deviceId, sessionId, ord, exercise, primaryMuscle, secondaryMuscles, setIndex, " +
        "weightKg, reps, rpe, isWarmup, startTs, endTs, restSec, note"

    private fun setOf(r: LiftRow) = LiftSetEntity(
        id = r.string(0)!!, deviceId = r.string(1)!!, sessionId = r.string(2)!!, ord = r.long(3)!!.toInt(),
        exercise = r.string(4)!!, primaryMuscle = r.string(5), secondaryMuscles = r.string(6),
        setIndex = r.long(7)!!.toInt(), weightKg = r.double(8), reps = r.long(9)?.toInt(), rpe = r.double(10),
        isWarmup = r.long(11) == 1L, startTs = r.long(12), endTs = r.long(13), restSec = r.long(14)?.toInt(),
        note = r.string(15),
    )

    fun sets(sessionId: String): List<LiftSetEntity> =
        sql.query("SELECT $setColumns FROM liftSet WHERE sessionId = ? ORDER BY ord", arrayOf(sessionId), ::setOf)

    /**
     * Appends a performed set: its order in the session and its index within the exercise are assigned
     * here, and the rest actually taken after the previous set is measured from that set's end.
     */
    fun logSet(
        session: LiftSessionRow,
        exercise: String,
        weightKg: Double?,
        reps: Int?,
        rpe: Double?,
        isWarmup: Boolean,
        endTs: Long,
        primaryMuscle: String? = null,
    ): LiftSetEntity = sql.transaction {
        val existing = sets(session.id)
        val previous = existing.lastOrNull()
        val setIndex = existing.count { it.exercise == exercise } + 1
        val startTs = previous?.endTs?.coerceAtMost(endTs)
        previous?.let { p ->
            p.endTs?.let { pe -> sql.exec("UPDATE liftSet SET restSec = ? WHERE id = ?", arrayOf((endTs - pe).coerceAtLeast(0), p.id)) }
        }
        val row = LiftSetEntity(
            id = newId(), deviceId = session.deviceId, sessionId = session.id, ord = existing.size,
            exercise = exercise, primaryMuscle = primaryMuscle, secondaryMuscles = null, setIndex = setIndex,
            weightKg = weightKg, reps = reps, rpe = rpe, isWarmup = isWarmup, startTs = startTs, endTs = endTs,
            restSec = null, note = null,
        )
        sql.exec(
            "INSERT INTO liftSet ($setColumns) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf(
                row.id, row.deviceId, row.sessionId, row.ord, row.exercise, row.primaryMuscle, row.secondaryMuscles,
                row.setIndex, row.weightKg, row.reps, row.rpe, if (row.isWarmup) 1 else 0, row.startTs, row.endTs,
                row.restSec, row.note,
            ),
        )
        row
    }

    fun updateSet(id: String, weightKg: Double?, reps: Int?, rpe: Double?, isWarmup: Boolean) {
        sql.exec(
            "UPDATE liftSet SET weightKg = ?, reps = ?, rpe = ?, isWarmup = ? WHERE id = ?",
            arrayOf(weightKg, reps, rpe, if (isWarmup) 1 else 0, id),
        )
    }

    /** Deletes one set and renumbers the session's order and per-exercise indexes so they stay dense. */
    fun deleteSet(set: LiftSetEntity) = sql.transaction {
        sql.exec("DELETE FROM liftSet WHERE id = ?", arrayOf(set.id))
        val counts = mutableMapOf<String, Int>()
        sets(set.sessionId).forEachIndexed { ord, s ->
            val index = (counts[s.exercise] ?: 0) + 1
            counts[s.exercise] = index
            sql.exec("UPDATE liftSet SET ord = ?, setIndex = ? WHERE id = ?", arrayOf(ord, index, s.id))
        }
    }

    /** Sets of [exercise] from the most recent finished session (other than [excludingSessionId]) that had it. */
    fun lastPerformance(deviceId: String, exercise: String, excludingSessionId: String?): List<LiftSetEntity> {
        val sessionId = sql.query(
            "SELECT s.id FROM liftSession s JOIN liftSet t ON t.sessionId = s.id " +
                "WHERE s.deviceId = ? AND t.exercise = ? AND s.id <> ? AND s.endTs IS NOT NULL " +
                "ORDER BY s.startTs DESC LIMIT 1",
            arrayOf(deviceId, exercise, excludingSessionId ?: ""),
        ) { it.string(0)!! }.firstOrNull() ?: return emptyList()
        return sets(sessionId).filter { it.exercise == exercise }
    }

    /**
     * Inserts a finished, already-complete session and its sets in one transaction, for imports.
     * Returns false and writes nothing when a session already holds the natural key
     * `(deviceId, startTs, sport)`: an import never overwrites a session logged here, and a second
     * import of the same file is a no-op.
     */
    fun importSession(session: LiftSessionRow, sets: List<LiftSetEntity>, now: Long): Boolean = sql.transaction {
        val taken = sql.query(
            "SELECT 1 FROM liftSession WHERE id = ? OR (deviceId = ? AND startTs = ? AND sport = ?)",
            arrayOf(session.id, session.deviceId, session.startTs, session.sport),
        ) { 1 }.isNotEmpty()
        if (taken) return@transaction false
        sql.exec(
            "INSERT INTO liftSession ($sessionColumns) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf(
                session.id, session.deviceId, session.startTs, session.endTs, session.sport, session.programId,
                session.programName, session.sessionRpe, session.note,
            ),
        )
        sets.forEach { s ->
            sql.exec(
                "INSERT INTO liftSet ($setColumns) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf(
                    s.id, s.deviceId, s.sessionId, s.ord, s.exercise, s.primaryMuscle, s.secondaryMuscles, s.setIndex,
                    s.weightKg, s.reps, s.rpe, if (s.isWarmup) 1 else 0, s.startTs, s.endTs, s.restSec, s.note,
                ),
            )
        }
        sets.map { it.exercise }.distinct().forEach { touchExercise(session.deviceId, it, now) }
        true
    }

    /** Every set of every finished session, for per-exercise bests. A personal log is small enough. */
    fun allFinishedSets(deviceId: String): List<LiftSetEntity> = sql.query(
        "SELECT ${setColumns.split(", ").joinToString { "t.$it" }} FROM liftSet t JOIN liftSession s ON s.id = t.sessionId " +
            "WHERE t.deviceId = ? AND s.endTs IS NOT NULL ORDER BY s.startTs, t.ord",
        arrayOf(deviceId), ::setOf,
    )
}
