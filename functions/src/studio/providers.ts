/**
 * Listing Studio providers (server side only; keys live in Secret Manager):
 *   Cloudinary  – storage, enhancement, editing and delivery of the production media
 *   Deepgram    – speech-to-text (nova-3) of the seller's own voice in the video → captions + copy context
 *   ElevenLabs  – voiceover from the Gemini script (eleven_multilingual_v2)
 *   Runway      – image-to-video product motion for photo-only listings
 * Gemini (creative brief) uses the existing @google/genai client in studio.ts.
 */
import { defineSecret, defineString } from "firebase-functions/params";
import { cloudinarySignature } from "./edit";

export const CLOUDINARY_URL = defineSecret("CLOUDINARY_URL");       // cloudinary://<key>:<secret>@<cloud>
export const DEEPGRAM_API_KEY = defineSecret("DEEPGRAM_API_KEY");
export const ELEVENLABS_API_KEY = defineSecret("ELEVENLABS_API_KEY");
export const RUNWAY_API_KEY = defineSecret("RUNWAY_API_KEY");
export const STUDIO_SECRETS = [CLOUDINARY_URL, DEEPGRAM_API_KEY, ELEVENLABS_API_KEY, RUNWAY_API_KEY];
const RUNWAY_MODEL = defineString("RUNWAY_MODEL", { default: "gen4.5" });
const ELEVENLABS_VOICE_ID = defineString("ELEVENLABS_VOICE_ID", { default: "JBFqnCBsd6RMkjVDRZzb" });

const v = (p: { value(): string }, env: string, def = "") => { try { return p.value() || process.env[env] || def; } catch { return process.env[env] || def; } };
let http: typeof fetch = (...a) => fetch(...a);
/** Test hook. */
export function _setStudioTransport(f: typeof fetch) { http = f; }

async function call(url: string, init: RequestInit, timeoutMs = 60_000): Promise<Response> {
  const ctl = new AbortController(); const t = setTimeout(() => ctl.abort(), timeoutMs);
  try { return await http(url, { ...init, signal: ctl.signal }); } finally { clearTimeout(t); }
}
async function json(r: Response, what: string) {
  const body = await r.json().catch(() => ({})) as any;
  if (!r.ok) throw new Error(`${what} failed (HTTP ${r.status}): ${String(body?.error?.message ?? body?.error ?? body?.detail?.message ?? body?.message ?? "").slice(0, 200)}`);
  return body;
}

/* ------------------------------------------------------------------------------------------- Cloudinary */

export function cloudinaryCreds() {
  const m = /^cloudinary:\/\/(\d+):([^@]+)@([\w-]+)/.exec(v(CLOUDINARY_URL, "CLOUDINARY_URL"));
  return m ? { apiKey: m[1], apiSecret: m[2], cloud: m[3] } : null;
}

/** Signed upload from a URL or data URI. */
export async function cloudinaryUpload(file: string, o: { publicId: string; resourceType: "image" | "video" | "raw"; overwrite?: boolean }) {
  const c = cloudinaryCreds(); if (!c) throw new Error("CLOUDINARY_URL is not configured");
  const params = { public_id: o.publicId, overwrite: o.overwrite ?? true, timestamp: Math.floor(Date.now() / 1000) };
  const form = new FormData();
  Object.entries(params).forEach(([k, val]) => form.append(k, String(val)));
  form.append("api_key", c.apiKey); form.append("signature", cloudinarySignature(params, c.apiSecret)); form.append("file", file);
  const r = await json(await call(`https://api.cloudinary.com/v1_1/${c.cloud}/${o.resourceType}/upload`, { method: "POST", body: form }, 120_000), "Cloudinary upload");
  return { publicId: String(r.public_id), secureUrl: String(r.secure_url), duration: Number(r.duration ?? 0) || null, width: r.width, height: r.height };
}

