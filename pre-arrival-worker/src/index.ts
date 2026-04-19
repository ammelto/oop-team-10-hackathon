export interface Env {
  ELEVENLABS_API_KEY: string;
  ELEVENLABS_AGENT_ID: string;
  ELEVENLABS_PHONE_NUMBER_ID: string;
}

interface DispatchRequest {
  job_id: string;
  note_text: string;
  eta_minutes: number;
  er_phone: string;
}

async function triggerOutboundCall(env: Env, req: DispatchRequest): Promise<void> {
  const res = await fetch("https://api.elevenlabs.io/v1/convai/twilio/outbound-call", {
    method: "POST",
    headers: {
      "xi-api-key": env.ELEVENLABS_API_KEY,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      agent_id: env.ELEVENLABS_AGENT_ID,
      agent_phone_number_id: env.ELEVENLABS_PHONE_NUMBER_ID,
      to_number: req.er_phone,
      conversation_initiation_client_data: {
        dynamic_variables: { note: req.note_text, eta: req.eta_minutes },
      },
    }),
  });
  const text = await res.text();
  console.log(`[${req.job_id}] ElevenLabs ${res.status}: ${text}`);
}

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/") {
      return Response.json({ ok: true, service: "pre-arrival-worker", endpoint: "POST /dispatch" });
    }
    if (request.method !== "POST" || url.pathname !== "/dispatch") {
      return new Response("Not Found", { status: 404 });
    }
    const body = (await request.json().catch(() => ({}))) as Partial<DispatchRequest>;
    const { job_id, note_text, eta_minutes, er_phone } = body;
    if (
      typeof job_id !== "string" ||
      typeof note_text !== "string" ||
      typeof eta_minutes !== "number" ||
      typeof er_phone !== "string"
    ) {
      return Response.json({ ok: false, error: "missing or invalid fields" }, { status: 400 });
    }
    ctx.waitUntil(triggerOutboundCall(env, { job_id, note_text, eta_minutes, er_phone }));
    return Response.json({ ok: true, job_id }, { status: 202 });
  },
} satisfies ExportedHandler<Env>;
