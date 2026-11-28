/**
 * Listing Studio: turns what a seller (or their agent) records into a professional listing.
 *
 *  video listing → Cloudinary upload → Deepgram captions of the seller's voice → Gemini brief (headline, voiceover
 *                  script) → ElevenLabs voiceover → Cloudinary edit: vertical 9:16, stabilised, colour lift, trim,
 *                  fades, headline + price lower-third, brand tag, voiceover mixed over the lowered original, captions
 *  photo listing → Cloudinary upload + enhance (smart crop, auto-improve, sharpen) → Gemini brief → Runway
 *                  image-to-video product motion of the hero photo → voiceover → same branded edit
 *
 * One step per Firestore trigger (studioJobs/{listingId}); Runway runs asynchronously and is polled every minute.
 * Optional steps degrade gracefully (no voiceover/captions/motion) — the listing always keeps its original media.
 * Results are applied automatically (configurable) and labelled AI-enhanced; the seller can revert in one tap.
 */
import { GoogleGenAI } from "@google/genai";
import { FieldValue, Timestamp } from "firebase-admin/firestore";
import { logger } from "firebase-functions/v2";
import { onDocumentWritten } from "firebase-functions/v2/firestore";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { DB_ID, GEMINI_API_KEY, GEMINI_MODEL, db, loadConfig, SMS_SECRETS } from "../config";
import { requireUser } from "../util";
import { cleanScript, deliveryUrl, photoTransformation, planSteps, posterTransformation, videoTransformation, wordsToVtt, type StudioScript, type StudioStep } from "./edit";
import { OPEN_SECRETS } from "../open/model";
import { STUDIO_SECRETS, cloudinaryCreds, cloudinaryEager, cloudinaryUpload, runwayStart, runwayTask, transcribe, voiceover } from "./providers";

const job = (id: string) => db.doc(`studioJobs/${id}`);
const OPTIONAL: StudioStep[] = ["transcribe", "voiceover", "animate"];
const day = () => new Date().toISOString().slice(0, 10);

async function underCap(kind: "runway" | "voiceover", cap: number) {
  if (!cap) return true;
  const ref = db.doc(`studioUsage/${day()}`);
  return db.runTransaction(async (tx) => { const n = ((await tx.get(ref)).get(kind) as number | undefined) ?? 0; if (n >= cap) return false; tx.set(ref, { [kind]: n + 1 }, { merge: true }); return true; });
}

/** Creates (or restarts) the studio job for a listing. */
export interface StudioOptions {
  /** Open Network partner that asked (webhooks + usage billing). */ partnerId?: string; env?: "sandbox" | "production";
  /** Put the result on the SeloNachipa listing when done (default: config studioAutoApply). */ autoApply?: boolean;
  voiceover?: boolean;
}
export async function startStudio(listingId: string, by: string, opt: StudioOptions = {}) {
  const cfg = await loadConfig() as Record<string, any>;
  if (cfg.studioEnabled === false) return { started: false, reason: "Studio is switched off." };
  const l = (await db.doc(`listings/${listingId}`).get()).data();
  if (!l) throw new HttpsError("not-found", "Listing not found.");
  const today = await db.collection("studioJobs").where("sellerUid", "==", l.sellerUid).where("day", "==", day()).count().get();
  if (today.data().count >= Number(cfg.studioMaxJobsPerSellerPerDay ?? 5)) return { started: false, reason: "Daily studio limit reached for this shop. Try again tomorrow." };
  const mediaType: "video" | "photo" = l.videoUrl && l.mediaType === "video" ? "video" : "photo";
  if (mediaType === "photo" && !(l.photoUrls ?? []).length) return { started: false, reason: "No media to enhance." };
  if (opt.partnerId) {
    const n = await db.collection("studioJobs").where("partnerId", "==", opt.partnerId).where("day", "==", day()).count().get();
    if (n.data().count >= Number(cfg.openStudioDailyCap ?? 50)) return { started: false, reason: "Daily video limit reached for your platform. Try again tomorrow." };
  }
  const steps = planSteps(mediaType, { voiceover: cfg.studioVoiceoverEnabled !== false && opt.voiceover !== false, runway: cfg.studioRunwayEnabled !== false,
    captions: cfg.studioCaptionsEnabled !== false, backgroundRemoval: cfg.studioBackgroundRemoval === true });
  await job(listingId).set({ listingId, sellerUid: l.sellerUid, by, mediaType, steps, index: 0, status: "queued", attempts: 0, day: day(),
    title: l.title ?? "", description: String(l.description ?? "").slice(0, 600), category: l.category ?? "", priceNgwee: l.priceNgwee ?? 0, discountPct: l.discountPct ?? 0,
    sources: { video: mediaType === "video" ? l.videoUrl : null, photos: (l.photoUrls ?? []).slice(0, 4) }, out: {}, log: [], error: null,
    partnerId: opt.partnerId ?? null, env: opt.env ?? null, autoApply: opt.autoApply ?? null,
    createdAt: FieldValue.serverTimestamp(), updatedAt: FieldValue.serverTimestamp() });
  await db.doc(`listings/${listingId}`).set({ studio: { status: "processing", startedAt: FieldValue.serverTimestamp() } }, { merge: true });
  return { started: true, steps };
}