/** Pre-renders a transformation (eager, async) so the first buyer never waits for the edit. */
export async function cloudinaryEager(publicId: string, resourceType: "image" | "video", transformation: string) {
  const c = cloudinaryCreds(); if (!c) throw new Error("CLOUDINARY_URL is not configured");
  const params = { public_id: publicId, type: "upload", eager: transformation, eager_async: true, timestamp: Math.floor(Date.now() / 1000) };
  const form = new FormData();
  Object.entries(params).forEach(([k, val]) => form.append(k, String(val)));
  form.append("api_key", c.apiKey); form.append("signature", cloudinarySignature(params, c.apiSecret));
  return json(await call(`https://api.cloudinary.com/v1_1/${c.cloud}/${resourceType}/explicit`, { method: "POST", body: form }), "Cloudinary eager");
}

/* ------------------------------------------------------------------------------------------- Deepgram */

export async function transcribe(mediaUrl: string): Promise<{ transcript: string; words: { word: string; punctuated_word?: string; start: number; end: number }[]; language?: string } | null> {
  const key = v(DEEPGRAM_API_KEY, "DEEPGRAM_API_KEY"); if (!key) return null;
  const r = await json(await call("https://api.deepgram.com/v1/listen?model=nova-3&smart_format=true&punctuate=true&detect_language=true",
    { method: "POST", headers: { Authorization: `Token ${key}`, "Content-Type": "application/json" }, body: JSON.stringify({ url: mediaUrl }) }, 120_000), "Deepgram");
  const ch = r?.results?.channels?.[0]; const alt = ch?.alternatives?.[0];
  return alt ? { transcript: String(alt.transcript ?? ""), words: alt.words ?? [], language: ch?.detected_language } : null;
}

/* ------------------------------------------------------------------------------------------- ElevenLabs */

export async function voiceover(text: string): Promise<Buffer> {
  const key = v(ELEVENLABS_API_KEY, "ELEVENLABS_API_KEY"); if (!key) throw new Error("ELEVENLABS_API_KEY is not configured");
  const voice = v(ELEVENLABS_VOICE_ID, "ELEVENLABS_VOICE_ID", "JBFqnCBsd6RMkjVDRZzb");
  const r = await call(`https://api.elevenlabs.io/v1/text-to-speech/${encodeURIComponent(voice)}?output_format=mp3_44100_128`, {
    method: "POST", headers: { "xi-api-key": key, "Content-Type": "application/json", Accept: "audio/mpeg" },
    body: JSON.stringify({ text, model_id: "eleven_multilingual_v2", voice_settings: { stability: 0.5, similarity_boost: 0.75 } }) });
  if (!r.ok) await json(r, "ElevenLabs");
  return Buffer.from(await r.arrayBuffer());
}

/* ------------------------------------------------------------------------------------------- Runway */

const RUNWAY = "https://api.dev.runwayml.com/v1";
const runwayHeaders = () => ({ Authorization: `Bearer ${v(RUNWAY_API_KEY, "RUNWAYML_API_SECRET")}`, "X-Runway-Version": "2024-11-06", "Content-Type": "application/json" });

export async function runwayStart(imageUrl: string, promptText: string): Promise<string> {
  if (!v(RUNWAY_API_KEY, "RUNWAYML_API_SECRET")) throw new Error("RUNWAY_API_KEY is not configured");
  const r = await json(await call(`${RUNWAY}/image_to_video`, { method: "POST", headers: runwayHeaders(),
    body: JSON.stringify({ model: v(RUNWAY_MODEL, "RUNWAY_MODEL", "gen4.5"), promptImage: imageUrl, promptText: promptText.slice(0, 900), ratio: "720:1280", duration: 5 }) }), "Runway");
  return String(r.id);
}

export async function runwayTask(id: string): Promise<{ status: string; output?: string[]; failure?: string }> {
  const r = await json(await call(`${RUNWAY}/tasks/${encodeURIComponent(id)}`, { method: "GET", headers: runwayHeaders() }), "Runway task");
  return { status: String(r.status), output: r.output, failure: r.failure ?? r.failureCode };
}
