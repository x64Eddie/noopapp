import Foundation
import Combine
import CoreBluetooth
import WhoopProtocol
import WhoopStore

/// An ISOLATED standard-Bluetooth source for FTMS gym equipment — a treadmill, indoor bike, rower, or
/// cross-trainer that exposes the Fitness Machine Service (0x1826) with one of the machine-data
/// characteristics (Treadmill 0x2ACD, Indoor Bike 0x2AD2, Rower 0x2AD1, Cross Trainer 0x2ACE).
///
/// WHOOP-FIRST ISOLATION (identical to `StandardHRSource`): this class runs its OWN `CBCentralManager`
/// and never imports, calls, or shares state with `BLEManager`. The WHOOP path cannot regress because of
/// anything here — the two CoreBluetooth flows are fully independent. The only shared surfaces are
/// `LiveState` (so the existing Live UI shows the machine's HR and the live-workout recorder captures it
/// through the SAME path) and the injected closures (`log`, `onBattery`). The pure FTMS field decode
/// lives in `WhoopProtocol.FTMSDecode` so it's unit-tested away from CoreBluetooth.
///
/// RECORDING: this source does NOT invent a scoring loop. It feeds live HR into `LiveState.heartRate`
/// exactly like `StandardHRSource`, so a machine session is recorded by the EXISTING manual live-workout
/// flow (`AppModel.startWorkout` / `endWorkout` → `StrainScorer` → Effort). The machine-specific metrics
/// (speed, cadence, power, distance, energy) are surfaced live via `latest` for the in-exercise UI and
/// logged; they are not run through any new scorer.
@MainActor
public final class FTMSSource: NSObject, ObservableObject {

    // MARK: - Public model

    /// A device seen during a scan.
    ///
    /// `advertisesFtms` records whether 0x1826 was in THIS advertisement, which is the only thing a scan
    /// can establish. The filtered scan sets it for everything it returns, by construction. A probe scan is
    /// unfiltered and so also surfaces devices that are not fitness machines at all, and devices that serve
    /// FTMS without advertising it — telling those two apart needs a connection, not a scan, so nothing here
    /// may be labelled a machine on scan evidence alone.
    public struct DiscoveredMachine: Identifiable, Equatable {
        public let id: UUID
        public let name: String
        public let rssi: Int
        public var advertisesFtms: Bool = true
    }

    /// Machines discovered during the current scan.
    @Published public private(set) var discovered: [DiscoveredMachine] = []
    /// True while a scan is running (UI affordance).
    @Published public private(set) var scanning: Bool = false
    /// The most recently decoded machine-data reading, for the live in-exercise readout. nil until the
    /// first notification; cleared on stop/disconnect so a stale panel can't outlive the link.
    @Published public private(set) var latest: FTMSReading?
    /// The connected machine's battery level (0x180F), 0–100, if it exposes one. nil otherwise.
    @Published public private(set) var batteryPct: Int?

    // MARK: - Standard BLE UUIDs

    private static let fitnessMachineService = CBUUID(string: "1826")
    private static let batteryService = CBUUID(string: "180F")
    private static let batteryLevel = CBUUID(string: "2A19")
    /// The four machine-data characteristics we read, mapped to their FTMS kind. We subscribe to
    /// whichever one the machine exposes.
    private static let machineCharKinds: [(CBUUID, FTMSMachineKind)] = [
        (CBUUID(string: "2ACD"), .treadmill),
        (CBUUID(string: "2AD2"), .indoorBike),
        (CBUUID(string: "2AD1"), .rower),
        (CBUUID(string: "2ACE"), .crossTrainer),
    ]
    private static let machineChars: [CBUUID] = machineCharKinds.map { $0.0 }
    /// The FTMS machine kind for a characteristic UUID (CBUUID compare), or nil if it isn't one of ours.
    private static func machineKind(for uuid: CBUUID) -> FTMSMachineKind? {
        machineCharKinds.first(where: { $0.0 == uuid })?.1
    }

