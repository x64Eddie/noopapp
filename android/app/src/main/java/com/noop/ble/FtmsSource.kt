package com.noop.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * An ISOLATED standard-Bluetooth source for FTMS gym equipment — a treadmill, indoor bike, rower, or
 * cross-trainer exposing the Fitness Machine Service (0x1826) with one of the machine-data
 * characteristics (Treadmill 0x2ACD, Indoor Bike 0x2AD2, Rower 0x2AD1, Cross Trainer 0x2ACE).
 *
 * Faithful Kotlin twin of Strand/BLE/FTMSSource.swift. WHOOP-FIRST ISOLATION (identical to
 * [StandardHrSource]): own scan + [BluetoothGatt], never touches [WhoopBleClient]. The shared surfaces
 * are injected closures: [liveSink] (machine HR → the live UI + the existing live-workout recorder),
 * [onBattery] (the machine's 0x180F battery), [log] (the exportable strap log). The pure FTMS field
 * decode lives in [FitnessMachine] so it's JVM-unit-tested away from android.bluetooth.
 *
 * RECORDING: no new scoring loop. HR (when the machine reports it) rides [liveSink] exactly like
 * [StandardHrSource], so a machine session is recorded by the EXISTING manual live-workout flow
 * ([AppViewModel.startWorkout]/[endWorkout] → StrainScorer → Effort). The machine metrics (speed,
 * cadence, power, distance, energy) are surfaced live via [latest] and logged.
 *
 * Android runtime-permission notes (same contract as [WhoopBleClient]/[StandardHrSource]): the caller
 * must hold BLUETOOTH_SCAN + BLUETOOTH_CONNECT before [scan]/[connect].
 */
@SuppressLint("MissingPermission")
class FtmsSource(
    context: Context,
    /** Push the machine's live HR (bpm) into whatever the UI / recorder observe. Called on the main looper. */
    private val liveSink: (hr: Int) -> Unit,
    /** Push the machine's latest decoded reading into the in-exercise UI. Called on the main looper. */
    private val readingSink: (FitnessMachine.Reading) -> Unit = {},
    /** Fired with the machine's battery percent (0–100) when read off 0x2A19. */
    private val onBattery: (Int) -> Unit = {},
    /** Diagnostic sink for the connect lifecycle — the SAME exportable strap log (issue #421). Every line
     *  is prefixed "FTMS: " so it's distinguishable in the shared log. Default no-op keeps tests silent. */
    private val log: (String) -> Unit = {},
    /** Read live at connect: whether the per-connect PROBE readout is wanted (Test Centre Sources /
     *  Connection domain). Gates the once-per-connect GATT inventory and the first-frame field summary —
     *  readouts that fire on every healthy connection and so must not be always-on. It does NOT gate the
     *  mismatch lines (0x1826 or its data characteristic missing, flags word changed), which are rare-event
     *  evidence: they cost nothing when nothing happens and are exactly what is absent when someone reports
     *  "my treadmill shows no data" without the Test Centre enabled. Default off. */
    private val probeDetail: () -> Boolean = { false },
) : LiveHrSource {

    /**
     * A device seen during a scan (UI affordance).
     *
     * [advertisesFtms] records whether 0x1826 was in THIS advertisement, which is the only thing a scan can
     * establish. The filtered scan sets it for everything it returns, by construction. A probe scan is
     * unfiltered and so also surfaces devices that are not fitness machines at all, and devices that serve
     * FTMS without advertising it — telling those two apart needs a connection, not a scan, so nothing here
     * may be labelled a machine on scan evidence alone.
     */
    data class DiscoveredMachine(
        val address: String,
        val name: String,
        val rssi: Int,
        val advertisesFtms: Boolean = true,
    )

    private val _discovered = MutableStateFlow<List<DiscoveredMachine>>(emptyList())
    val discovered: StateFlow<List<DiscoveredMachine>> = _discovered.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _latest = MutableStateFlow<FitnessMachine.Reading?>(null)
    /** The most recently decoded machine-data reading, for the live in-exercise readout; null until the
     *  first notification and after disconnect (a stale panel must not outlive the link). */
    val latest: StateFlow<FitnessMachine.Reading?> = _latest.asStateFlow()

    private val _batteryPct = MutableStateFlow<Int?>(null)
    /** The connected machine's 0x180F battery level, 0–100, if exposed; null otherwise / after disconnect. */
    val batteryPct: StateFlow<Int?> = _batteryPct.asStateFlow()

    // MARK: - Android Bluetooth handles (OWN scanner + GATT, separate from WHOOP)

    private val appContext = context.applicationContext
    private val bluetoothManager: BluetoothManager? =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val scanner: BluetoothLeScanner? get() = adapter?.bluetoothLeScanner

    private var gatt: BluetoothGatt? = null
    private val seen = ConcurrentHashMap<String, BluetoothDevice>()
    private var pendingConnectAddress: String? = null
    private var lastDevice: BluetoothDevice? = null
    private var retried133 = false
    private var loggedFirstReading = false
    /** True while the current scan is the unfiltered probe scan (changes what a sighting can claim). */
    private var unfilteredScan = false
    /** The previous frame's flags word, so a CHANGE can be reported without reporting every frame. */
    private var lastFlags: Int? = null
    /** Flags-change lines emitted this connection, capped so a machine toggling bits cannot flood the log. */
    private var flagsChangeLines = 0

    private val handler = Handler(Looper.getMainLooper())

    // MARK: - Scanning

    /** Begin scanning for FTMS machines advertising the 0x1826 service. */
    override fun scan() = scan(unfiltered = false)

    /**
     * Begin scanning for gym equipment.
     *
     * [unfiltered] drops the 0x1826 [ScanFilter], which matters because **a machine that serves FTMS need
     * not advertise it.** The filtered scan can only ever find equipment that puts 0x1826 in its
     * advertisement, so a machine whose FTMS lives behind a connection — reportedly common on iFit-based
     * treadmills (NordicTrack, ProForm), which advertise Heart Rate receive and audio instead — is
     * invisible to it and cannot be reached at all. Probing one therefore requires seeing every nearby
     * device and connecting to discover what it actually serves.
     *
     * The cost is noise: an unfiltered scan reports everything in radio range, so this is a Test Centre
     * affordance, not the default scan. Nothing is written to any device by scanning either way.
     */
    fun scan(unfiltered: Boolean) {
        seen.clear()
        _discovered.value = emptyList()
        _scanning.value = true
        unfilteredScan = unfiltered
        log(
            if (unfiltered) "FTMS: probe scan — every nearby BLE device, no 0x1826 advertisement filter"
            else "FTMS: scanning for gym equipment (0x1826)…"
        )
        val sc = scanner ?: run {
            _scanning.value = false
            log("FTMS: no BLE scanner available — Bluetooth may be off or unsupported")
            return
        }
        if (adapter?.isEnabled != true) {
            _scanning.value = false
            log("FTMS: Bluetooth adapter is off — cannot scan")
            return
        }
        val filters =
            if (unfiltered) emptyList()
            else listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(FITNESS_MACHINE_SERVICE)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        sc.startScan(filters, settings, scanCallback)
    }

    /** Stop an in-progress scan. Idempotent. */
    fun stopScan() {
        _scanning.value = false
        if (adapter?.isEnabled == true) runCatching { scanner?.stopScan(scanCallback) }
    }

    // MARK: - Connecting

    /** Connect to the chosen machine (by address) and start streaming its machine data. */
    override fun connect(address: String) {
        stopScan()
        val device = seen[address] ?: runCatching { adapter?.getRemoteDevice(address) }.getOrNull()
        if (device == null) { pendingConnectAddress = address; return }
        connectToDevice(device)
    }

    private fun connectToDevice(device: BluetoothDevice) {
        lastDevice = device
        log("FTMS: connecting to ${device.address}")
        gatt?.let { runCatching { it.disconnect(); it.close() } }
        gatt = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                @Suppress("DEPRECATION")
                device.connectGatt(appContext, false, gattCallback)
            }
        }.getOrElse {
            log("FTMS: connectGatt failed (${it.javaClass.simpleName}: ${it.message})")
            null
        }
    }

    /** Tear down: cancel the connection and stop scanning. Idempotent. */
    override fun stop() {
        stopScan()
        pendingConnectAddress = null
        gatt?.let { runCatching { it.disconnect(); it.close() } }
        gatt = null
        resetPerConnectionProbeState()
        _latest.value = null
        _batteryPct.value = null
    }

    // MARK: - Scan callback

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val address = device.address ?: return
            val advertised = result.scanRecord?.serviceUuids?.map { it.uuid } ?: emptyList()
            // In FILTERED mode the ScanFilter is the authority: the OS matched 0x1826 to return this result
            // at all, so the flag must not be recomputed from `serviceUuids` — a device that advertises the
            // UUID in an AD field the parsed list does not reflect would otherwise be labelled "not
            // advertising fitness data" by the very scan that found it by its fitness advertisement.
            // Only an unfiltered probe scan has to decide this for itself.
            val advertisesFtms = !unfilteredScan || advertised.contains(FITNESS_MACHINE_SERVICE)
            val named = result.scanRecord?.deviceName ?: runCatching { device.name }.getOrNull()
            // An unfiltered probe scan sees the whole room. A nameless device cannot be identified or
            // meaningfully picked, so it is dropped rather than filling the list.
            if (unfilteredScan && named == null) return
            val firstSight = seen.put(address, device) == null
            val name = named ?: "Gym Equipment"
            if (firstSight) {
                log(
                    "FTMS: found ${logSafeDeviceName(name)} ($address) rssi ${result.rssi}" +
                        if (unfilteredScan) {
                            // Probe evidence: what the device PUT IN ITS ADVERTISEMENT. Absence of 0x1826
                            // here is not absence of FTMS — only a connection can establish that.
                            " advertises " + (
                                if (advertised.isEmpty()) "no service UUIDs"
                                else advertised.joinToString(", ") {
                                    FitnessMachineProbe.shortLabel(it.toString())
                                }
                                ) + if (advertisesFtms) " (includes FTMS 0x1826)" else ""
                        } else ""
                )
            }
            val machine = DiscoveredMachine(
                address = address, name = name, rssi = result.rssi, advertisesFtms = advertisesFtms,
            )
            val list = _discovered.value.toMutableList()
            val i = list.indexOfFirst { it.address == address }
            if (i >= 0) list[i] = machine else list.add(machine)
            _discovered.value = list
            if (pendingConnectAddress == address) {
                pendingConnectAddress = null
                handler.post { connectToDevice(device) }
            }
        }
    }

    // MARK: - GATT callback

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) = guarded("connection-state") {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    retried133 = false
                    log("FTMS: connected (status=$status) — discovering services")
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    log("FTMS: disconnected (status=$status)")
                    resetPerConnectionProbeState()
                    _latest.value = null
                    _batteryPct.value = null
                    if (gatt === g) { runCatching { g.close() }; gatt = null }
                    if (status == GATT_ERROR_133) {
                        val device = lastDevice
                        if (!retried133 && device != null) {
                            retried133 = true
                            log("FTMS: connect error 133 — retrying once in 1s")
                            handler.postDelayed({ connectToDevice(device) }, 1000)
                        } else {
                            log("FTMS: still failing (133) — try forgetting the machine in Android " +
                                "Settings → Bluetooth, then re-pair.")
                        }
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = guarded("services-discovered") {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("FTMS: WARNING service discovery failed (status=$status) — giving up on this machine")
                return@guarded
            }
            val services = g.services ?: emptyList()
            val svc = g.getService(FITNESS_MACHINE_SERVICE)
            if (svc == null) {
                // ALWAYS-ON (a mismatch, not a per-connect readout): the device answered discovery and
                // 0x1826 was not in the answer. The inventory is the whole diagnosis — it distinguishes
                // "this is a pair of headphones" from "this treadmill really does serve only Heart Rate
                // and audio", which is the reported shape of iFit-based equipment. Without it the log says
                // only that nothing was found, which is the one fact that was already obvious.
                log(
                    "FTMS: 0x1826 service NOT FOUND — no fitness machine data available from this device. " +
                        "It serves: " + (
                        if (services.isEmpty()) "no services (discovery returned an empty list)"
                        else services.joinToString(", ") { FitnessMachineProbe.shortLabel(it.uuid.toString()) }
                        )
                )
                return@guarded
            }
            // Subscribe to whichever machine-data characteristic this machine exposes. Enabling a
            // notification is a CCCD write — the standard, reversible GATT subscribe, undone by
            // disconnecting; nothing here writes to the machine's control point or alters its state.
            val found = MACHINE_CHARS.mapNotNull { (uuid, kind) ->
                svc.getCharacteristic(uuid)?.let { kind to it }
            }
            if (found.isEmpty()) {
                // ALSO ALWAYS-ON, and a different fault from the one above: the service is there but it
                // carries none of the four machine-data characteristics, which is what a subscription-gated
                // or unimplemented FTMS looks like. Distinguishing the two decides whether the machine is
                // usable at all, so neither line may be behind a toggle.
                log(
                    "FTMS: 0x1826 FOUND but it exposes none of the four machine-data characteristics " +
                        "(2ACD/2AD2/2AD1/2ACE) — no live metrics are available. It exposes: " + (
                        svc.characteristics?.takeIf { it.isNotEmpty() }
                            ?.joinToString(", ") { FitnessMachineProbe.shortLabel(it.uuid.toString()) }
                            ?: "no characteristics"
                        )
                )
            } else {
                log("FTMS: 0x1826 fitness machine service FOUND — enabling machine-data notifications")
                for ((kind, ch) in found) {
                    log("FTMS: ${kind.displayName} data characteristic found — enabling notifications")
                    enableNotify(g, ch)
                }
            }
            if (probeDetail()) {
                // GATED: fires on every healthy connection and says nothing new when the machine works.
                log(
                    "FTMS: probe — GATT inventory: " + services.joinToString("; ") { s ->
                        FitnessMachineProbe.shortLabel(s.uuid.toString()) + " [" + (
                            s.characteristics?.takeIf { it.isNotEmpty() }
                                ?.joinToString(" ") { FitnessMachineProbe.shortLabel(it.uuid.toString()) }
                                ?: "none"
                            ) + "]"
                    }
                )
            }
            // Battery (0x2A19): read once if present.
            val batt = g.getService(BATTERY_SERVICE)?.getCharacteristic(BATTERY_CHAR)
            if (batt != null) {
                log("FTMS: 0x180F battery service found — reading level")
                runCatching { g.readCharacteristic(batt) }
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
        ) = handleMachine(ch.uuid, value)

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            handleMachine(ch.uuid, ch.value ?: return)
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (ch.uuid == BATTERY_CHAR && status == BluetoothGatt.GATT_SUCCESS) handleBattery(value)
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (ch.uuid == BATTERY_CHAR && status == BluetoothGatt.GATT_SUCCESS) handleBattery(ch.value ?: return)
        }
    }

    /** Enable notifications on a characteristic (setCharacteristicNotification + the explicit CCCD write). */
    private fun enableNotify(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(CCCD) ?: run {
            log("FTMS: WARNING ${ch.uuid} has no CCCD (0x2902) — cannot enable notifications")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            run {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(cccd)
            }
        }
    }

    /**
     * Clear everything scoped to ONE connection, so the next connection's log describes the next
     * connection. A stale `loggedFirstReading` would suppress the new link's first-frame line, and a stale
     * `lastFlags` would report a field change that was really just a different machine.
     */
    private fun resetPerConnectionProbeState() {
        loggedFirstReading = false
        lastFlags = null
        flagsChangeLines = 0
    }

    /**
     * Run a GATT-callback body so a throw on the binder thread can never crash the app — mirrors
     * [StandardHrSource.guardedCallback]. A misbehaving machine must degrade to "no data", never take
     * the app down; the message lands in the exportable strap log.
     */
    private fun guarded(label: String, block: () -> Unit) {
        runCatching(block).onFailure { log("FTMS: $label error (${it.javaClass.simpleName}: ${it.message})") }
    }

    private fun handleBattery(data: ByteArray) = guarded("battery-parse") {
        val pct = StandardBattery.parse(data) ?: return@guarded
        log("FTMS: battery $pct%")
        _batteryPct.value = pct
        handler.post { guarded("battery-sink") { onBattery(pct) } }
    }

    private fun handleMachine(uuid: UUID, data: ByteArray) = guarded("machine-parse") {
        val kind = MACHINE_CHARS.firstOrNull { it.first == uuid }?.second ?: return@guarded
        val reading = FitnessMachine.decode(kind.uuid16, data) ?: return@guarded
        val flags = FitnessMachineProbe.flags(kind.uuid16, data)
        if (!loggedFirstReading) {
            loggedFirstReading = true
            log("FTMS: receiving ${reading.kind.displayName} data — first reading" +
                (reading.heartRate?.let { " HR $it bpm" } ?: ""))
            // GATED per-connect readout: which fields THIS machine's frames actually carry. The headline
            // question the probe exists to answer, but it fires once on every healthy connection.
            if (probeDetail()) {
                FitnessMachineProbe.frameSummary(kind.uuid16, data)
                    ?.let { log("FTMS: probe — first frame $it") }
            }
        } else if (flags != lastFlags && flagsChangeLines < MAX_FLAGS_CHANGE_LINES) {
            // ALWAYS-ON state transition: the machine changed which fields it populates mid-session. Rare,
            // so it costs nothing when nothing happens, and it is what explains a metric that appears or
            // vanishes partway through a workout. Capped in case a machine flips bits every frame.
            flagsChangeLines += 1
            FitnessMachineProbe.frameSummary(kind.uuid16, data)?.let { summary ->
                log(
                    "FTMS: frame fields changed $summary" +
                        if (flagsChangeLines == MAX_FLAGS_CHANGE_LINES) " (further changes not logged)" else ""
                )
            }
        }
        lastFlags = flags
        handler.post {
            guarded("reading-sink") {
                _latest.value = reading
                readingSink(reading)
                reading.heartRate?.takeIf { it in 30..220 }?.let { liveSink(it) }
            }
        }
    }

    companion object {
        val FITNESS_MACHINE_SERVICE: UUID = UUID.fromString("00001826-0000-1000-8000-00805f9b34fb")
        private val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        private val BATTERY_CHAR: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** The four machine-data characteristics → their FTMS kind. */
        private val MACHINE_CHARS: List<Pair<UUID, FitnessMachine.MachineKind>> = listOf(
            UUID.fromString("00002acd-0000-1000-8000-00805f9b34fb") to FitnessMachine.MachineKind.TREADMILL,
            UUID.fromString("00002ad2-0000-1000-8000-00805f9b34fb") to FitnessMachine.MachineKind.INDOOR_BIKE,
            UUID.fromString("00002ad1-0000-1000-8000-00805f9b34fb") to FitnessMachine.MachineKind.ROWER,
            UUID.fromString("00002ace-0000-1000-8000-00805f9b34fb") to FitnessMachine.MachineKind.CROSS_TRAINER,
        )

        private const val GATT_ERROR_133 = 133

        /** Cap on "frame fields changed" lines per connection — evidence, not a trace. */
        private const val MAX_FLAGS_CHANGE_LINES = 5
    }
}
