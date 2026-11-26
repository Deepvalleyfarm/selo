import { describe, expect, it } from "vitest";
import { APPROVED_ENDPOINTS, assertApproved, MAPS_DEFAULTS } from "../src/maps/approved";
import { cell, decodePolyline, distanceToPathM, encodePolyline, geohash, haversineM } from "../src/maps/geo-math";
import { routeDecision, trafficInfo } from "../src/routes";
import { landmarkNear, pickShortest } from "../src/maps/client";

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
  it("defaults: K45 base, K5.50/km, 3 chars, 500 ms, live traffic on rider routes", () => {
    expect(MAPS_DEFAULTS.deliveryBaseFee).toBe(45); expect(MAPS_DEFAULTS.deliveryFeePerKm).toBe(5.5);
    expect(MAPS_DEFAULTS.mapsAutocompleteMinChars).toBe(3); expect(MAPS_DEFAULTS.mapsAutocompleteDebounceMs).toBe(500);
    expect(MAPS_DEFAULTS.routesTrafficAware).toBe(true); expect(MAPS_DEFAULTS.mapsCacheDays).toBeLessThanOrEqual(30);
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

describe("best route, traffic and landmarks", () => {
  it("picks the shortest route; near-ties go to the faster one", () => {
    expect(pickShortest([{ distanceMeters: 5200, duration: "600s" }, { distanceMeters: 4100, duration: "900s" }]).distanceMeters).toBe(4100);
    expect(pickShortest([{ distanceMeters: 4000, duration: "900s" }, { distanceMeters: 4080, duration: "500s" }]).distanceMeters).toBe(4080);
  });
  it("colours the route by congestion and grades it", () => {
    const pts = [{ lat: -15.40, lng: 28.30 }, { lat: -15.401, lng: 28.30 }, { lat: -15.402, lng: 28.30 }, { lat: -15.403, lng: 28.30 }, { lat: -15.404, lng: 28.30 }];
    const poly = encodePolyline(pts);
    const t = trafficInfo(poly, [{ from: 0, to: 2, speed: "NORMAL" }, { from: 2, to: 4, speed: "TRAFFIC_JAM" }], 900, 500);
    expect(t.segments).toHaveLength(2); expect(t.segments[1].speed).toBe("TRAFFIC_JAM"); expect(t.level).toBe("heavy"); expect(t.delayS).toBe(400);
    expect(trafficInfo(poly, [{ from: 0, to: 4, speed: "NORMAL" }], 300, 290).level).toBe("clear");
    expect(trafficInfo("", [], 300, 300).level).toBe("unknown");
  });
  it("finds a building or named place within 10 m of the pin", () => {
    const at = { lat: -15.41, lng: 28.30 };
    const j = { results: [
      { types: ["plus_code"], formatted_address: "H8Q2+2X Lusaka", geometry: { location: at } },
      { types: ["street_address"], formatted_address: "12 Great East Rd", geometry: { location: at }, address_components: [] },
      { types: ["establishment", "point_of_interest"], formatted_address: "Arcades Shopping Mall, Great East Rd", geometry: { location: { lat: -15.41005, lng: 28.30003 } },
        address_components: [{ long_name: "Arcades Shopping Mall", types: ["establishment", "point_of_interest"] }] },
    ] };
    expect(landmarkNear(j, at, 10)).toBe("Arcades Shopping Mall");
    expect(landmarkNear({ results: [{ ...j.results[2], geometry: { location: { lat: -15.4102, lng: 28.30 } } }] }, at, 10)).toBeNull(); // ~22 m away
  });
});