    /// A GATT UUID rendered for a probe log line, through the ONE cross-platform renderer
    /// (`FTMSProbe.shortLabel`, pinned by `FTMSProbeTests` and its Kotlin twin) so the Apple and Android
    /// logs cannot disagree about how a service is spelled.
    ///
    /// `CBUUID.uuidString` already abbreviates a Bluetooth-base UUID to its 16- or 32-bit short form, which
    /// `shortLabel` cannot recognise, so the short forms are expanded back to the canonical 128-bit string
    /// before being handed over. A vendor UUID passes through untouched — on a machine that serves no
    /// standard fitness service the proprietary UUIDs ARE the finding.
    private static func label(_ uuid: CBUUID) -> String {
        let s = uuid.uuidString
        switch s.count {
        case 4:  return FTMSProbe.shortLabel("0000\(s)-0000-1000-8000-00805f9b34fb")
        case 8:  return FTMSProbe.shortLabel("\(s)-0000-1000-8000-00805f9b34fb")
        default: return FTMSProbe.shortLabel(s)
        }
    }

    /// Cap on "frame fields changed" lines per connection — evidence, not a trace.
    private static let maxFlagsChangeLines = 5

    // MARK: - Dependencies (injected — no BLEManager reference)

    private let live: LiveState
    private let log: (String) -> Void
    private let onBattery: (Int) -> Void
    /// Whether to push the machine's HR into the shared `LiveState` (true for the active machine; false
    /// for the throwaway discovery-only scanner the wizard uses).
    private let feedsLive: Bool
    /// Read live at connect: whether the per-connect PROBE readout is wanted (Test Centre Sources /
    /// Connection domain). Gates the once-per-connect GATT inventory and the first-frame field summary —
    /// readouts that fire on every healthy connection and so must not be always-on. It does NOT gate the
    /// mismatch lines (0x1826 or its data characteristic missing, flags word changed), which are rare-event
    /// evidence: they cost nothing when nothing happens and are exactly what is absent when someone reports
    /// "my treadmill shows no data" without the Test Centre enabled. Default off, wired by the two
    /// construction sites to `AppModel.ftmsProbeEnabled()` — the ONE resolver, shared with the scan breadth.
    private let probeDetail: () -> Bool

    private var loggedFirstReading = false
    /// True while the current scan is the unfiltered probe scan (changes what a sighting can claim).
    private var unfilteredScan = false
    /// The previous frame's flags word, so a CHANGE can be reported without reporting every frame.
    private var lastFlags: Int?
    /// Flags-change lines emitted this connection, capped so a machine toggling bits cannot flood the log.
    private var flagsChangeLines = 0
    /// Services whose characteristic discovery the gated inventory is still waiting on, and whether that
    /// inventory has already been printed for this connection (it is printed once, when the last service
    /// answers).
    private var pendingProbeServices = 0
    private var probeInventoryLogged = false
    /// True only between the probe's full characteristic discovery and the one inventory line it prints.
    /// Without it the counter's "nothing left to wait for" state — which is also its INITIAL state — would
    /// make the gated inventory print on an ungated connection.
    private var probeInventoryExpected = false

    // MARK: - CoreBluetooth state (OWN central, separate from WHOOP)

    private var central: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var pendingConnectID: UUID?
    private var seenPeripherals: [UUID: CBPeripheral] = [:]

    // MARK: - Init

    /// - Parameters:
    ///   - live: the shared `LiveState` the Live UI + live-workout recorder observe.
    ///   - log: connect-lifecycle diagnostics, wired to the same strap log `BLEManager` writes to.
    ///   - onBattery: fired with the machine's battery percent (0–100) when read off 0x2A19.
    ///   - feedsLive: when false (the wizard's discovery-only scanner) the source never writes `LiveState`.
    ///   - probeDetail: read live at connect — whether the gated per-connect probe readouts are wanted.
    public init(live: LiveState,
                log: @escaping (String) -> Void = { _ in },
                onBattery: @escaping (Int) -> Void = { _ in },
                feedsLive: Bool = true,
                probeDetail: @escaping () -> Bool = { false }) {
        self.live = live
        self.log = log
        self.onBattery = onBattery
        self.feedsLive = feedsLive
        self.probeDetail = probeDetail
        super.init()
        self.central = CBCentralManager(delegate: self, queue: nil)
    }

    // MARK: - Scanning

    /// Begin scanning for FTMS machines advertising the 0x1826 service.
    public func scan() { scan(unfiltered: false) }