async function brief(j: any, transcript: string): Promise<StudioScript> {
  const price = `K${((j.priceNgwee ?? 0) / 100).toFixed(2)}`;
  const prompt = `You write short, honest product ads for SeloNaChipa, a Zambian marketplace. Return JSON only:
{"headline": "≤ 32 characters, no price", "voiceover": "warm spoken ad, 25–40 words, simple English, mentions the price ${price}, ends with 'Order on SeloNaChipa'",
 "runwayPrompt": "camera-motion-only prompt for an image-to-video model: subtle product motion, the product must stay exactly as photographed, no new objects, no text, no people",
 "hashtags": ["up to 5"]}
Never invent features, sizes, brands, warranties or discounts that are not in the listing.
Listing title: ${j.title}\nCategory: ${j.category}\nDescription: ${j.description}\nPrice: ${price}${j.discountPct ? ` (${j.discountPct}% off)` : ""}
${transcript ? `What the seller says in the video: "${transcript.slice(0, 1200)}"` : ""}`;
  try {
    const ai = new GoogleGenAI({ apiKey: GEMINI_API_KEY.value() });
    const r = await ai.models.generateContent({ model: GEMINI_MODEL.value(), contents: [{ role: "user", parts: [{ text: prompt }] }],
      config: { responseMimeType: "application/json", temperature: 0.6 } });
    return cleanScript(JSON.parse(r.text ?? "{}"), j.title);
  } catch (e) {
    logger.warn("studio brief fell back", String(e));
    return cleanScript({ headline: j.title, voiceover: `${j.title}. Now ${price}. Order on SeloNaChipa.` }, j.title);
  }
}

