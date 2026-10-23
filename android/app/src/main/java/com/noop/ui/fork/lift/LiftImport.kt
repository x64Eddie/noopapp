package com.noop.ui.fork.lift

import android.content.Context
import com.noop.data.LiftSessionRow
import com.noop.data.LiftSetEntity
import com.noop.data.WhoopDatabase
import com.noop.ingest.Bom
import com.noop.ingest.CsvTable
import com.noop.ingest.LiftingImporter
import com.noop.ingest.cell
import com.noop.ingest.double
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId

/**
 * Fork-only: keeps the per-set detail of a lifting import.
 *
 * Upstream's [LiftingImporter] folds each Liftosaur or Hevy session into one `workout` summary row
 * (deviceId "lifting") and discards the sets. This re-reads the same export bytes and writes every
 * session and set into the lift tables under [LiftLog.DEVICE_ID], so imported history feeds the Lift
 * Log's last-time numbers, progression suggestions and bests, and is pushed to the receiver.
 *
 * The importer's own parsing is untouched. The two parses read the same rows with the same helpers
 * (`parseEpochSeconds`, `jsonDouble`, the CSV accessors), and both skip a session with no performed set.
 *
 * Identity and safety:
 * - Session and set ids are derived from the source and start time, so a re-import is a no-op.
 * - A session whose `(deviceId, startTs, sport)` already exists is skipped, never overwritten.
 * - An imported session pairs with the existing "lifting" workout row, not a manual one: no second
 *   workout row is written for it.
 */
object LiftImport {

    data class ParsedSet(
        val exercise: String,
        val weightKg: Double?,
        val reps: Int?,
        val rpe: Double?,
        val isWarmup: Boolean,
        val endTs: Long?,
    )

    data class ParsedSession(
        val source: String,
        val startTs: Long,
        val endTs: Long,
        val title: String?,
        val sets: List<ParsedSet>,
    )

    /** Called once from [LiftingImporter.importExport] after its workout rows are saved. */
    fun importDetail(context: Context, bytes: ByteArray, zone: ZoneId = ZoneId.systemDefault()): Int {
        val sessions = parse(bytes, zone)
        if (sessions.isEmpty()) return 0
        val store = LiftStore(RoomLiftSql(WhoopDatabase.get(context)))
        return persist(store, sessions, System.currentTimeMillis() / 1000)
    }

    fun persist(store: LiftStore, sessions: List<ParsedSession>, now: Long): Int = sessions.count { s ->
        val sessionId = "import-${s.source}-${s.startTs}"
        val row = LiftSessionRow(
            id = sessionId, deviceId = LiftLog.DEVICE_ID, startTs = s.startTs, endTs = s.endTs,
            sport = LiftLog.SPORT, programId = null, programName = s.title, sessionRpe = null, note = null,
        )
        val perExercise = mutableMapOf<String, Int>()
        val sets = s.sets.mapIndexed { ord, p ->
            val index = (perExercise[p.exercise] ?: 0) + 1
            perExercise[p.exercise] = index
            LiftSetEntity(
                id = "$sessionId-$ord", deviceId = LiftLog.DEVICE_ID, sessionId = sessionId, ord = ord,
                exercise = p.exercise, setIndex = index, weightKg = p.weightKg, reps = p.reps, rpe = p.rpe,
                isWarmup = p.isWarmup, endTs = p.endTs,
            )
        }
        store.importSession(row, sets, now)
    }

    fun parse(bytes: ByteArray, zone: ZoneId): List<ParsedSession> =
        when (LiftingImporter.detectFormat(bytes)) {
            LiftingImporter.Format.LIFTOSAUR_JSON -> parseLiftosaur(Bom.stripString(String(Bom.stripUtf8(bytes), Charsets.UTF_8)), zone)
            LiftingImporter.Format.HEVY_CSV -> parseHevyCsv(CsvTable.fromData(bytes), zone)
        }

    // MARK: Liftosaur

    internal fun parseLiftosaur(text: String, zone: ZoneId): List<ParsedSession> {
        val trimmed = text.trim()
        val history = if (trimmed.startsWith("[")) runCatching { JSONArray(trimmed) }.getOrNull()
        else runCatching { JSONObject(trimmed) }.getOrNull()?.let {
            it.optJSONArray("history") ?: it.optJSONObject("storage")?.optJSONArray("history")
        }
        history ?: return emptyList()
        val out = ArrayList<ParsedSession>()
        for (i in 0 until history.length()) {
            val record = history.optJSONObject(i) ?: continue
            val start = epoch(record.opt("startTime") ?: record.opt("date") ?: record.opt("ts"), zone) ?: continue
            val end = epoch(record.opt("endTime"), zone)?.takeIf { it >= start } ?: start
            val sets = ArrayList<ParsedSet>()
            val entries = record.optJSONArray("entries") ?: JSONArray()
            for (e in 0 until entries.length()) {
                val entry = entries.optJSONObject(e) ?: continue
                val name = exerciseName(entry) ?: continue
                val unit = entry.optString("unit", "").lowercase().ifEmpty { null }
                entry.optJSONArray("warmupSets")?.let { addLiftosaurSets(it, name, unit, true, zone, sets) }
                entry.optJSONArray("sets")?.let { addLiftosaurSets(it, name, unit, false, zone, sets) }
            }
            if (sets.none { !it.isWarmup }) continue // same rule as the importer: no performed set, no session
            val title = record.optString("programName", "").ifEmpty { record.optString("dayName", "").ifEmpty { null } }
            out += ParsedSession("liftosaur", start, end, title, sets)
        }
        return out
    }