    /// Begin scanning for gym equipment.
    ///
    /// `unfiltered` drops the 0x1826 service filter, which matters because **a machine that serves FTMS
    /// need not advertise it.** The filtered scan can only ever find equipment that puts 0x1826 in its
    /// advertisement, so a machine whose FTMS lives behind a connection — reportedly common on iFit-based
    /// treadmills (NordicTrack, ProForm), which advertise Heart Rate receive and audio instead — is
    /// invisible to it and cannot be reached at all. Probing one therefore requires seeing every nearby
    /// device and connecting to discover what it actually serves.
    ///
    /// The cost is noise: an unfiltered scan reports everything in radio range, so this is a Test Centre
    /// affordance, not the default scan. Nothing is written to any device by scanning either way.
    public func scan(unfiltered: Bool) {
        discovered.removeAll()
        seenPeripherals.removeAll()
        scanning = true
        unfilteredScan = unfiltered
        log(unfiltered
            ? "FTMS: probe scan — every nearby BLE device, no 0x1826 advertisement filter"
            : "FTMS: scanning for gym equipment (0x1826)…")
        guard central.state == .poweredOn else {
            log("FTMS: Bluetooth not powered on (state=\(central.state.rawValue)) — scan deferred until ready")
            return
        }
        // An unfiltered scan must allow duplicates OFF for the same reason the filtered one does: the list
        // is keyed by identifier, and a repeat sighting only re-renders the same row.
        central.scanForPeripherals(withServices: unfiltered ? nil : [Self.fitnessMachineService],
                                   options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
    }

    /// Stop an in-progress scan.
    public func stopScan() {
        scanning = false
        if central.state == .poweredOn { central.stopScan() }
    }

    // MARK: - Connecting

    /// Connect to the chosen machine and start streaming its machine data.
    public func connect(_ id: UUID) {
        stopScan()
        // Before the radio is up a retrieve answers nothing even for a machine this device has been bonded
        // to for weeks, and the branch below would read that as "never seen" and arm a scan (#2433).
        // Park the intent instead: `centralManagerDidUpdateState` asks again once the answer means
        // something.
        guard central.state == .poweredOn else {
            pendingConnectID = id
            log("FTMS: connect to \(id) deferred - Bluetooth not powered on (state=\(central.state.rawValue))")
            return
        }
        let p = seenPeripherals[id] ?? central.retrievePeripherals(withIdentifiers: [id]).first
        guard let p else {
            pendingConnectID = id
            log("FTMS: machine \(id) not cached yet — scanning to find it")
            scan()
            return
        }
        seenPeripherals[id] = p
        peripheral = p
        p.delegate = self
        log("FTMS: connecting to \(id)")
        central.connect(p, options: nil)
    }

    /// Tear down: cancel the connection and stop scanning. Idempotent.
    public func stop() {
        stopScan()
        pendingConnectID = nil
        if let p = peripheral {
            central.cancelPeripheralConnection(p)
        }
        peripheral = nil
        resetPerConnectionProbeState()
        latest = nil
        batteryPct = nil
        if feedsLive { live.connected = false }
    }

    /// Clear everything scoped to ONE connection, so the next connection's log describes the next
    /// connection. A stale `loggedFirstReading` would suppress the new link's first-frame line, and a stale
    /// `lastFlags` would report a field change that was really just a different machine.
    private func resetPerConnectionProbeState() {
        loggedFirstReading = false
        lastFlags = nil
        flagsChangeLines = 0
        pendingProbeServices = 0
        probeInventoryLogged = false
        probeInventoryExpected = false
    }

    // MARK: - Reading handling

    /// Fold a decoded reading into the live state + published snapshot. HR (when present) rides the SAME
    /// `LiveState.heartRate` channel the WHOOP / standard-HR sources use, so the existing live-workout
    /// recorder scores it — no new scoring loop.
    private func ingest(_ reading: FTMSReading) {
        latest = reading
        guard feedsLive else { return }
        if let hr = reading.heartRate, hr >= 30, hr <= 220 {
            live.heartRate = hr
        }
        live.connected = true
    }
}

// MARK: - CBCentralManagerDelegate

extension FTMSSource: @preconcurrency CBCentralManagerDelegate {
    public func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn:
            // The retrieve is re-issued HERE rather than trusting the one that ran while the state was
            // still `.unknown`: that one answers nothing even for a bonded machine, which used to drop this
            // replay into the scan branch and leave it unreachable off-screen, where iOS throttles
            // scanning hard (#2433).
            let parked = pendingConnectID
            let held = parked.flatMap { seenPeripherals[$0] }
            let fresh = held == nil
                ? parked.flatMap { central.retrievePeripherals(withIdentifiers: [$0]).first }
                : nil
            switch PendingConnect.replay(isPending: parked != nil,
                                         isHeld: held != nil,
                                         didRetrieve: fresh != nil,
                                         isScanning: scanning) {
            case .connect:
                if let id = parked, let p = held ?? fresh {
                    pendingConnectID = nil
                    seenPeripherals[id] = p
                    peripheral = p
                    p.delegate = self
                    log("FTMS: connecting to \(id) - targeted (the radio was not up when it was asked for)")
                    central.connect(p, options: nil)
                }
            case .scan:
                // Honour the breadth the deferred scan was asked for: a probe scan parked before the radio
                // came up must not come back as the filtered scan, which cannot reach the machine it was
                // started to find.
                central.scanForPeripherals(withServices: unfilteredScan ? nil : [Self.fitnessMachineService],
                                           options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
            case .discover:
                // Nothing to connect and no scan armed, because the intent was parked before the
                // retrieve. `scan()` arms `scanning` and `didDiscover` connects the parked id on sight.
                // Named, because this whole defect was read off log lines: a line that says a
                // connect could not be resolved is only useful if it says which device.
                if let id = parked { log("FTMS: \(id) is not resolvable yet - discovering to find it") }
                scan()
            case .idle:
                break
            }
        default:
            if feedsLive { live.connected = false }
        }
    }