/** Runs one step; returns the output patch, or "wait" (Runway still running). */
async function runStep(id: string, j: any, step: StudioStep, cfg: Record<string, any>): Promise<Record<string, unknown> | "wait"> {
  const base = `studio/${id}`;
  switch (step) {
    case "upload": {
      const out: Record<string, unknown> = {};
      if (j.sources.video) out.videoPublicId = (await cloudinaryUpload(j.sources.video, { publicId: `${base}/source`, resourceType: "video" })).publicId;
      const photos: string[] = [];
      for (const [i, u] of (j.sources.photos as string[]).entries()) photos.push((await cloudinaryUpload(u, { publicId: `${base}/photo-${i + 1}`, resourceType: "image" })).publicId);
      out.photoPublicIds = photos;
      return out;
    }
    case "transcribe": {
      const t = await transcribe(j.sources.video);
      if (!t || t.words.length < 3) return { transcript: t?.transcript ?? "" };
      const vtt = wordsToVtt(t.words);
      const cap = await cloudinaryUpload(`data:text/vtt;base64,${Buffer.from(vtt).toString("base64")}`, { publicId: `${base}/captions.vtt`, resourceType: "raw" });
      return { transcript: t.transcript.slice(0, 2000), language: t.language ?? null, captionsPublicId: cap.publicId };
    }
    case "script": return { script: await brief(j, String(j.out.transcript ?? "")) };
    case "animate": {
      if (j.out.runwayPublicId) return {};
      const c = cloudinaryCreds()!;
      if (j.out.runwayTaskId) {
        const t = await runwayTask(j.out.runwayTaskId);
        if (t.status === "SUCCEEDED" && t.output?.[0]) return { runwayPublicId: (await cloudinaryUpload(t.output[0], { publicId: `${base}/motion`, resourceType: "video" })).publicId };
        if (t.status === "FAILED" || t.status === "CANCELLED") throw new Error(`Runway ${t.status}: ${t.failure ?? ""}`);
        return "wait";
      }
      if (!(await underCap("runway", Number(cfg.studioDailyCapRunway ?? 100)))) throw new Error("Runway daily cap reached");
      const hero = deliveryUrl(c.cloud, "image", photoTransformation({ backgroundRemoval: cfg.studioBackgroundRemoval === true, size: 1280 }).replace("f_auto,q_auto", "f_jpg,q_auto"), j.out.photoPublicIds[0]);
      await job(id).update({ "out.runwayTaskId": await runwayStart(hero, j.out.script?.runwayPrompt ?? "") });
      return "wait";
    }
    case "voiceover": {
      const text = j.out.script?.voiceover;
      if (!text || !(await underCap("voiceover", Number(cfg.studioDailyCapVoiceover ?? 500)))) return {};
      const mp3 = await voiceover(text);
      return { voicePublicId: (await cloudinaryUpload(`data:audio/mpeg;base64,${mp3.toString("base64")}`, { publicId: `${base}/voice`, resourceType: "video" })).publicId };
    }
    case "render": {
      const c = cloudinaryCreds()!;
      const photos = ((j.out.photoPublicIds ?? []) as string[]).map((p) => deliveryUrl(c.cloud, "image", photoTransformation({ backgroundRemoval: cfg.studioBackgroundRemoval === true }), p));
      const clip = j.out.videoPublicId ?? j.out.runwayPublicId;
      const out: Record<string, unknown> = { photoUrls: photos };
      if (clip) {
        const price = (j.priceNgwee ?? 0) > 0 ? `K ${((j.priceNgwee * (100 - (j.discountPct ?? 0))) / 10_000).toFixed(2)}` : undefined;
        const tr = videoTransformation({ headline: j.out.script?.headline ?? j.title, priceText: price, voicePublicId: j.out.voicePublicId ?? null,
          captionsPublicId: j.out.captionsPublicId ?? null, maxSeconds: 30, stabilize: !!j.out.videoPublicId });
        await cloudinaryEager(clip, "video", tr).catch((e) => logger.warn("eager render failed (renders on first view)", String(e)));
        out.videoUrl = deliveryUrl(c.cloud, "video", tr, clip, "mp4");
        out.posterUrl = deliveryUrl(c.cloud, "video", posterTransformation, clip, "jpg");
      }
      return out;
    }
  }
}

