/**
 * Listing Studio — pure building blocks (no I/O; unit-tested): which steps a listing needs, the Cloudinary
 * transformations that turn a phone video or photo into a professional, branded vertical clip, request signing,
 * and WebVTT captions from Deepgram word timings.
 */
import { createHash } from "node:crypto";

export type StudioStep = "upload" | "transcribe" | "script" | "animate" | "voiceover" | "render";
export interface StudioFlags { voiceover: boolean; runway: boolean; captions: boolean; backgroundRemoval: boolean }

/** Video listings are edited (no generative video); photo-only listings get a Runway product-motion clip. */
export function planSteps(mediaType: "video" | "photo", f: StudioFlags): StudioStep[] {
  if (mediaType === "video") return ["upload", ...(f.captions ? ["transcribe" as const] : []), "script", ...(f.voiceover ? ["voiceover" as const] : []), "render"];
  return ["upload", "script", ...(f.runway ? ["animate" as const] : []), ...(f.voiceover && f.runway ? ["voiceover" as const] : []), "render"];
}

/** Cloudinary API signature: sha1 of the sorted params (excluding file/api_key/resource_type) + secret. */
export function cloudinarySignature(params: Record<string, string | number | boolean | undefined>, secret: string): string {
  const s = Object.keys(params).filter((k) => params[k] !== undefined && params[k] !== "" && !["file", "api_key", "resource_type", "cloud_name"].includes(k)).sort()
    .map((k) => `${k}=${params[k]}`).join("&");
  return createHash("sha1").update(s + secret).digest("hex");
}

/** Text for l_text overlays: URL-encoded, with commas and slashes double-escaped as Cloudinary requires. */
export function overlayText(t: string): string {
  return encodeURIComponent(t.replace(/\s+/g, " ").trim().slice(0, 60)).replace(/%2C/gi, "%252C").replace(/%2F/gi, "%252F").replace(/[()']/g, "");
}
/** public_id "studio/abc/voice" → layer id "studio:abc:voice". */
export const layerId = (publicId: string) => publicId.replace(/\//g, ":");

export interface VideoEdit {
  headline?: string; priceText?: string; brand?: string; voicePublicId?: string | null; captionsPublicId?: string | null; maxSeconds?: number; stabilize?: boolean;
}

/**
 * Vertical 9:16 1080×1920 with smart crop, stabilisation, colour/contrast lift, trimmed to ≤30 s, fade in/out,
 * headline + price lower-third, brand tag, optional voiceover mixed over the (lowered) original sound, optional
 * burned-in captions, auto codec/quality.
 */
export function videoTransformation(e: VideoEdit): string {
  const parts: string[] = [
    "c_fill,g_auto,w_1080,h_1920", `du_${Math.min(60, Math.max(5, e.maxSeconds ?? 30))}`,
    ...(e.stabilize !== false ? ["e_deshake"] : []), "e_contrast:12", "e_saturation:12", "e_vibrance:20", "e_fade:400", "e_fade:-600",
  ];
  if (e.voicePublicId) parts.push("e_volume:-75", `l_audio:${layerId(e.voicePublicId)}`, "fl_layer_apply");
  if (e.captionsPublicId) parts.push(`l_subtitles:arial_44_bold:${layerId(e.captionsPublicId)},co_white,b_rgb:00000090,g_south,y_420`, "fl_layer_apply");
  if (e.headline) parts.push(`l_text:Arial_64_bold:${overlayText(e.headline)},co_white,b_rgb:C8102ECC,c_fit,w_960`, "fl_layer_apply,g_south,y_260");
  if (e.priceText) parts.push(`l_text:Arial_72_heavy:${overlayText(e.priceText)},co_white,b_rgb:111827DD`, "fl_layer_apply,g_south,y_140");
  parts.push(`l_text:Arial_36_bold:${overlayText(e.brand ?? "SeloNaChipa")},co_white,o_85`, "fl_layer_apply,g_north_east,x_40,y_60");
  parts.push("q_auto,vc_auto");
  return parts.join("/");
}

/** Product photo: smart square crop, auto-improve, light sharpen, optional background removal onto white. */
export function photoTransformation(o: { backgroundRemoval?: boolean; size?: number } = {}): string {
  const s = o.size ?? 1080;
  return [...(o.backgroundRemoval ? ["e_background_removal", `b_white`] : []), `c_auto,g_auto,w_${s},h_${s}`, "e_improve", "e_sharpen:60", "f_auto,q_auto"].join("/");
}

/** Poster frame for the feed card. */
export const posterTransformation = "so_auto,c_fill,g_auto,w_1080,h_1920/e_improve/f_jpg,q_auto";

export const deliveryUrl = (cloud: string, kind: "video" | "image", transformation: string, publicId: string, ext?: string) =>
  `https://res.cloudinary.com/${cloud}/${kind}/upload/${transformation}/${publicId}${ext ? `.${ext}` : ""}`;

/** Deepgram words → WebVTT cues (≈ 6 words / 3 s per line). */
export function wordsToVtt(words: { word: string; punctuated_word?: string; start: number; end: number }[]): string {
  const t = (s: number) => { const ms = Math.round(s * 1000); const h = Math.floor(ms / 3_600_000), m = Math.floor(ms / 60_000) % 60, sec = Math.floor(ms / 1000) % 60;
    return `${String(h).padStart(2, "0")}:${String(m).padStart(2, "0")}:${String(sec).padStart(2, "0")}.${String(ms % 1000).padStart(3, "0")}`; };
  const cues: string[] = [];
  for (let i = 0; i < words.length;) {
    const chunk = [words[i]];
    while (i + chunk.length < words.length && chunk.length < 6 && words[i + chunk.length].end - chunk[0].start < 3) chunk.push(words[i + chunk.length]);
    cues.push(`${t(chunk[0].start)} --> ${t(chunk[chunk.length - 1].end)}\n${chunk.map((w) => w.punctuated_word ?? w.word).join(" ")}`);
    i += chunk.length;
  }
  return `WEBVTT\n\n${cues.join("\n\n")}\n`;
}

/** The Gemini creative brief must come back in this shape; anything missing is filled safely. */
export interface StudioScript { headline: string; voiceover: string; runwayPrompt: string; hashtags: string[] }
export function cleanScript(raw: any, fallbackTitle: string): StudioScript {
  const s = (v: unknown, n: number) => String(v ?? "").replace(/\s+/g, " ").trim().slice(0, n);
  const words = s(raw?.voiceover, 400).split(" ").slice(0, 45).join(" ");
  return { headline: s(raw?.headline, 40) || fallbackTitle.slice(0, 40), voiceover: words,
    runwayPrompt: s(raw?.runwayPrompt, 400) || "Slow, smooth cinematic push-in on the product, soft studio lighting, shallow depth of field. The product stays exactly as shown. No text, no people.",
    hashtags: Array.isArray(raw?.hashtags) ? raw.hashtags.slice(0, 5).map((h: unknown) => s(h, 30).replace(/^#?/, "#")) : [] };
}