    public func centralManager(_ central: CBCentralManager,
                               didDiscover peripheral: CBPeripheral,
                               advertisementData: [String: Any],
                               rssi RSSI: NSNumber) {
        let id = peripheral.identifier
        let advertised = (advertisementData[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID]) ?? []
        // In FILTERED mode the scan's own service filter is the authority: the OS matched 0x1826 to return
        // this sighting at all, so the flag must not be recomputed from the advertisement dictionary — a
        // machine that advertises the UUID somewhere this dictionary does not reflect (an overflow or
        // solicited-services field) would otherwise be labelled "not advertising fitness data" by the very
        // scan that found it by its fitness advertisement. Only an unfiltered probe scan decides this
        // for itself.
        let advertisesFtms = !unfilteredScan || advertised.contains(Self.fitnessMachineService)
        let advName = advertisementData[CBAdvertisementDataLocalNameKey] as? String
        let named = advName ?? peripheral.name
        // An unfiltered probe scan sees the whole room. A nameless device cannot be identified or
        // meaningfully picked, so it is dropped rather than filling the list.
        if unfilteredScan && named == nil { return }
        let firstSight = seenPeripherals[id] == nil
        seenPeripherals[id] = peripheral
        let name = named ?? "Gym Equipment"
        if firstSight {
            // Probe evidence: what the device PUT IN ITS ADVERTISEMENT. Absence of 0x1826 here is not
            // absence of FTMS — only a connection can establish that.
            let advertises = unfilteredScan
                ? " advertises " + (advertised.isEmpty
                    ? "no service UUIDs"
                    : advertised.map { Self.label($0) }.joined(separator: ", "))
                    + (advertisesFtms ? " (includes FTMS 0x1826)" : "")
                : ""
            log("FTMS: found \(LiveState.logSafeDeviceName(name)) (\(id)) rssi \(RSSI.intValue)\(advertises)")
        }
        let machine = DiscoveredMachine(id: id, name: name, rssi: RSSI.intValue,
                                        advertisesFtms: advertisesFtms)
        if let idx = discovered.firstIndex(where: { $0.id == id }) {
            discovered[idx] = machine
        } else {
            discovered.append(machine)
        }
        if pendingConnectID == id {
            pendingConnectID = nil
            connect(id)
        }
    }