/** Applies the finished studio media to the listing (originals kept for revert). */
export async function applyStudio(listingId: string, apply: boolean) {
  const ref = db.doc(`listings/${listingId}`);
  await db.runTransaction(async (tx) => {
    const l = (await tx.get(ref)).data(); if (!l?.studio) return;
    const s = l.studio;
    if (apply && s.videoUrl !== undefined) {
      const original = l.originalMedia ?? { videoUrl: l.videoUrl ?? null, photoUrls: l.photoUrls ?? [], thumbUrl: l.thumbUrl ?? null, mediaType: l.mediaType };
      tx.update(ref, { originalMedia: original, videoUrl: s.videoUrl ?? original.videoUrl, mediaType: s.videoUrl ? "video" : original.mediaType,
        thumbUrl: s.posterUrl ?? (s.photoUrls?.[0] ?? original.thumbUrl), photoUrls: s.photoUrls?.length ? s.photoUrls : original.photoUrls,
        "studio.applied": true, "studio.appliedAt": FieldValue.serverTimestamp(), aiEnhanced: true, aiGeneratedMotion: !!s.generativeMotion });
    } else if (!apply && l.originalMedia) {
      tx.update(ref, { videoUrl: l.originalMedia.videoUrl, photoUrls: l.originalMedia.photoUrls, thumbUrl: l.originalMedia.thumbUrl, mediaType: l.originalMedia.mediaType,
        "studio.applied": false, aiEnhanced: false, aiGeneratedMotion: false });
    }
  });
}

async function advance(id: string) {
  const cfg = await loadConfig() as Record<string, any>;
  const claimed = await db.runTransaction(async (tx) => {
    const j = (await tx.get(job(id))).data();
    if (!j || j.status !== "queued" || j.index >= j.steps.length) return null;
    tx.update(job(id), { status: "running", leaseUntil: Timestamp.fromMillis(Date.now() + 8 * 60_000), updatedAt: FieldValue.serverTimestamp() });
    return j;
  });
  if (!claimed) return;
  const step: StudioStep = claimed.steps[claimed.index];
  try {
    const res = await runStep(id, claimed, step, cfg);
    if (res === "wait") { await job(id).update({ status: "waiting", waitingSince: claimed.waitingSince ?? FieldValue.serverTimestamp(), updatedAt: FieldValue.serverTimestamp() }); return; }
    const patch: Record<string, unknown> = Object.fromEntries(Object.entries(res).map(([k, val]) => [`out.${k}`, val]));
    const last = claimed.index + 1 >= claimed.steps.length;
    await job(id).update({ ...patch, index: claimed.index + 1, attempts: 0, status: last ? "done" : "queued", log: FieldValue.arrayUnion({ step, ok: true, at: Date.now() }),
      updatedAt: FieldValue.serverTimestamp() });
    if (last) {
      const o = { ...claimed.out, ...res } as Record<string, any>;
      await db.doc(`listings/${id}`).set({ studio: { status: "ready", videoUrl: o.videoUrl ?? null, posterUrl: o.posterUrl ?? null, photoUrls: o.photoUrls ?? [],
        headline: o.script?.headline ?? null, hashtags: o.script?.hashtags ?? [], voiceover: !!o.voicePublicId, captions: !!o.captionsPublicId,
        generativeMotion: !!o.runwayPublicId, finishedAt: FieldValue.serverTimestamp(), applied: false } }, { merge: true });
      if (claimed.autoApply ?? cfg.studioAutoApply !== false) await applyStudio(id, true);
      if (claimed.partnerId) await partnerStudioDone(id, claimed, "ready");
    }
  } catch (e) {
    const msg = String((e as Error).message ?? e).slice(0, 300);
    logger.warn("studio step failed", { id, step, msg });
    const attempts = (claimed.attempts ?? 0) + 1;
    if (attempts < 3 && !/not configured|cap reached/.test(msg)) { await job(id).update({ status: "queued", attempts, lastError: msg, updatedAt: FieldValue.serverTimestamp() }); return; }
    if (OPTIONAL.includes(step)) {
      const last = claimed.index + 1 >= claimed.steps.length;
      await job(id).update({ index: claimed.index + 1, attempts: 0, status: last ? "done" : "queued", log: FieldValue.arrayUnion({ step, ok: false, skipped: true, error: msg, at: Date.now() }),
        updatedAt: FieldValue.serverTimestamp() });
      return;
    }
    await job(id).update({ status: "failed", error: `${step}: ${msg}`, updatedAt: FieldValue.serverTimestamp() });
    await db.doc(`listings/${id}`).set({ studio: { status: "failed", error: "We couldn't produce the studio version. Your original media is live." } }, { merge: true });
    if (claimed.partnerId) await partnerStudioDone(id, claimed, "failed").catch((err) => logger.warn("partner studio webhook", String(err)));
  }
}

