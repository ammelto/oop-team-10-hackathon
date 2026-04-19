/*
 * Trauma EMR — Real-time receiving endpoint
 *
 * Start:
 *   node server.js
 *
 * POST initial encounter:
 *   curl -X POST http://localhost:3000/encounter \
 *     -H "Content-Type: application/json" \
 *     -d @mock_initial.json
 *
 * POST decompensation update:
 *   curl -X POST http://localhost:3000/encounter \
 *     -H "Content-Type: application/json" \
 *     -d @mock_update.json
 *
 * Health check:
 *   curl http://localhost:3000/health
 *
 * Reset demo (clears all encounters, resets frontend to State 0):
 *   curl http://localhost:3000/demo-reset
 */

const express = require('express');
const http = require('http');
const { Server } = require('socket.io');
const cors = require('cors');
const path = require('path');

const app = express();
const server = http.createServer(app);
const io = new Server(server, {
  cors: { origin: '*', methods: ['GET', 'POST'] }
});

app.use(cors());
app.use(express.json({ limit: '50mb' }));
app.use(express.static(path.join(__dirname, 'public')));

// In-memory encounter store — keyed by encounter_id
const encounters = {};

app.get('/health', (req, res) => {
  res.json({ status: 'ok' });
});

app.get('/demo-reset', (req, res) => {
  Object.keys(encounters).forEach(k => delete encounters[k]);
  console.log(`[${new Date().toISOString()}] Demo reset — all encounters cleared`);
  io.emit('demo_reset');
  res.json({ success: true, message: 'Demo reset complete' });
});

app.post('/encounter', (req, res) => {
  const payload = req.body;
  const { encounter_id, event_type } = payload;

  console.log(`\n[${new Date().toISOString()}] Incoming encounter payload`);
  console.log(JSON.stringify(payload, null, 2));

  if (!encounter_id) {
    return res.status(400).json({ error: 'encounter_id is required' });
  }

  if (encounters[encounter_id] && event_type === 'update') {
    const existing = encounters[encounter_id];

    if (payload.vitals_timeline?.length) {
      existing.vitals_timeline = [...(existing.vitals_timeline || []), ...payload.vitals_timeline];
    }
    if (payload.procedures?.length) {
      existing.procedures = [...(existing.procedures || []), ...payload.procedures];
    }
    if (payload.medications?.length) {
      existing.medications = [...(existing.medications || []), ...payload.medications];
    }
    if (payload.resuscitation_events?.length) {
      existing.resuscitation_events = [...(existing.resuscitation_events || []), ...payload.resuscitation_events];
    }
    if (payload.images?.length) {
      existing.images = [...(existing.images || []), ...payload.images];
    }

    // Scalar field updates
    const scalarFields = [
      'status', 'trauma_level', 'primary_diagnosis', 'mechanism',
      'clinical_note', 'icd10_codes', 'ais_score',
      'hospital_notification_summary', 'transcript'
    ];
    for (const field of scalarFields) {
      if (payload[field] !== undefined) existing[field] = payload[field];
    }

    encounters[encounter_id] = existing;
  } else {
    encounters[encounter_id] = { ...payload };
  }

  const receivedAt = new Date().toISOString();
  io.emit('encounter_event', { payload, receivedAt });

  res.json({ success: true });
});

io.on('connection', (socket) => {
  console.log(`[${new Date().toISOString()}] Client connected: ${socket.id}`);
  socket.on('disconnect', () => {
    console.log(`[${new Date().toISOString()}] Client disconnected: ${socket.id}`);
  });
});

const PORT = process.env.PORT || 3000;
server.listen(PORT, () => {
  console.log(`\nTrauma EMR server running at http://localhost:${PORT}\n`);
});