    private fun addLiftosaurSets(
        list: JSONArray, name: String, unit: String?, warmup: Boolean, zone: ZoneId, out: MutableList<ParsedSet>,
    ) {
        for (s in 0 until list.length()) {
            val set = list.optJSONObject(s) ?: continue
            if (!set.has("completedReps")) continue // a planned set nobody did
            val reps = LiftingImporter.jsonDouble(set.opt("completedReps"))?.takeIf { it in 0.0..1e6 }?.toInt() ?: continue
            if (reps <= 0) continue
            out += ParsedSet(
                exercise = name,
                weightKg = weightKg(set, unit),
                reps = reps,
                rpe = LiftingImporter.jsonDouble(set.opt("completedRpe"))?.takeIf { it in 1.0..10.0 },
                isWarmup = warmup,
                endTs = epoch(set.opt("timestamp"), zone),
            )
        }
    }

    /** Liftosaur names an exercise by `exercise.id` (a slug) or a plain `name`; prefer a readable one. */
    private fun exerciseName(entry: JSONObject): String? {
        val ex = entry.opt("exercise")
        val raw = when (ex) {
            is JSONObject -> ex.optString("name").ifEmpty { ex.optString("id") }
            is String -> ex
            else -> entry.optString("name")
        }.trim()
        if (raw.isEmpty()) return null
        return if (raw.any { it.isUpperCase() } || raw.contains(' ')) raw
        else raw.split('_', '-').filter { it.isNotEmpty() }.joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
    }

    private fun weightKg(set: JSONObject, entryUnit: String?): Double? {
        val raw = set.opt("completedWeight") ?: if (set.has("weight")) set.opt("weight") else set.opt("weightValue")
        val (value, unit) = if (raw is JSONObject) {
            LiftingImporter.jsonDouble(raw.opt("value")) to raw.optString("unit", "").lowercase().ifEmpty { entryUnit }
        } else LiftingImporter.jsonDouble(raw) to entryUnit
        val v = value ?: return null
        return if (unit == "lb" || unit == "lbs") v * LiftingImporter.LB_TO_KG else v
    }

    /** Epoch ms or s as a number, or a date string; mirrors the importer's Liftosaur date rule. */
    private fun epoch(any: Any?, zone: ZoneId): Long? {
        val n = LiftingImporter.jsonDouble(any)
        if (n != null && n > 0) return if (n > 1_000_000_000_000.0) (n / 1000).toLong() else n.toLong()
        return (any as? String)?.let { LiftingImporter.parseEpochSeconds(it, zone) }
    }

    // MARK: Hevy CSV (one row per set)

    internal fun parseHevyCsv(table: CsvTable, zone: ZoneId): List<ParsedSession> {
        data class Acc(val start: Long, val title: String?, var endRaw: String?, val sets: MutableList<ParsedSet>)
        val byKey = LinkedHashMap<String, Acc>()
        for (row in table.rows) {
            val startRaw = row.cell("start_time", "start", "date") ?: continue
            val start = LiftingImporter.parseEpochSeconds(startRaw, zone) ?: continue
            val title = row.cell("title", "workout_name", "name")
            val acc = byKey.getOrPut("${title ?: ""}|$startRaw") { Acc(start, title, null, mutableListOf()) }
            row.cell("end_time", "end")?.let { acc.endRaw = it }
            val exercise = row.cell("exercise_title", "exercise_name", "exercise")?.trim().orEmpty()
            if (exercise.isEmpty()) continue
            val setType = (row.cell("set_type", "type") ?: "").lowercase()
            val weight = (row.double("weight_kg", "weight", "weight_kgs")
                ?: row.double("weight_lb", "weight_lbs", "weight_lbf")?.let { it * LiftingImporter.LB_TO_KG })
                ?.takeIf { it.isFinite() }
            val reps = row.double("reps", "rep_count")?.takeIf { it.isFinite() && it >= 0 && it < 1e6 }?.toInt()
            acc.sets += ParsedSet(
                exercise = exercise,
                weightKg = weight,
                reps = reps,
                rpe = row.double("rpe")?.takeIf { it in 1.0..10.0 },
                isWarmup = setType == "warmup" || setType == "warm_up" || setType == "warm-up",
                endTs = null,
            )
        }
        return byKey.values.mapNotNull { a ->
            if (a.sets.none { !it.isWarmup }) return@mapNotNull null
            val end = a.endRaw?.let { LiftingImporter.parseEpochSeconds(it, zone) }?.takeIf { it >= a.start } ?: a.start
            ParsedSession("hevy", a.start, end, a.title, a.sets)
        }
    }
}
