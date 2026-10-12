package com.noop.ble

/**
 * FTMS (Fitness Machine Service, 0x1826) pure decoders + the standard Battery Level (0x2A19) parser.
 *
 * Faithful Kotlin twin of WhoopProtocol/FTMSDecode.swift. Spec-deterministic field parsing for the four
 * machine-data characteristics NOOP reads live:
 *   - Treadmill Data     0x2ACD
 *   - Cross Trainer Data 0x2ACE
 *   - Rower Data         0x2AD1
 *   - Indoor Bike Data   0x2AD2
 *
 * Each characteristic begins with a little-endian flags field whose bits gate which fields follow, in a
 * FIXED spec order. We decode the flag-gated fields we surface (speed, cadence, power, distance, total
 * energy, heart rate, elapsed time) and IGNORE the rest by advancing the cursor by their spec width, so
 * an unrecognised optional field never desynchronises the ones after it.
 *
 * SECURITY / ROBUSTNESS: the byte buffer is UNTRUSTED BLE input. Every field read is bounds-checked
 * against the buffer length; a truncated/malformed packet yields the fields decoded so far (never a
 * crash, never a read past the end) — mirroring [StandardHeartRate.parse]'s bounds discipline.
 *
 * Pure (no android.bluetooth) → unit-tested on the JVM against byte fixtures built from the FTMS spec.
 *
 * Reference: Bluetooth SIG "Fitness Machine Service" 1.0 + the GATT Specification Supplement field
 * tables. NOOP's own clean re-implementation of the public spec.
 */
object FitnessMachine {

    /** Which FTMS machine, identified by which machine-data characteristic it streams. */
    enum class MachineKind(val uuid16: String, val displayName: String) {
        TREADMILL("2ACD", "Treadmill"),
        INDOOR_BIKE("2AD2", "Indoor Bike"),
        ROWER("2AD1", "Rower"),
        CROSS_TRAINER("2ACE", "Cross Trainer"),
    }

    /**
     * A single decoded FTMS machine-data notification. Every field is nullable — a machine advertises
     * only a subset, and a truncated packet decodes only what fit. Units are the spec's resolved units.
     */
    data class Reading(
        val kind: MachineKind,
        /** Instantaneous speed in km/h. */
        val speedKmh: Double? = null,
        /** Cadence: steps/min (treadmill/cross-trainer), rpm (indoor bike), strokes/min (rower). */
        val cadence: Double? = null,
        /** Instantaneous power in watts (signed). */
        val powerWatts: Int? = null,
        /** Total distance this session, in metres. */
        val distanceM: Int? = null,
        /** Total energy this session, in kilocalories. */
        val totalEnergyKcal: Int? = null,
        /** Heart rate in bpm, if the machine reports it. */
        val heartRate: Int? = null,
        /** Elapsed session time in seconds. */
        val elapsedTimeSec: Int? = null,
    )

    /** A forward cursor over the byte buffer; reads advance only on success (bounds-checked). */
    private class Reader(val bytes: ByteArray) {
        var idx = 0
        fun u8(): Int? {
            if (idx >= bytes.size) return null
            return (bytes[idx++].toInt() and 0xFF)
        }
        fun u16(): Int? {
            if (idx + 1 >= bytes.size) return null
            val v = (bytes[idx].toInt() and 0xFF) or ((bytes[idx + 1].toInt() and 0xFF) shl 8)
            idx += 2
            return v
        }
        /** Signed 16-bit (two's complement) — FTMS power is sint16. */
        fun s16(): Int? {
            val raw = u16() ?: return null
            return if (raw >= 0x8000) raw - 0x10000 else raw
        }
        fun u24(): Int? {
            if (idx + 2 >= bytes.size) return null
            val v = (bytes[idx].toInt() and 0xFF) or
                ((bytes[idx + 1].toInt() and 0xFF) shl 8) or
                ((bytes[idx + 2].toInt() and 0xFF) shl 16)
            idx += 3
            return v
        }
        /** Skip n bytes (an optional field we don't surface), clamped so it never runs past the end. */
        fun skip(n: Int) { idx = minOf(bytes.size, idx + n) }
    }

