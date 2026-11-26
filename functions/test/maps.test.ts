import { describe, expect, it } from "vitest";
import { APPROVED_ENDPOINTS, assertApproved, MAPS_DEFAULTS } from "../src/maps/approved";
import { cell, decodePolyline, distanceToPathM, encodePolyline, geohash, haversineM } from "../src/maps/geo-math";
import { routeDecision } from "../src/routes";

describe("approved Google Maps APIs only", () => {
  it("allows exactly Places (New) autocomplete, Geocoding and Routes", () => {
    expect(assertApproved("POST", "https://places.googleapis.com/v1/places:autocomplete")).toBe("places_autocomplete");
    expect(assertApproved("GET", "https://maps.googleapis.com/maps/api/geocode/json?place_id=x")).toBe("geocoding");
    expect(assertApproved("POST", "https://routes.googleapis.com/directions/v2:computeRoutes")).toBe("routes_compute");
    expect(assertApproved("POST", "https://routes.googleapis.com/distanceMatrix/v2:computeRouteMatrix")).toBe("routes_matrix");
    expect(APPROVED_ENDPOINTS).toHaveLength(4);
  });
  it.each([
    ["GET", "https://maps.googleapis.com/maps/api/distancematrix/json?origins=1"],
    ["GET", "https://maps.googleapis.com/maps/api/directions/json?origin=1"],
    ["GET", "https://maps.googleapis.com/maps/api/place/autocomplete/json?input=a"],
    ["GET", "https://maps.googleapis.com/maps/api/place/details/json?place_id=a"],
    ["GET", "https://places.googleapis.com/v1/places/abc"],
    ["GET", "https://places.googleapis.com/v1/places/abc/photos/x/media"],
    ["POST", "https://addressvalidation.googleapis.com/v1:validateAddress"],
    ["GET", "https://roads.googleapis.com/v1/snapToRoads?path=1"],
    ["POST", "https://www.googleapis.com/geolocation/v1/geolocate"],
    ["GET", "https://maps.googleapis.com/maps/api/timezone/json?location=1"],
    ["GET", "https://maps.googleapis.com/maps/api/elevation/json?locations=1"],
    ["POST", "https://routeoptimization.googleapis.com/v1/projects/x:optimizeTours"],
  ])("blocks %s %s", (m, u) => { expect(() => assertApproved(m, u)).toThrow(/not an approved API/); });
  it("defaults: K45 base, K5.50/km, 3 chars, 500 ms, traffic off", () => {
    expect(MAPS_DEFAULTS.deliveryBaseFee).toBe(45); expect(MAPS_DEFAULTS.deliveryFeePerKm).toBe(5.5);
    expect(MAPS_DEFAULTS.mapsAutocompleteMinChars).toBe(3); expect(MAPS_DEFAULTS.mapsAutocompleteDebounceMs).toBe(500);
    expect(MAPS_DEFAULTS.routesTrafficAware).toBe(false); expect(MAPS_DEFAULTS.mapsCacheDays).toBeLessThanOrEqual(30);
  });
});

describe("geometry", () => {
  it("round-trips Google polylines", () => {
    expect(decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@")).toEqual([{ lat: 38.5, lng: -120.2 }, { lat: 40.7, lng: -120.95 }, { lat: 43.252, lng: -126.453 }]);
    const pts = [{ lat: -15.41, lng: 28.28 }, { lat: -15.42, lng: 28.3 }];
    expect(decodePolyline(encodePolyline(pts))).toEqual(pts);
  });
  it("distance from a point to a path", () => {
    const path = [{ lat: -15.4, lng: 28.28 }, { lat: -15.4, lng: 28.3 }];
    expect(distanceToPathM({ lat: -15.4, lng: 28.29 }, path)).toBeLessThan(1);
    expect(Math.round(distanceToPathM({ lat: -15.401, lng: 28.29 }, path))).toBeGreaterThan(100);
  });
  it("geohash and cache cells", () => {
    expect(geohash({ lat: 57.64911, lng: 10.40744 }, 11)).toBe("u4pruydqqvj");
    expect(cell({ lat: -15.416789, lng: 28.283456 })).toBe("-15.4168,28.2835");
    expect(Math.round(haversineM({ lat: -15.4, lng: 28.28 }, { lat: -15.4001, lng: 28.28 }))).toBe(11);
  });
});

describe("reroute policy (Routes API only when needed)", () => {
  const path = [{ lat: -15.4, lng: 28.28 }, { lat: -15.4, lng: 28.3 }];
  const cfg = { rerouteMinIntervalSec: 45, rerouteMaxPerHour: 12, offRouteMeters: 60 };
  const base = { cachedKey: "A:PICKUP>A:DROPOFF", requestedKey: "A:PICKUP>A:DROPOFF", cachedPath: path, callsLastHour: 1, nowMs: 1_000_000, cfg };
  it("reuses the cached route while the rider is on it", () => {
    expect(routeDecision({ ...base, origin: { lat: -15.4002, lng: 28.29 }, lastAtMs: 999_000 })).toBe("reuse");
  });
  it("waits instead of rerouting twice within the minimum interval", () => {
    expect(routeDecision({ ...base, origin: { lat: -15.41, lng: 28.29 }, lastAtMs: 1_000_000 - 10_000 })).toBe("wait");
  });
  it("reroutes when really off route and the interval has passed", () => {
    expect(routeDecision({ ...base, origin: { lat: -15.41, lng: 28.29 }, lastAtMs: 1_000_000 - 60_000 })).toBe("fetch");
  });
  it("respects the hourly cap", () => {
    expect(routeDecision({ ...base, origin: { lat: -15.41, lng: 28.29 }, lastAtMs: 0, callsLastHour: 12 })).toBe("wait");
  });
  it("fetches for a new set of stops (new job accepted)", () => {
    expect(routeDecision({ ...base, requestedKey: "A:PICKUP>B:PICKUP>A:DROPOFF>B:DROPOFF", origin: { lat: -15.4, lng: 28.29 }, lastAtMs: 999_000 })).toBe("fetch");
  });
});
