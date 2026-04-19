# Decision Log — Trauma EMR

Running log of architectural and design decisions made during development.
Updated continuously as changes are made.

---

## 2026-04-18

### D-001 — Tech stack: Node.js + Express + Socket.io
**Decision:** Use Node.js with Express for the HTTP server and Socket.io for real-time push to the browser.  
**Rationale:** Minimal setup for a hackathon demo; Socket.io handles WebSocket fallbacks automatically; no build step required.  
**Alternatives considered:** FastAPI + SSE, plain WebSocket — rejected for extra complexity or weaker ecosystem.

---

### D-002 — No database; in-memory encounter store
**Decision:** Store encounters in a plain JS object (`const encounters = {}`) keyed by `encounter_id`.  
**Rationale:** Demo-only requirement; zero ops overhead; server restart is acceptable for a hackathon.  
**Alternatives considered:** SQLite, Redis — unnecessary for a single-session demo.

---

### D-003 — Single HTML file frontend, vanilla JS
**Decision:** Entire frontend lives in `public/index.html` with no framework or build tooling.  
**Rationale:** Fastest path to a working demo; no npm build step; easy for teammates to read and modify.  
**Alternatives considered:** React, Vue — build complexity not justified for a one-page demo app.

---

### D-004 — Chart.js via CDN for vitals chart
**Decision:** Load Chart.js from jsDelivr CDN, no local bundle.  
**Rationale:** Zero config; reliable for demo on local network; CDN version cached by browser.  
**Alternatives considered:** D3.js (too low-level for a time-boxed hackathon), Recharts (requires React).

---

### D-005 — Dual Y-axis vitals chart (HR/SBP left, SpO₂ right)
**Decision:** HR and SBP share the left Y-axis (0–220), SpO₂ uses a right Y-axis (60–100%).  
**Rationale:** SpO₂ lives in a fundamentally different range (90–100%) than HR/SBP; single axis would flatten the SpO₂ line and make trends invisible.  
**Alternatives considered:** Single axis with normalization — rejected because it obscures clinical meaning.

---

### D-006 — Surgical update strategy (no full re-render on update events)
**Decision:** When `event_type: "update"` arrives, append new vitals to the chart, append new timeline items to the DOM, and update only scalar fields. Never wipe and re-render.  
**Rationale:** Preserves visual continuity during a live demo; avoids chart flicker; makes the "live" feel credible to an audience.  
**Alternatives considered:** Full re-render on each event — fast to implement but jarring for a demo.

---

### D-007 — Server merges delta payloads; client receives the original delta
**Decision:** The server merges incoming update payloads into the stored encounter. The socket event emits the *original* (delta) payload, not the merged state.  
**Rationale:** The client already knows the current state from the initial render; sending only the delta keeps the socket message small and keeps the append logic simple.  
**Alternatives considered:** Emit full merged state — would require client diff logic or a full re-render.

---

### D-008 — Web Audio API for chime (no audio files)
**Decision:** Generate chimes programmatically using the Web Audio API oscillator. Initial encounter: ascending sine tones. Critical update: descending square-wave tones.  
**Rationale:** No audio assets to bundle or serve; tones are generated on the fly; works offline on demo hardware.  
**Alternatives considered:** Pre-recorded .mp3 — requires asset management and CORS headers for local files.

---

### D-009 — Dark navy color scheme, monospace font for clinical data
**Decision:** `#070c18` background, `#dde4f0` primary text, `Courier New` monospace for all vitals and timeline data.  
**Rationale:** High contrast for a room presentation; monospace aligns numeric columns naturally; navy reads as "medical software" without being pure black.  
**Alternatives considered:** Pure black (#000) — too harsh; light theme — washes out on projectors.

---

### D-010 — Status badge color mapping
**Decision:** stable → green, borderline → amber, peri-arrest → red + flashing, post-rosc → solid red.  
**Rationale:** Matches standard clinical traffic-light conventions; flash on peri-arrest draws immediate attention without requiring the reader to parse text.

---

### D-011 — Resuscitation events panel hidden until populated
**Decision:** The resuscitation events card has `display:none` until the first resus event arrives in a payload.  
**Rationale:** Avoids an empty red card on the initial render, which would be alarming with no content.

---

### D-012 — CORS enabled globally on the Express server
**Decision:** `app.use(cors())` with `origin: '*'` on both Express and Socket.io.  
**Rationale:** Teammates need to POST from phones or laptops on the same LAN during the demo without pre-configuring allowed origins.  
**Trade-off:** Not appropriate for production; acceptable for a hackathon demo.

---

### D-013 — Event feed prepends (newest first)
**Decision:** New feed items are inserted at the top of the feed via `insertBefore(item, feed.firstChild)`.  
**Rationale:** Most recent event is always visible without scrolling, which matters when the feed is shown live to an audience.

---
