/**
 * The ONLY Google Maps Platform endpoints SeloNaChipa may call (server side). Anything else is refused before a request
 * leaves the function, so a stray Address Validation, Roads, Distance Matrix, Directions (legacy), Places (legacy),
 * Place Photos or Geolocation call can never be billed by accident. The Android app uses the fourth approved product,
 * Maps SDK for Android, with its own key restricted to that SDK.
 *
 *   1. Places API (New)   – autocomplete predictions only
 *   2. Geocoding API      – place_id → coordinates once per saved location, address text, reverse (pin drop)
 *   3. Maps SDK (Android) – map display on the phone (no server calls)
 *   4. Routes API         – computeRoutes (rider route after acceptance) and computeRouteMatrix (fee distance)
 */

export type MapsSku = "places_autocomplete" | "geocoding" | "routes_compute" | "routes_matrix";

interface Endpoint { sku: MapsSku; method: "GET" | "POST"; prefix: string }
export const APPROVED_ENDPOINTS: readonly Endpoint[] = [
  { sku: "places_autocomplete", method: "POST", prefix: "https://places.googleapis.com/v1/places:autocomplete" },
  { sku: "geocoding", method: "GET", prefix: "https://maps.googleapis.com/maps/api/geocode/json?" },
  { sku: "routes_compute", method: "POST", prefix: "https://routes.googleapis.com/directions/v2:computeRoutes" },
  { sku: "routes_matrix", method: "POST", prefix: "https://routes.googleapis.com/distanceMatrix/v2:computeRouteMatrix" },
] as const;

/** Throws unless (method, url) is one of the approved endpoints, and returns its SKU. */
export function assertApproved(method: string, url: string): MapsSku {
  const hit = APPROVED_ENDPOINTS.find((e) => e.method === method && url.startsWith(e.prefix));
  if (!hit) throw new Error(`Blocked Google Maps call (not an approved API): ${method} ${url.split("?")[0]}`);
  return hit.sku;
}

/**
 * List prices in USD per 1,000 billable events (Google Maps Platform pricing, 2025 tiers, before the monthly free
 * usage caps). Used only for the back-office spend estimate; check the console for the bill.
 */
export const LIST_PRICE_PER_1000: Record<MapsSku, number> = {
  places_autocomplete: 2.83, // Autocomplete Requests (per request; session tokens don't help without Place Details)
  geocoding: 5.0,            // Geocoding (Essentials)
  routes_compute: 5.0,       // Compute Routes Essentials (≤10 intermediates, TRAFFIC_UNAWARE)
  routes_matrix: 5.0,        // Compute Route Matrix Essentials, per element
};
/** Free billable events per SKU per month (Essentials tier). */
export const FREE_PER_MONTH: Record<MapsSku, number> = {
  places_autocomplete: 10_000, geocoding: 10_000, routes_compute: 10_000, routes_matrix: 10_000,
};

/** Config keys that cap each SKU per UTC day (0 = no cap) and switch it off. */
export const CAP_KEY: Record<MapsSku, string> = {
  places_autocomplete: "mapsDailyCapAutocomplete", geocoding: "mapsDailyCapGeocoding",
  routes_compute: "mapsDailyCapRoutes", routes_matrix: "mapsDailyCapRouteMatrix",
};
export const ENABLED_KEY: Record<MapsSku, string> = {
  places_autocomplete: "mapsAutocompleteEnabled", geocoding: "mapsGeocodingEnabled",
  routes_compute: "mapsRoutesEnabled", routes_matrix: "mapsRoutesEnabled",
};

/** Defaults merged into platformConfig (admin dashboard → Config → Maps & delivery pricing). */
export const MAPS_DEFAULTS = {
  // Delivery fee engine (marketplace orders): fee = base + km × rate, road distance from the Routes API.
  deliveryBaseFee: 45, deliveryFeePerKm: 5.5,
  // Client behaviour (the app reads these too)
  mapsAutocompleteMinChars: 3, mapsAutocompleteDebounceMs: 500, mapsReversePerPickerSession: 5,
  // Kill switches: off = the platform falls back to the address store, the gazetteer and straight-line distance.
  mapsAutocompleteEnabled: true, mapsGeocodingEnabled: true, mapsRoutesEnabled: true,
  // TRAFFIC_AWARE moves Compute Routes to the Pro SKU (≈2× price). Off by default.
  routesTrafficAware: false,
  // Daily caps (UTC) per SKU; 0 = unlimited. At 80% an ops alert is raised, at 100% calls degrade to fallbacks.
  mapsDailyCapAutocomplete: 20_000, mapsDailyCapGeocoding: 6_000, mapsDailyCapRoutes: 12_000, mapsDailyCapRouteMatrix: 15_000,
  // Per user per hour (cache misses only) and per rider rerouting rules
  mapsUserAutocompletePerHour: 120, mapsUserGeocodePerHour: 40,
  rerouteMinIntervalSec: 45, rerouteMaxPerHour: 12, offRouteMeters: 60, offRouteFixes: 3,
  // How long Google content may be reused from cache (Maps Platform terms: up to 30 days for lat/lng).
  mapsCacheDays: 30,
};
export type MapsConfig = typeof MAPS_DEFAULTS;
