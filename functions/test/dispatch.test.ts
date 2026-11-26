import { describe, expect, it } from "vitest";
import { dispatchScore, pickWave, rankCandidates, type RiderCandidate } from "../src/dispatch";

const job = { pickup: { lat: -15.4167, lng: 28.2833 }, dropoff: { lat: -15.43, lng: 28.30 } };
const base: RiderCandidate = { uid: "x", geo: null, pingAgeMin: 1, activeCount: 0, maxConcurrent: 4, acceptanceRate: 95, completionRate: 98, completedDeliveries: 50, routeStops: [] };
const at = (dLat: number) => ({ lat: job.pickup.lat + dLat, lng: job.pickup.lng });

describe("smart dispatch score", () => {
  it("prefers the rider closer to the seller", () => {
    const near = dispatchScore({ ...base, uid: "near", geo: at(0.005) }, job), far = dispatchScore({ ...base, uid: "far", geo: at(0.06) }, job);
    expect(near.score).toBeGreaterThan(far.score);
    expect(near.etaPickupMin!).toBeLessThan(far.etaPickupMin!);
  });
  it("rewards good acceptance and completion history", () => {
    const good = dispatchScore({ ...base, geo: at(0.01) }, job), poor = dispatchScore({ ...base, geo: at(0.01), acceptanceRate: 40, completionRate: 60 }, job);
    expect(good.score - poor.score).toBeGreaterThan(8);
  });
  it("favours riders whose current route passes the job, and free capacity", () => {
    const onRoute = dispatchScore({ ...base, geo: at(0.02), activeCount: 1, routeStops: [job.dropoff] }, job);
    const offRoute = dispatchScore({ ...base, geo: at(0.02), activeCount: 1, routeStops: [{ lat: -15.2, lng: 28.6 }] }, job);
    expect(onRoute.score).toBeGreaterThan(offRoute.score);
    const busy = dispatchScore({ ...base, geo: at(0.02), activeCount: 3, routeStops: [job.dropoff] }, job);
    expect(onRoute.score).toBeGreaterThan(busy.score);
  });
  it("offers in growing waves: top 3, top 8, then everyone", () => {
    const cs = Array.from({ length: 12 }, (_, i) => ({ ...base, uid: `r${i}`, geo: at(0.004 * i) }));
    const ranked = rankCandidates(cs, job);
    expect(ranked[0].uid).toBe("r0");
    expect(pickWave(ranked, 0)).toEqual(["r0", "r1", "r2"]);
    expect(pickWave(ranked, 1)).toHaveLength(8);
    expect(pickWave(ranked, 2)).toHaveLength(12);
  });
});
