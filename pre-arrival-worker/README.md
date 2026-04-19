# pre-arrival-worker

Cloudflare Worker that accepts a paramedic's note and triggers an outbound ER phone call via ElevenLabs Conversational AI. The agent (configured in the ElevenLabs dashboard) reformats the note into MIST, states ETA, reads once, says "Over", and hangs up.

**Deployed URL:** https://pre-arrival-worker.rohan-984.workers.dev

## Setup

```bash
npm install
```

### Secrets

```bash
wrangler secret put ELEVENLABS_API_KEY
wrangler secret put ELEVENLABS_AGENT_ID
```

### Run locally

```bash
wrangler dev
```

### Deploy

```bash
wrangler deploy
```

## API

### `POST /dispatch`

Request:

```json
{
  "job_id": "uuid",
  "note_text": "45M motorbike RTA, GCS 13, BP 90/60, suspected pelvic fracture, TXA given",
  "eta_minutes": 8,
  "er_phone": "+1..."
}
```

Response: `202 Accepted` with `{ "ok": true, "job_id": "..." }`. The outbound call is fired in `ctx.waitUntil`.

Missing/invalid fields → `400` with `{ "ok": false, "error": "..." }`.

### Example curl against prod

```bash
curl -X POST https://pre-arrival-worker.rohan-984.workers.dev/dispatch \
  -H "Content-Type: application/json" \
  -d '{
    "job_id": "b8d1e2a4-3f47-4a1a-9c3f-2c6a1e0a1d11",
    "note_text": "45M motorbike RTA, GCS 13, BP 90/60, suspected pelvic fracture, TXA given",
    "eta_minutes": 8,
    "er_phone": "+15551234567"
  }'
```

Logs (ElevenLabs response per job) are visible via `wrangler tail`.
