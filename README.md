# Trauma EMR — Real-time Receiving End

Prehospital AI documentation → trauma bay display. Single-page EMR that renders incoming JSON encounters in real time via Socket.io.

## Start

```bash
npm install && node server.js
```

Open **http://localhost:3000** in a browser.

## Demo flow

```bash
# 1. Send initial encounter (trauma inbound)
curl -X POST http://localhost:3000/encounter \
  -H "Content-Type: application/json" \
  -d @mock_initial.json

# 2. Send decompensation update (~30 seconds later for demo effect)
curl -X POST http://localhost:3000/encounter \
  -H "Content-Type: application/json" \
  -d @mock_update.json
```

From another machine on the same network, replace `localhost` with the host LAN IP (e.g. `192.168.1.x`).

## Endpoints

| Method | Path       | Description                         |
|--------|------------|-------------------------------------|
| POST   | /encounter | Ingest encounter payload            |
| GET    | /health    | Health check → `{ status: "ok" }`  |

## Update behavior

When a payload arrives with the same `encounter_id` and `event_type: "update"`:
- Vitals points **appended** to chart (no re-render)
- Procedures, medications, resuscitation events **appended** to timelines
- Status badge and banner **replaced**
- Event feed **prepended** with new entries
- **Critical audio chime** fires

## JSON Schema

```jsonc
{
  "encounter_id": "string",
  "timestamp": "ISO8601",
  "event_type": "initial" | "update",
  "patient": {
    "estimated_age": "string",   // e.g. "30s"
    "sex": "string",
    "mrn": "string"              // e.g. "TRX-20480418"
  },
  "mechanism": "string",
  "primary_diagnosis": "string",
  "trauma_level": 1 | 2,        // 1 = red, 2 = amber
  "status": "stable" | "borderline" | "peri-arrest" | "post-rosc",
  "vitals_timeline": [
    { "time": "T+0:00", "hr": 124, "sbp": 94, "dbp": 60, "spo2": 94, "rr": 24 }
  ],
  "procedures":          [{ "time": "string", "description": "string" }],
  "medications":         [{ "time": "string", "description": "string" }],
  "resuscitation_events":[{ "time": "string", "description": "string" }],
  "clinical_note": "string",
  "icd10_codes": ["string"],
  "ais_score": 1,
  "hospital_notification_summary": "string",
  "transcript": "string",
  "images": ["base64 or URL"]
}
```

For `event_type: "update"`, include only **delta fields** — the server merges arrays and overwrites scalars.