    // MARK: - Treadmill Data (0x2ACD)
    fun treadmill(data: ByteArray): Reading? {
        val r = Reader(data)
        val flags = r.u16() ?: return null
        var speed: Double? = null
        var distance: Int? = null
        var energy: Int? = null
        var hr: Int? = null
        var elapsed: Int? = null

        if (flags and 0x0001 == 0) r.u16()?.let { speed = it * 0.01 }  // Inst. Speed (absent if More Data)
        if (flags and 0x0002 != 0) r.u16()                            // Average Speed
        if (flags and 0x0004 != 0) r.u24()?.let { distance = it }     // Total Distance
        if (flags and 0x0008 != 0) r.skip(4)                          // Inclination + Ramp Angle
        if (flags and 0x0010 != 0) r.skip(4)                          // Pos + Neg Elevation Gain
        if (flags and 0x0020 != 0) r.u8()                             // Instantaneous Pace
        if (flags and 0x0040 != 0) r.u8()                             // Average Pace
        if (flags and 0x0080 != 0) {                                  // Total Energy block
            r.u16()?.let { energy = it }                              // Total Energy (kcal)
            r.u16(); r.u8()                                           // per-hour + per-min
        }
        if (flags and 0x0100 != 0) r.u8()?.let { hr = it }            // Heart Rate
        if (flags and 0x0200 != 0) r.u8()                             // Metabolic Equivalent
        if (flags and 0x0400 != 0) r.u16()?.let { elapsed = it }      // Elapsed Time
        return Reading(MachineKind.TREADMILL, speedKmh = speed, distanceM = distance,
            totalEnergyKcal = energy, heartRate = hr, elapsedTimeSec = elapsed)
    }

    // MARK: - Indoor Bike Data (0x2AD2)
    fun indoorBike(data: ByteArray): Reading? {
        val r = Reader(data)
        val flags = r.u16() ?: return null
        var speed: Double? = null
        var cadence: Double? = null
        var power: Int? = null
        var distance: Int? = null
        var energy: Int? = null
        var hr: Int? = null
        var elapsed: Int? = null

        if (flags and 0x0001 == 0) r.u16()?.let { speed = it * 0.01 } // Inst. Speed
        if (flags and 0x0002 != 0) r.u16()                           // Average Speed
        if (flags and 0x0004 != 0) r.u16()?.let { cadence = it * 0.5 } // Inst. Cadence (0.5 rpm)
        if (flags and 0x0008 != 0) r.u16()                           // Average Cadence
        if (flags and 0x0010 != 0) r.u24()?.let { distance = it }    // Total Distance
        if (flags and 0x0020 != 0) r.u16()                           // Resistance Level
        if (flags and 0x0040 != 0) r.s16()?.let { power = it }       // Inst. Power
        if (flags and 0x0080 != 0) r.s16()                           // Average Power
        if (flags and 0x0100 != 0) { r.u16()?.let { energy = it }; r.u16(); r.u8() } // Total Energy
        if (flags and 0x0200 != 0) r.u8()?.let { hr = it }           // Heart Rate
        if (flags and 0x0400 != 0) r.u8()                            // Metabolic Equivalent
        if (flags and 0x0800 != 0) r.u16()?.let { elapsed = it }     // Elapsed Time
        return Reading(MachineKind.INDOOR_BIKE, speedKmh = speed, cadence = cadence, powerWatts = power,
            distanceM = distance, totalEnergyKcal = energy, heartRate = hr, elapsedTimeSec = elapsed)
    }

    // MARK: - Rower Data (0x2AD1)
    fun rower(data: ByteArray): Reading? {
        val r = Reader(data)
        val flags = r.u16() ?: return null
        var cadence: Double? = null
        var power: Int? = null
        var distance: Int? = null
        var energy: Int? = null
        var hr: Int? = null
        var elapsed: Int? = null

        if (flags and 0x0001 == 0) {                                 // Stroke Rate + Stroke Count
            r.u8()?.let { cadence = it * 0.5 }                       // 0.5 stroke/min
            r.u16()                                                  // Stroke Count
        }
        if (flags and 0x0002 != 0) r.u8()                           // Average Stroke Rate
        if (flags and 0x0004 != 0) r.u24()?.let { distance = it }   // Total Distance
        if (flags and 0x0008 != 0) r.u16()                          // Inst. Pace
        if (flags and 0x0010 != 0) r.u16()                          // Average Pace
        if (flags and 0x0020 != 0) r.s16()?.let { power = it }      // Inst. Power
        if (flags and 0x0040 != 0) r.s16()                          // Average Power
        if (flags and 0x0080 != 0) r.s16()                          // Resistance Level
        if (flags and 0x0100 != 0) { r.u16()?.let { energy = it }; r.u16(); r.u8() } // Total Energy
        if (flags and 0x0200 != 0) r.u8()?.let { hr = it }          // Heart Rate
        if (flags and 0x0400 != 0) r.u8()                           // Metabolic Equivalent
        if (flags and 0x0800 != 0) r.u16()?.let { elapsed = it }    // Elapsed Time
        return Reading(MachineKind.ROWER, cadence = cadence, powerWatts = power, distanceM = distance,
            totalEnergyKcal = energy, heartRate = hr, elapsedTimeSec = elapsed)
    }

