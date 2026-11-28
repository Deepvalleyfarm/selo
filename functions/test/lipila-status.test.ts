/** Lipila status lookups (GET …/check-status?referenceId=…), used to settle payments and withdrawals with no callback. */
import { afterEach, beforeEach, describe, expect, it } from "vitest";

const ENV = { LIPILA_BASE_URL: "https://blz.lipila.io", LIPILA_API_KEY: "test-key", LIPILA_CALLBACK_URL: "https://example.test/lipila_webhook",
  LIPILA_DISBURSEMENT_STATUS_PATH: "/api/v1/disbursements/check-status?referenceId={referenceId}",
  LIPILA_COLLECTION_STATUS_PATH: "/api/v1/collections/check-status?referenceId={referenceId}" };
// Example from Lipila's Disbursements Status documentation.
const documented = { referenceId: "046b3b664c35", currency: "ZMW", amount: 0.2, accountNumber: "260xxxxxxxxxx", status: "Successful", paymentType: "ZamtelKwacha",
  type: "Disbursement", ipAddress: "::1", identifier: "LPLTXNDZK-20250819104104-EF4D8905AAC1", externalId: "MP251003.1252.C0324", message: "Process service request successfully." };

let calls: { url: string; init: any }[] = [];
const reply = (status: number, body: unknown) => (async (url: string, init: any) => { calls.push({ url, init }); return { ok: status >= 200 && status < 300, status, json: async () => body } as any; }) as any;

describe("Lipila status lookup", () => {
  beforeEach(() => { calls = []; Object.assign(process.env, ENV); });
  afterEach(() => { for (const k of Object.keys(ENV)) delete process.env[k]; });

  it("checks a withdrawal by its referenceId with the documented request", async () => {
    const { lipila, _setLipilaTransport, isSuccessful } = await import("../src/lipila");
    _setLipilaTransport(reply(200, documented));
    const s = await lipila.status("disbursement", "6e807106-3803-4908-8cd4-c79d0b107404");
    expect(calls[0].url).toBe("https://blz.lipila.io/api/v1/disbursements/check-status?referenceId=6e807106-3803-4908-8cd4-c79d0b107404");
    expect(calls[0].init.method).toBe("GET");
    expect(calls[0].init.headers).toMatchObject({ accept: "application/json", "x-api-key": "test-key" });
    expect(calls[0].init.body).toBeUndefined();
    expect(s?.status).toBe("Successful");
    expect(isSuccessful(s!.status)).toBe(true);
    expect(s?.message).toBe("Process service request successfully.");
  });

  it("encodes the reference safely", async () => {
    const { lipila, _setLipilaTransport } = await import("../src/lipila");
    _setLipilaTransport(reply(200, { ...documented, status: "Pending" }));
    await lipila.status("disbursement", "WD-1&x=2");
    expect(calls[0].url).toBe("https://blz.lipila.io/api/v1/disbursements/check-status?referenceId=WD-1%26x%3D2");
  });

  it("a failed payout is final; pending is not", async () => {
    const { lipila, _setLipilaTransport, isFailed, isSuccessful } = await import("../src/lipila");
    _setLipilaTransport(reply(200, { ...documented, status: "Failed", message: "Insufficient funds in wallet" }));
    const f = await lipila.status("disbursement", "WD-2");
    expect(isFailed(f!.status)).toBe(true);
    _setLipilaTransport(reply(200, { ...documented, status: "Pending" }));
    const p = await lipila.status("disbursement", "WD-3");
    expect(isFailed(p!.status) || isSuccessful(p!.status)).toBe(false);
  });

  it("400 / 401 / 403 / 429 and network errors are inconclusive (null), never treated as failure", async () => {
    const { lipila, _setLipilaTransport } = await import("../src/lipila");
    for (const code of [400, 401, 403, 429, 500]) {
      _setLipilaTransport(reply(code, { message: "error" }));
      expect(await lipila.status("disbursement", "WD-4")).toBeNull();
    }
    _setLipilaTransport((async () => { throw new Error("ECONNRESET"); }) as any);
    expect(await lipila.status("disbursement", "WD-4")).toBeNull();
  });

  it("payment (collection) status uses its own endpoint", async () => {
    const { lipila, _setLipilaTransport } = await import("../src/lipila");
    _setLipilaTransport(reply(200, { ...documented, type: "Collection" }));
    await lipila.status("collection", "PAY-9");
    expect(calls[0].url).toBe("https://blz.lipila.io/api/v1/collections/check-status?referenceId=PAY-9");
  });

  it("without a status path the lookup is skipped (withdrawal goes to manual review)", async () => {
    delete process.env.LIPILA_DISBURSEMENT_STATUS_PATH;
    const { lipila, _setLipilaTransport } = await import("../src/lipila");
    _setLipilaTransport(reply(200, documented));
    expect(await lipila.status("disbursement", "WD-5")).toBeNull();
    expect(calls).toHaveLength(0);
  });
});