/** Open Network: tell the partner (webhook) and, for a finished production video, record the usage charge. */
async function partnerStudioDone(id: string, j: any, outcome: "ready" | "failed") {
  const { emit } = await import("../open/webhooks");
  const { studioView } = await import("../open/media");
  const l = (await db.doc(`listings/${id}`).get()).data() ?? {};
  const env = j.env ?? "production";
  await emit(j.partnerId, env, outcome === "ready" ? "media.video.ready" : "media.video.failed", id, { video: studioView(id, l, j) }, `${outcome}-${j.createdAt?.toMillis?.() ?? ""}`);
  if (outcome !== "ready" || env !== "production") return;
  const p = (await db.doc(`openPartners/${j.partnerId}`).get()).data();
  const price = Number(p?.pricing?.mediaVideoNgwee ?? 0);
  if (price > 0) {
    const { post } = await import("../open/settlement");
    await post(j.partnerId, { type: "MEDIA_USAGE", amountNgwee: price, refType: "media", refId: `video:${id}:${j.createdAt?.toMillis?.() ?? ""}`, env, note: "AI video" });
  }
}

export const onStudioJob = onDocumentWritten({ document: "studioJobs/{id}", database: DB_ID, secrets: [...SMS_SECRETS, ...STUDIO_SECRETS, GEMINI_API_KEY, ...OPEN_SECRETS], timeoutSeconds: 540, memory: "1GiB" }, async (e) => {
  const after = e.data?.after.data();
  if (after?.status === "queued") await advance(e.params.id);
});

/** Every minute: wake jobs waiting on Runway, and requeue jobs whose worker died mid-step. */
export const studio_poll = onSchedule({ schedule: "every 1 minutes", secrets: [...SMS_SECRETS, ...STUDIO_SECRETS, GEMINI_API_KEY], timeoutSeconds: 120 }, async () => {
  const waiting = await db.collection("studioJobs").where("status", "==", "waiting").limit(50).get();
  for (const d of waiting.docs) {
    const since = (d.get("waitingSince") as Timestamp | undefined)?.toMillis() ?? Date.now();
    if (Date.now() - since > 20 * 60_000) { await d.ref.update({ status: "queued", attempts: 3, lastError: "Runway timed out" }); continue; } // → skipped as optional
    await d.ref.update({ status: "queued", updatedAt: FieldValue.serverTimestamp() });
  }
  const stuck = await db.collection("studioJobs").where("status", "==", "running").where("leaseUntil", "<", Timestamp.now()).limit(20).get();
  for (const d of stuck.docs) await d.ref.update({ status: "queued", attempts: FieldValue.increment(1) });
});

const ownsListing = async (uid: string, role: string, listingId: string) => {
  const l = (await db.doc(`listings/${listingId}`).get()).data();
  if (!l) throw new HttpsError("not-found", "Listing not found.");
  if (l.sellerUid !== uid && !(role === "AGENT" && l.agentUid === uid)) throw new HttpsError("permission-denied", "Not your listing.");
};

export const studio_start = onCall(async (req) => {
  const { uid, role } = requireUser(req, "SELLER", "AGENT");
  const id = String(req.data?.listingId ?? ""); await ownsListing(uid, role, id);
  return startStudio(id, uid);
});
/** Use the studio version (true) or go back to the original media (false). */
export const studio_apply = onCall(async (req) => {
  const { uid, role } = requireUser(req, "SELLER", "AGENT");
  const id = String(req.data?.listingId ?? ""); await ownsListing(uid, role, id);
  await applyStudio(id, req.data?.apply !== false);
  return { ok: true };
});