    // MARK: - Cross Trainer Data (0x2ACE) — 24-bit flags (u16 + u8)
    fun crossTrainer(data: ByteArray): Reading? {
        val r = Reader(data)
        val lo = r.u16() ?: return null
        val hi = r.u8() ?: return null
        val flags = lo or (hi shl 16)
        var speed: Double? = null
        var cadence: Double? = null
        var power: Int? = null
        var distance: Int? = null
        var energy: Int? = null
        var hr: Int? = null
        var elapsed: Int? = null

        if (flags and 0x000001 == 0) r.u16()?.let { speed = it * 0.01 } // Inst. Speed
        if (flags and 0x000002 != 0) r.u16()                         // Average Speed
        if (flags and 0x000004 != 0) r.u24()?.let { distance = it }  // Total Distance
        if (flags and 0x000008 != 0) {                               // Step Count block
            r.u16()?.let { cadence = it.toDouble() }                 // Step Per Minute
            r.u16()                                                  // Average Step Rate
        }
        if (flags and 0x000010 != 0) r.u16()                         // Stride Count
        if (flags and 0x000020 != 0) r.skip(4)                       // Pos + Neg Elevation Gain
        if (flags and 0x000040 != 0) r.skip(4)                       // Inclination + Ramp Angle
        if (flags and 0x000080 != 0) r.s16()                         // Resistance Level
        if (flags and 0x000100 != 0) r.s16()?.let { power = it }     // Inst. Power
        if (flags and 0x000200 != 0) r.s16()                         // Average Power
        if (flags and 0x000400 != 0) { r.u16()?.let { energy = it }; r.u16(); r.u8() } // Total Energy
        if (flags and 0x000800 != 0) r.u8()?.let { hr = it }         // Heart Rate
        if (flags and 0x001000 != 0) r.u8()                          // Metabolic Equivalent
        if (flags and 0x002000 != 0) r.u16()?.let { elapsed = it }   // Elapsed Time
        return Reading(MachineKind.CROSS_TRAINER, speedKmh = speed, cadence = cadence, powerWatts = power,
            distanceM = distance, totalEnergyKcal = energy, heartRate = hr, elapsedTimeSec = elapsed)
    }

    /** Decode by the 16-bit characteristic UUID short form (case-insensitive); null for unknown/empty. */
    fun decode(uuid16: String, data: ByteArray): Reading? = when (uuid16.uppercase()) {
        "2ACD" -> treadmill(data)
        "2AD2" -> indoorBike(data)
        "2AD1" -> rower(data)
        "2ACE" -> crossTrainer(data)
        else -> null
    }
}

/**
 * Pure parser for the standard BLE Battery Level characteristic (0x2A19): a single u8 percent, 0–100.
 * Values above 100 are clamped; an empty buffer yields null. Faithful twin of Swift `StandardBattery`.
 */
object StandardBattery {
    fun parse(data: ByteArray): Int? {
        if (data.isEmpty()) return null
        return minOf(100, data[0].toInt() and 0xFF)
    }
}

/**
 * Pure formatting for the FTMS **capability probe** — the read-only diagnostic that answers "does this
 * machine actually serve treadmill data, and which fields does it populate?" before anything is built on
 * top of it. Many iFit-based machines (NordicTrack, ProForm) advertise Heart Rate *receive* without
 * exposing 0x1826 outbound at all, or expose it with the data characteristic unimplemented — and a
 * working HR broadcast to the machine is no evidence either way.
 *
 * Every string here asserts only what the bytes show: the characteristic, the frame length, the raw flags
 * word, and which of the fields NOOP surfaces the decode actually populated. It does NOT claim the machine
 * "supports" or "lacks" a metric in general — one frame is one frame, and a machine may set different flag
 * bits when it is idle than when it is moving.
 *
 * Faithful Kotlin twin of `WhoopProtocol.FTMSProbe`. The output strings are a byte-identical contract
 * across the two platforms: the same frame must produce the same line on Android and on Apple, so two
 * field logs from the same machine are directly comparable. Pinned by fixtures shared with
 * `FTMSDecodeTests.probeFrameSummary…` on the Swift side.
 */
