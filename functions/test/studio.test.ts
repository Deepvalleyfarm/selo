import { createHash } from "node:crypto";
import { describe, expect, it } from "vitest";
import { cleanScript, cloudinarySignature, layerId, overlayText, photoTransformation, planSteps, videoTransformation, wordsToVtt } from "../src/studio/edit";
import { _setStudioTransport, cloudinaryUpload, runwayStart, transcribe, voiceover } from "../src/studio/providers";

const all = { voiceover: true, runway: true, captions: true, backgroundRemoval: false };
describe("studio plan", () => {
  it("edits videos (no generative video) and animates photo-only listings", () => {
    expect(planSteps("video", all)).toEqual(["upload", "transcribe", "script", "voiceover", "render"]);
    expect(planSteps("photo", all)).toEqual(["upload", "script", "animate", "voiceover", "render"]);
    expect(planSteps("photo", { ...all, runway: false })).toEqual(["upload", "script", "render"]);
  });
});

describe("Cloudinary edit recipe", () => {
  it("signs sorted params with the secret (sha1), skipping file/api_key", () => {
    const sig = cloudinarySignature({ timestamp: 1315060510, public_id: "sample", file: "x", api_key: "1" }, "abcd");
    expect(sig).toBe(createHash("sha1").update("public_id=sample&timestamp=1315060510abcd").digest("hex"));
  });
  it("escapes overlay text and layer ids", () => {
    expect(overlayText("Tomatoes, 5 kg / bag")).toBe("Tomatoes%252C%205%20kg%20%252F%20bag");
    expect(layerId("studio/abc/voice")).toBe("studio:abc:voice");
  });
  it("vertical branded edit with voiceover and captions", () => {
    const t = videoTransformation({ headline: "Fresh Chongwe Tomatoes", priceText: "K 85.00", voicePublicId: "studio/l1/voice", captionsPublicId: "studio/l1/captions.vtt" });
    expect(t.startsWith("c_fill,g_auto,w_1080,h_1920/du_30/e_deshake/")).toBe(true);
    expect(t).toContain("e_volume:-75/l_audio:studio:l1:voice/fl_layer_apply");
    expect(t).toContain("l_subtitles:arial_44_bold:studio:l1:captions.vtt");
    expect(t).toContain("l_text:Arial_72_heavy:K%2085.00");
    expect(t.endsWith("q_auto,vc_auto")).toBe(true);
  });
  it("photo enhancement recipe", () => {
    expect(photoTransformation()).toBe("c_auto,g_auto,w_1080,h_1080/e_improve/e_sharpen:60/f_auto,q_auto");
    expect(photoTransformation({ backgroundRemoval: true })).toMatch(/^e_background_removal\/b_white\//);
  });
});

describe("captions and script", () => {
  it("builds WebVTT cues from Deepgram words", () => {
    const vtt = wordsToVtt([{ word: "fresh", punctuated_word: "Fresh", start: 0.1, end: 0.4 }, { word: "tomatoes", start: 0.5, end: 1.0 }, { word: "today", punctuated_word: "today.", start: 4.2, end: 4.6 }]);
    expect(vtt).toBe("WEBVTT\n\n00:00:00.100 --> 00:00:01.000\nFresh tomatoes\n\n00:00:04.200 --> 00:00:04.600\ntoday.\n");
  });
  it("cleans the Gemini brief (length limits, safe fallbacks)", () => {
    const s = cleanScript({ headline: "x".repeat(80), voiceover: Array(80).fill("word").join(" "), hashtags: ["fresh", "#lusaka"] }, "Tomatoes");
    expect(s.headline).toHaveLength(40); expect(s.voiceover.split(" ")).toHaveLength(45); expect(s.hashtags).toEqual(["#fresh", "#lusaka"]);
    expect(cleanScript({}, "Tomatoes").runwayPrompt).toMatch(/stays exactly as shown/);
  });
});

describe("providers (mocked HTTP)", () => {
  const calls: { url: string; init: any }[] = [];
  const mock = (body: unknown, status = 200, raw?: ArrayBuffer) => _setStudioTransport((async (url: any, init: any) => { calls.push({ url: String(url), init });
    return raw ? new Response(raw, { status }) : new Response(JSON.stringify(body), { status }); }) as typeof fetch);
  it("Cloudinary signed upload", async () => {
    process.env.CLOUDINARY_URL = "cloudinary://111:sekret@democloud"; mock({ public_id: "studio/l1/source", secure_url: "https://res.cloudinary.com/x.mp4" });
    const r = await cloudinaryUpload("https://example.test/v.mp4", { publicId: "studio/l1/source", resourceType: "video" });
    expect(r.publicId).toBe("studio/l1/source"); expect(calls.at(-1)!.url).toBe("https://api.cloudinary.com/v1_1/democloud/video/upload");
    const f = calls.at(-1)!.init.body as FormData; expect(f.get("api_key")).toBe("111"); expect(String(f.get("signature"))).toMatch(/^[0-9a-f]{40}$/);
  });
  it("Deepgram nova-3 with Token auth", async () => {
    process.env.DEEPGRAM_API_KEY = "dg"; mock({ results: { channels: [{ detected_language: "en", alternatives: [{ transcript: "fresh tomatoes", words: [] }] }] } });
    expect((await transcribe("https://example.test/v.mp4"))?.transcript).toBe("fresh tomatoes");
    expect(calls.at(-1)!.url).toContain("model=nova-3"); expect(calls.at(-1)!.init.headers.Authorization).toBe("Token dg");
  });
  it("ElevenLabs multilingual v2", async () => {
    process.env.ELEVENLABS_API_KEY = "el"; mock(null, 200, new Uint8Array([73, 68, 51]).buffer);
    expect((await voiceover("Hello")).length).toBe(3);
    expect(calls.at(-1)!.init.headers["xi-api-key"]).toBe("el"); expect(JSON.parse(calls.at(-1)!.init.body).model_id).toBe("eleven_multilingual_v2");
  });
  it("Runway image-to-video gen4.5, vertical 5 s", async () => {
    process.env.RUNWAYML_API_SECRET = "rw"; mock({ id: "task-1" });
    expect(await runwayStart("https://img.test/p.jpg", "slow push-in")).toBe("task-1");
    const b = JSON.parse(calls.at(-1)!.init.body); expect(b).toMatchObject({ model: "gen4.5", ratio: "720:1280", duration: 5 });
    expect(calls.at(-1)!.init.headers["X-Runway-Version"]).toBe("2024-11-06");
  });
});
