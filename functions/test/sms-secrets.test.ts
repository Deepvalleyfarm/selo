/** Every deployed function can send SMS: transactional SMS is triggered from many functions, often indirectly. */
import { describe, expect, it } from "vitest";

describe("Beem secrets on every function", () => {
  it("each exported function declares BEEM_API_KEY and BEEM_SECRET_KEY exactly once", async () => {
    const m = await import("../src/index");
    const fns = Object.entries(m).filter(([, v]) => v && typeof v === "function" && (v as any).__endpoint) as [string, any][];
    expect(fns.length).toBeGreaterThan(150);
    const problems = fns.flatMap(([name, f]) => {
      const keys: string[] = (f.__endpoint.secretEnvironmentVariables ?? []).map((s: { key: string }) => s.key);
      const bad = !keys.includes("BEEM_API_KEY") || !keys.includes("BEEM_SECRET_KEY") || new Set(keys).size !== keys.length;
      return bad ? [`${name}: ${keys.join(",") || "(none)"}`] : [];
    });
    expect(problems).toEqual([]);
  }, 60_000);
});
