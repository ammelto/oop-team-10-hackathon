# Wearables Module

Streams vitals from any wearable that writes to Android Health Connect —
covering **Samsung Galaxy Watch** (via Samsung Health) and **Oura Ring**
(via the Oura app) out of the box — and exposes a single in-process API for
the rest of the app to consume.

Everything works offline. No cell service required.

---

## 1. The 10-second version

```kotlin
val wearables = WearablesServiceLocator.repository(appContext)

// live stream
viewModelScope.launch {
    wearables.vitals.collect { sample ->
        // new VitalSample landed (HR, HRV, SpO2, BP, temp, resp rate, …)
    }
}

// windowed query for the LLM prompt
val now = Instant.now()
val recent = wearables.window(
    from  = now.minusSeconds(60),
    to    = now,
    types = setOf(VitalType.HeartRate, VitalType.HrvRmssd, VitalType.OxygenSaturation),
)
```

That's the whole API. Everything below is detail.

---

## 2. Typical LLM usage: fuse vitals into the note prompt

```kotlin
import com.example.oop.wearables.WearablesServiceLocator
import com.example.oop.wearables.data.VitalSummaries.summarizeForPrompt
import com.example.oop.wearables.model.VitalType
import java.time.Instant

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val wearables  = WearablesServiceLocator.repository(appContext)

    private suspend fun buildFusedPrompt(transcript: String): String {
        val now = Instant.now()
        val samples = wearables.window(
            from  = now.minusSeconds(60),
            to    = now,
            types = setOf(
                VitalType.HeartRate,
                VitalType.HrvRmssd,
                VitalType.OxygenSaturation,
                VitalType.BloodPressureSystolic,
                VitalType.BloodPressureDiastolic,
                VitalType.BodyTemperature,
                VitalType.SkinTemperature,
                VitalType.RespiratoryRate,
            ),
        )
        val summary = samples.summarizeForPrompt(now)
        return buildString {
            appendLine("Recent vitals (last 60 s):")
            appendLine(summary)
            appendLine()
            appendLine("Transcript:")
            append(transcript)
        }
    }
}
```

`summarizeForPrompt()` returns a compact, LLM-friendly block like:

```
hr            : 72 bpm (min 68, max 79, mean 72, n=58, 2s ago, Galaxy Watch7)
hrv_rmssd     : 42 ms (min 38, max 46, mean 42, n=2, 45s ago, Oura Ring Gen3)
spo2          : 98 % (min 97, max 99, mean 98, n=6, 8s ago, Galaxy Watch7)
bp_diastolic  : 78 mmHg (…)
bp_systolic   : 118 mmHg (…)
body_temp     : 36.7 celsius (…)
resp_rate     : 14 breaths_per_min (…)
```

Drop it directly into your prompt template. If you want a smaller footprint,
call `VitalSummaries.summarize(samples)` and format yourself.

---

## 3. Starting and stopping

Streaming is **off by default** on app launch (no unnecessary battery drain).
Start from code or from the Wearables status screen:

```kotlin
wearables.startStreaming()   // starts foreground service + enabled sources
wearables.stopStreaming()    // stops both
```

Both are idempotent; safe to call multiple times. Observe
`wearables.isStreaming: StateFlow<Boolean>` to keep UI in sync.

Once `startStreaming()` is called the foreground service holds an ongoing
notification ("Streaming vitals · HR 72 bpm · SpO2 98%") with a Stop action
that also halts ingestion cleanly.

---

## 4. Live reactions (SharedFlow)

```kotlin
viewModelScope.launch {
    wearables.vitals.collect { sample ->
        if (sample.type == VitalType.OxygenSaturation && sample.value < 92.0) {
            alert("Low SpO₂ reading: ${sample.value.toInt()}%")
        }
    }
}
```

- Hot SharedFlow, `replay = 0`, `extraBufferCapacity = 256`, `DROP_OLDEST`.
- Every active collector gets every new sample while collecting.
- Samples that fired before you subscribed are only available via
  `window(...)`.

---

## 5. Latest snapshot (non-suspending)

For a UI that just wants "what's the last HR we saw?" use the in-memory
cache — instant, no coroutines needed:

```kotlin
val latestHr: VitalSample? = wearables.latest(VitalType.HeartRate)
val everything: Map<VitalType, VitalSample> = wearables.latestSnapshot()
```

The cache is updated on every ingested sample.

---

## 6. Permissions

Health Connect permissions are runtime-granted. Check current state:

```kotlin
val missing = wearables.missingHealthConnectPermissions()   // Set<String>
```

If non-empty, launch the standard Health Connect contract:

```kotlin
import androidx.health.connect.client.PermissionController

val launcher = rememberLauncherForActivityResult(
    contract = PermissionController.createRequestPermissionResultContract(),
) { granted -> /* refresh UI / re-init source */ }

launcher.launch(missing)
```

The `WearablesScreen` composable already wires this for you. Consumers can
also just route the user to that screen.

---

## 7. What's inside a VitalSample

```kotlin
data class VitalSample(
    val id: String,                        // deterministic SHA-256 dedup key
    val timestamp: Instant,                // UTC
    val type: VitalType,                   // sealed: HR, HRV, SpO2, BP_SYS, BP_DIA, BodyTemp, SkinTemp, RespRate
    val value: Double,                     // unit is `type.unit`
    val source: WearableSource,            // HEALTH_CONNECT | SAMSUNG_HEALTH | MOCK
    val device: String?,                   // friendly label: "Galaxy Watch7", "Oura Ring Gen3"
    val providerRecordId: String?,         // source-native record id, when known
    val metadata: Map<String, String>,     // origin_package, measurement mode, quality hints, …
)
```

Units are normalized on read — no imperial/metric surprises:

| VitalType                | Unit               |
|--------------------------|--------------------|
| HeartRate                | bpm                |
| HrvRmssd                 | milliseconds       |
| OxygenSaturation         | percent            |
| BloodPressureSystolic    | mmHg               |
| BloodPressureDiastolic   | mmHg               |
| BodyTemperature          | °C                 |
| SkinTemperature          | °C                 |
| RespiratoryRate          | breaths/min        |

---

## 8. Sources and how they map to devices

| WearableSource     | Covers                                                                                                         | Phase 1? |
|--------------------|----------------------------------------------------------------------------------------------------------------|----------|
| `HEALTH_CONNECT`   | Anything any app writes to Health Connect locally. This is the path for both **Samsung Galaxy Watch** (via Samsung Health) and **Oura Ring** (via the Oura app). Fitbit, Whoop, Garmin via their apps also flow through here. | ✅ |
| `SAMSUNG_HEALTH`   | Samsung-specific fields not exposed via Health Connect (BP calibration metadata, high-frequency HR, etc.). Phase-2 aar drop-in. | ⏳ Stub |
| `MOCK`             | Deterministic simulated stream for demo / development. Clearly tagged `source = MOCK`, `device = "Simulated"`. | ✅ |

Each sample's `device` field tells you which physical device emitted it:

- Samsung Galaxy Watch family → `"Galaxy Watch4"`, `"Galaxy Watch5"`, …
- Oura Ring → `"Oura Ring Gen3"`, `"Oura Ring Gen4"`
- Package fallback → friendly label from `packageLabels` in
  `HealthConnectMappers.kt`, or the raw package name as last resort.

Filter on `device` or `source` if you want to prefer one physical device
over another (or strip `source = MOCK` out of a production prompt):

```kotlin
val clinicalOnly = recent.filter { it.source != WearableSource.MOCK }
```

---

## 9. Offline behavior

- **Live samples**: require only that the wearable and its phone app are in
  Bluetooth range of the phone. No internet required. Samsung Health and
  the Oura app both sync the wearable over BLE and write to Health
  Connect locally; we read locally. Cell service never enters the picture.
- **Windowed queries**: served entirely from Room on the phone (see
  `wearables.db`). Always work.
- **Persistence window**: 7 days by default. Pruned once daily by
  `RetentionJob` on WorkManager. Tune `WearablesConfig.retentionWindow`.
- **Change-token recovery**: if the phone is offline long enough that the
  Health Connect changes token expires, `HealthConnectSource` rebootstraps
  the last 60 s of records and picks up with a fresh token. You don't have
  to do anything.