    public func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        log("FTMS: connected — discovering services")
        peripheral.delegate = self
        // Discover ALL services (nil), not just the two this source uses, matching the Android twin — which
        // has no service filter to drop. The always-on "0x1826 NOT FOUND, it serves X" line is the whole
        // diagnosis, and a discovery that asked only about 0x1826 and 0x180F can attribute nothing beyond
        // the absence of those two: it would print "no services" for a treadmill serving six of them.
        // Service discovery is a read; nothing is written to the machine either way.
        peripheral.discoverServices(nil)
    }

    public func centralManager(_ central: CBCentralManager,
                               didFailToConnect peripheral: CBPeripheral, error: Error?) {
        log("FTMS: WARNING failed to connect — \(error?.localizedDescription ?? "unknown error")")
        if feedsLive { live.connected = false }
    }

    public func centralManager(_ central: CBCentralManager,
                               didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        if let error = error {
            log("FTMS: disconnected — \(error.localizedDescription)")
        } else {
            log("FTMS: disconnected (clean)")
        }
        resetPerConnectionProbeState()
        latest = nil
        batteryPct = nil
        if feedsLive { live.connected = false }
        if self.peripheral?.identifier == peripheral.identifier { self.peripheral = nil }
    }
}

// MARK: - CBPeripheralDelegate