object FitnessMachineProbe {

    /**
     * Byte width of the leading flags field. Cross Trainer Data carries a 24-bit flags word; the other
     * three carry 16-bit.
     */
    fun flagsWidth(kind: FitnessMachine.MachineKind): Int =
        if (kind == FitnessMachine.MachineKind.CROSS_TRAINER) 3 else 2

    /**
     * The raw little-endian flags word at the head of a machine-data frame, or null if the UUID is not one
     * of the four machine-data characteristics or the frame is too short to hold its flags field.
     */
    fun flags(uuid16: String, data: ByteArray): Int? {
        val kind = kindForUuid16(uuid16) ?: return null
        val width = flagsWidth(kind)
        if (data.size < width) return null
        var value = 0
        for (i in 0 until width) value = value or ((data[i].toInt() and 0xFF) shl (8 * i))
        return value
    }

    /**
     * One log line describing a single machine-data frame: the characteristic, its byte length, the raw
     * flags word, and which surfaced fields the decode populated versus left empty.
     *
     * Returns null when there is nothing attributable to say — an unknown characteristic, or a frame too
     * short to read its own flags field.
     */
    fun frameSummary(uuid16: String, data: ByteArray): String? {
        val kind = kindForUuid16(uuid16) ?: return null
        val flags = flags(uuid16, data) ?: return null
        val reading = FitnessMachine.decode(uuid16, data) ?: return null
        val width = flagsWidth(kind)
        val hex = String.format("0x%0${width * 2}X", flags)
        val present = mutableListOf<String>()
        val empty = mutableListOf<String>()
        for ((label, carried) in fieldPresence(reading)) {
            if (carried) present.add(label) else empty.add(label)
        }
        val presentPart = if (present.isEmpty()) "no fields decoded" else "decoded: " + present.joinToString(", ")
        val emptyPart = if (empty.isEmpty()) "" else " | not in frame: " + empty.joinToString(", ")
        return "${kind.uuid16} ${data.size}B flags=$hex $presentPart$emptyPart"
    }

    /**
     * The fields NOOP surfaces, in a fixed order, each paired with whether this reading carried it. Fixed
     * order so two log lines (or the two platforms) are directly comparable.
     */
    internal fun fieldPresence(reading: FitnessMachine.Reading): List<Pair<String, Boolean>> = listOf(
        "speed" to (reading.speedKmh != null),
        "cadence" to (reading.cadence != null),
        "power" to (reading.powerWatts != null),
        "distance" to (reading.distanceM != null),
        "energy" to (reading.totalEnergyKcal != null),
        "HR" to (reading.heartRate != null),
        "elapsed" to (reading.elapsedTimeSec != null),
    )

    /**
     * A GATT service/characteristic UUID rendered for a log line: the 16-bit short form ("0x1826") when the
     * UUID sits on the Bluetooth Base UUID, and otherwise the full 128-bit string UNCHANGED.
     *
     * A vendor-proprietary UUID is never abbreviated or dropped, because on a machine that serves no
     * standard fitness service the proprietary ones ARE the finding — abbreviating them to a shared prefix
     * would make two different vendor services print identically.
     */
    fun shortLabel(uuid: String): String {
        val lower = uuid.lowercase()
        if (lower.length == 36 &&
            lower.startsWith("0000") &&
            lower.endsWith("-0000-1000-8000-00805f9b34fb")
        ) {
            return "0x" + lower.substring(4, 8).uppercase()
        }
        return uuid
    }

    /** The machine kind for a 16-bit characteristic UUID short form (case-insensitive). */
    fun kindForUuid16(uuid16: String): FitnessMachine.MachineKind? = when (uuid16.uppercase()) {
        "2ACD" -> FitnessMachine.MachineKind.TREADMILL
        "2AD2" -> FitnessMachine.MachineKind.INDOOR_BIKE
        "2AD1" -> FitnessMachine.MachineKind.ROWER
        "2ACE" -> FitnessMachine.MachineKind.CROSS_TRAINER
        else -> null
    }
}