---

## 10. Adding a new source (e.g. Wear OS companion, BLE GATT)

One new file. Implement `WearableDataSource`:

```kotlin
class WearOsCompanionSource(private val appContext: Context) : WearableDataSource {
    override val id = WearableSource.WEAR_OS
    private val _status = MutableStateFlow<SourceStatus>(SourceStatus.Uninitialized)
    override val status: StateFlow<SourceStatus> = _status.asStateFlow()

    override suspend fun initialize(context: Context) { _status.value = SourceStatus.Ready }

    override fun vitals(): Flow<VitalSample> = channelFlow {
        // bind to Wearable Data Layer, decode, map, send(sample)
    }

    override suspend fun close() { _status.value = SourceStatus.Uninitialized }
}
```

Register it in `WearablesServiceLocator.build()` (add to the `sources` list,
and to `initialEnabled` if you want it on by default). No other file
changes.

---

## 11. Troubleshooting

- **"Health Connect not installed"** — on Android 13 and below, Health
  Connect is a separate Play Store app. Install it, then pair Samsung
  Health / Oura inside it. On Android 14+, it's part of the OS.
- **"Permissions required"** — open Wearables screen → Grant. This opens
  the standard Health Connect permission dialog with our 7 requested
  permissions.
- **"No live samples"** — ensure the wearable is on your wrist/finger and
  its phone app is running. Samsung Health only writes HR during active
  workouts **unless** you enable continuous HR in Samsung Health →
  Settings → Heart rate → Measure continuously. Oura writes regularly
  without configuration.
- **"Foreground service notification won't dismiss"** — tap Stop on the
  notification, or toggle streaming off on the Wearables screen. The
  service stops itself cleanly.
- **No data older than ~30 days** — Health Connect enforces a 30-day
  historical read window for apps by default.
- **Mock samples polluting the LLM prompt** — filter on `source` before
  summarizing: `recent.filter { it.source != WearableSource.MOCK }`.

---

## 12. File map

```
wearables/
├── data/               — repository, Room DB, DAO, retention, summaries
│   ├── WearableRepository.kt        // public interface + impl
│   ├── WearableDatabase.kt          // Room DB singleton
│   ├── VitalEntity.kt / VitalDao.kt // persistence
│   ├── VitalMapping.kt              // entity <-> domain
│   ├── VitalSummaries.kt            // summarizeForPrompt() helper
│   ├── IdMinting.kt                 // deterministic SHA-256 dedup id
│   └── RetentionJob.kt              // WorkManager periodic prune
├── source/             — WearableDataSource + per-source impls
│   ├── WearableDataSource.kt        // interface
│   ├── HealthConnectSource.kt       // Phase 1 primary
│   ├── HealthConnectMappers.kt      // HC record -> VitalSample
│   ├── SamsungHealthSource.kt       // Phase 2 stub
│   └── MockSource.kt                // simulated stream
├── service/            — foreground streaming service + notification
│   ├── WearableStreamingService.kt
│   └── StreamingNotification.kt
├── model/              — VitalSample, VitalType, WearableSource, SourceStatus
├── mvi/                — WearablesIntent / WearablesUiState / WearablesEffect
├── ui/                 — Compose status screen (WearablesScreen + tiles)
├── WearablesServiceLocator.kt  — singleton factory (manual DI seam)
├── WearablesViewModel.kt       — AndroidViewModel for the status screen
├── WearablesConfig.kt          — tunable constants
└── README.md                   — this file
```

---

## 13. Why not the Open Wearables SDK?

The hackathon-provided Open Wearables Android SDK is a one-way cloud sync
client — it reads Samsung Health + Health Connect on-device and pushes
data **up** to the OW server. It exposes no realtime read Flow back into
our app. Since we have offline-first and on-device-LLM requirements, we
read the same stores ourselves directly and skip the round trip. The
`WearableDataSource` abstraction leaves the door open to wire an OW-backed
historical-windows source later if we want their multi-provider
(Garmin/Whoop/Fitbit) coverage as a supplemental historical archive.

See the planning document at
`~/.claude/plans/this-is-an-android-distributed-donut.md` for the full
decision log.