extension FTMSSource: @preconcurrency CBPeripheralDelegate {
    public func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        if let error = error {
            log("FTMS: WARNING service discovery failed — \(error.localizedDescription)")
            return
        }
        let services = peripheral.services ?? []
        let ftms = services.first(where: { $0.uuid == Self.fitnessMachineService })
        if ftms == nil {
            // ALWAYS-ON (a mismatch, not a per-connect readout): the device answered discovery and 0x1826
            // was not in the answer. The inventory is the whole diagnosis — it distinguishes "this is a pair
            // of headphones" from "this treadmill really does serve only Heart Rate and audio", which is the
            // reported shape of iFit-based equipment. Without it the log says only that nothing was found,
            // which is the one fact that was already obvious.
            log("FTMS: 0x1826 service NOT FOUND — no fitness machine data available from this device. "
                + "It serves: "
                + (services.isEmpty
                   ? "no services (discovery returned an empty list)"
                   : services.map { Self.label($0.uuid) }.joined(separator: ", ")))
        }
        if probeDetail() {
            // A full inventory needs every service's characteristics, so ask them all and print once the
            // last one answers (`noteProbeServiceAnswered`).
            pendingProbeServices = services.count
            probeInventoryLogged = false
            probeInventoryExpected = true
            for svc in services { peripheral.discoverCharacteristics(nil, for: svc) }
            if services.isEmpty { noteProbeServiceAnswered(peripheral) }
            return
        }
        if let ftms {
            // Discover ALL of 0x1826's characteristics rather than the four machine-data ones: the always-on
            // "it exposes none of the four" line below has to say what the service DOES expose, and a
            // filtered discovery can only report that the four it asked for are absent.
            peripheral.discoverCharacteristics(nil, for: ftms)
        }
        for svc in services where svc.uuid == Self.batteryService {
            peripheral.discoverCharacteristics([Self.batteryLevel], for: svc)
        }
    }

    /// Count one service's characteristic-discovery answer towards the GATED full GATT inventory, and print
    /// the inventory when the last one lands. Gated because it fires on every healthy connection and says
    /// nothing new when the machine works.
    private func noteProbeServiceAnswered(_ peripheral: CBPeripheral) {
        guard probeInventoryExpected else { return }
        if pendingProbeServices > 0 { pendingProbeServices -= 1 }
        guard pendingProbeServices == 0, !probeInventoryLogged else { return }
        probeInventoryLogged = true
        probeInventoryExpected = false
        let services = peripheral.services ?? []
        let inventory = services.map { svc -> String in
            let chars = svc.characteristics ?? []
            let inner = chars.isEmpty ? "none" : chars.map { Self.label($0.uuid) }.joined(separator: " ")
            return "\(Self.label(svc.uuid)) [\(inner)]"
        }.joined(separator: "; ")
        log("FTMS: probe — GATT inventory: \(inventory.isEmpty ? "no services" : inventory)")
    }

    public func peripheral(_ peripheral: CBPeripheral,
                           didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        if let error = error {
            log("FTMS: WARNING characteristic discovery failed — \(error.localizedDescription)")
            // Still counted: an inventory that waits for a service that already failed never prints.
            noteProbeServiceAnswered(peripheral)
            return
        }
        let chars = service.characteristics ?? []
        // Subscribe to whichever machine-data characteristic this machine exposes (notify-only). Enabling a
        // notification is a CCCD write — the standard, reversible GATT subscribe, undone by disconnecting;
        // nothing here writes to the machine's control point or alters its state.
        if service.uuid == Self.fitnessMachineService {
            // Judged HERE, not in `didDiscoverServices`: on Apple the characteristics of 0x1826 are not
            // known until this callback, so "the service is there but carries no machine data" is only
            // observable once it has answered.
            let found = chars.compactMap { ch in Self.machineKind(for: ch.uuid).map { ($0, ch) } }
            if found.isEmpty {
                // ALSO ALWAYS-ON, and a different fault from "0x1826 NOT FOUND": the service is there but it
                // carries none of the four machine-data characteristics, which is what a subscription-gated
                // or unimplemented FTMS looks like. Distinguishing the two decides whether the machine is
                // usable at all, so neither line may be behind a toggle.
                log("FTMS: 0x1826 FOUND but it exposes none of the four machine-data characteristics "
                    + "(2ACD/2AD2/2AD1/2ACE) — no live metrics are available. It exposes: "
                    + (chars.isEmpty
                       ? "no characteristics"
                       : chars.map { Self.label($0.uuid) }.joined(separator: ", ")))
            } else {
                log("FTMS: 0x1826 fitness machine service FOUND — enabling machine-data notifications")
                for (kind, ch) in found {
                    log("FTMS: \(kind.displayName) data characteristic found — enabling notifications")
                    peripheral.setNotifyValue(true, for: ch)
                }
            }
        }
        // Battery (0x2A19): read once, subscribe if it notifies.
        for ch in chars where ch.uuid == Self.batteryLevel {
            log("FTMS: 0x180F battery service found — reading level")
            peripheral.readValue(for: ch)
            if ch.properties.contains(.notify) { peripheral.setNotifyValue(true, for: ch) }
        }
        noteProbeServiceAnswered(peripheral)
    }

    public func peripheral(_ peripheral: CBPeripheral,
                           didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard error == nil, let value = characteristic.value else { return }
        // Battery Level.
        if characteristic.uuid == Self.batteryLevel {
            if let pct = StandardBattery.parse([UInt8](value)) {
                log("FTMS: battery \(pct)%")
                batteryPct = pct
                onBattery(pct)
            }
            return
        }
        // Machine data — map the characteristic UUID to its FTMS kind (CBUUID compare, not string
        // formatting, so we don't depend on whether the OS reports the 16-bit or 128-bit form).
        guard let kind = Self.machineKind(for: characteristic.uuid) else { return }
        let bytes = [UInt8](value)
        guard let reading = FTMSDecode.decode(uuid16: kind.characteristicUUID16, bytes) else { return }
        let flags = FTMSProbe.flags(uuid16: kind.characteristicUUID16, bytes)
        if !loggedFirstReading {
            loggedFirstReading = true
            log("FTMS: receiving \(reading.kind.displayName) data — first reading\(reading.heartRate.map { " HR \($0) bpm" } ?? "")")
            // GATED per-connect readout: which fields THIS machine's frames actually carry. The headline
            // question the probe exists to answer, but it fires once on every healthy connection.
            if probeDetail(), let summary = FTMSProbe.frameSummary(uuid16: kind.characteristicUUID16, bytes) {
                log("FTMS: probe — first frame \(summary)")
            }
        } else if flags != lastFlags, flagsChangeLines < Self.maxFlagsChangeLines {
            // ALWAYS-ON state transition: the machine changed which fields it populates mid-session. Rare,
            // so it costs nothing when nothing happens, and it is what explains a metric that appears or
            // vanishes partway through a workout. Capped in case a machine flips bits every frame.
            flagsChangeLines += 1
            if let summary = FTMSProbe.frameSummary(uuid16: kind.characteristicUUID16, bytes) {
                log("FTMS: frame fields changed \(summary)"
                    + (flagsChangeLines == Self.maxFlagsChangeLines ? " (further changes not logged)" : ""))
            }
        }
        lastFlags = flags
        ingest(reading)
    }
}
