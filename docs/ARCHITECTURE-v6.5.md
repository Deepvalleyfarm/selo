# SeloNaChipa v6.5 — production integrations

Google Maps, Beem OTP/SMS, Lipila payments and the Listing Studio, built on the existing stack: Kotlin/Compose
Android, Firebase Cloud Functions (Node 22, TypeScript), the named Firestore database, and the React back office.
Specs that assumed Next.js, PostgreSQL or Prisma were mapped onto this stack. The Postgres migration and Prisma
models for the Beem module are still included under `functions/src/beem/migrations/` so Mabala can reuse it.

## 1. Google Maps

### Only four APIs

| API | Where | Used for |
|---|---|---|
| Places API (New) — Autocomplete | Cloud Functions (`maps/client.ts`) | Address search for buyers, sellers, agents and riders. Zambia only, biased to the user's city. |
| Geocoding API | Cloud Functions | Converts the chosen `place_id` to coordinates once, on save. Also reverse lookup for the map pin. |
| Routes API — `computeRouteMatrix` | Cloud Functions | Distance and ETA for the delivery fee. One element, Essentials tier. |
| Routes API — `computeRoutes` | Cloud Functions | Rider route with turn-by-turn steps, only after a job is accepted. |
| Maps SDK for Android | App (`ui/maps/SeloMap.kt`) | Map display, markers, clustering, polylines. Native map loads are free. |

- `maps/approved.ts` refuses any other Google endpoint before a request leaves the function. A unit test covers
  Distance Matrix, Directions, legacy Places, Place Details, Photos, Address Validation, Roads, Geolocation,
  Time Zone, Elevation and Route Optimization.
- The phone calls no Google web services. The server key lives in Secret Manager (`MAPS_API_KEY`). The Android key
  is restricted to the Maps SDK, the package name and the signing SHA-1. `scripts/maps-setup.sh` creates both
  keys and disables every other Maps API.

### Layers

```
Android: AddressField → PlacesCache (3 chars, 500 ms, on-device LRU + 7-day disk cache, session token)
         MapPicker    → fixed centre pin; reverse lookup on idle, skipped for moves under 12 m, max 5 per session
         SeloMap      → one lazily created MapView per Activity, moved between screens; markers diffed and animated
         RouteController → ReroutePolicy (3 off-route fixes, ≥45 s apart, ≤12/h) + trims finished legs locally
Server:  geo.ts / routes.ts → maps/client.ts → cache.ts (in-process LRU → Firestore mapsCache with TTL)
                                            → usage.ts (sharded daily counters, caps, kill switches, alerts)
```

### Firestore data model (Maps)

| Document | Fields |
|---|---|
| `users/{uid}.location` | `{ address, latitude, longitude, placeId, source, cityId, neighbourhood, updatedAt }` — permanent; never geocoded again. |
| `stores/{sellerUid}` | `{ name, address, latitude, longitude, geohash, geohash5, cityId, active }` — public store markers. |
| `orders/{id}` | `pickupLocation`, `deliveryLocation` (`{ address, latitude, longitude }`), `distanceKm`, `etaMinutes`, `deliveryFee`. |
| `orders/{id}.navigation` | `{ pickupLat, pickupLng, dropoffLat, dropoffLng, distanceKm, estimatedMinutes }`, plus `trackingPolyline` (only the leg into this buyer's drop-off). |
| `riders/{uid}` | `{ currentLatitude, currentLongitude, accuracy, heading, updatedAt }` — written by `rider_ping` every 30 s. |
| `riderRoutes/{uid}` | Last computed route, used for free reuse and throttling. |
| `mapsCache/{id}` | Google results, with a `expiresAt` TTL (30 days at most, per the Maps terms). |
| `mapsUsage/{day}/shards/{n}` | Calls, cache hits, errors and fallbacks per SKU. |

### Delivery fee

Fee = `deliveryBaseFee` + km × `deliveryFeePerKm`. Defaults are K45 + K5.50/km, so 8 km costs K89. The distance
is the road distance from the Routes matrix. Both values are set in the admin dashboard under Configuration →
Maps & delivery pricing. Parcels keep their own configured rates (K30 + K4.50/km).

### Cost controls

- **Phone:** autocomplete starts at 3 characters, 500 ms debounce, on-device cache, and no lookups while the map is
  moving.
- **Server caches:** shared caches with a 30-day TTL, and duplicate concurrent requests collapsed into one.
- **Free sources first:**
  - Our own address store answers before Places is called.
  - A saved address within 25 m answers before reverse geocoding.
  - A known `place_id` is never geocoded twice.
- **Routes:**
  - Only for accepted jobs. A cached route is reused while the rider stays on it.
  - Finished stops are trimmed from the route locally.
  - `TRAFFIC_UNAWARE` keeps calls on the Essentials price.
  - Waypoints are ordered on the phone, so the Pro/Enterprise optimisation is never used.
- **Abuse limits:** per-user hourly limits on cache misses.
- **Caps:** daily caps per SKU. At 80% an ops alert is raised. At 100% the platform falls back to saved addresses,
  the neighbourhood list and straight-line distance (labelled "approximate").
- **Kill switches:** one per API.
- **Backstop:** set Cloud Console quotas and a billing budget (see the setup script).

### Monitoring

- Back office → Google Maps usage: calls, cache-hit rate, errors, latency and estimated cost per API per day.
- Structured `maps_call` logs. Create a log-based metric for alerting.
- `opsAlerts` entries at 80% and 100% of a cap.

## 2. Beem OTP and SMS (BEEM-OTP-001)

The module is reusable: `functions/src/beem/*` has its own config, OTP, SMS, health and log-store code. Mabala
changes only its environment values.

- **OTP:**
  - `auth_sendOtp` returns `{ pinId, expiresInSeconds }`, never the PIN. Numbers are cleaned and validated before
    Beem is called.
  - Rate limits: 3 requests per number and 10 per IP per 10 minutes.
  - One retry on network timeouts only.
  - `auth_verifyOtp` checks that the pinId was issued to that same number. Code 117 issues the one-time
    registration token.
  - HTTP routes `/api/auth/otp/request` and `/verify` are available for web clients and other apps.
- **App:**
  - Six boxes that auto-advance and accept paste.
  - Auto-read through the SMS Retriever (zero tap) or SMS User Consent (one tap). Auto-verifies as soon as the
    code is filled.
  - 60 s countdown; Resend unlocks at zero.
  - For zero-tap reading, add the app hash to the Beem OTP template.
- **SMS:** server-side templates (`sms-templates.ts`), sent at these events:
  - Parcel collected → receiver.
  - Parcel delivered → sender.
  - Order confirmed → buyer.
  - Rider assigned → buyer.
  - Account created → new user.

  Delivery-code bodies are stored with the digits masked.
- **Logs:**
  - `otp_logs` and `sms_logs` use the exact SQL column names.
  - `beem_stats/{day}` holds the 7-day KPIs.
  - `beem_health/current` is refreshed every 5 minutes.
- **Monitor:** back office → Beem OTP & SMS (Super Admin only): health card with Recheck, active-errors panel with
  causes and actions, KPIs, filterable OTP and SMS logs with masked numbers, and CSV export.
- **Health check:** by default it sends no SMS. It probes the OTP API with an invalid number and reads the SMS
  balance. Set `BEEM_HEALTH_MODE=send` to send a real test OTP every 5 minutes.

## 3. Lipila payments

- **Client (`lipila.ts`):**
  - `x-api-key` and `callbackUrl` headers.
  - `/api/v1/collections/mobile-money` and `/api/v1/disbursements/mobile-money`.
  - Accounts in `260…` format, amounts in ZMW.
  - Backoff on 429.
  - The production base URL must be set; there is no sandbox fallback.
- **Webhook (`lipila_webhook`):**
  - Standard Webhooks HMAC-SHA256 over `id.timestamp.body` on the raw bytes.
  - Timestamp checked before the signature, with a 300 s tolerance.
  - Several `v1,` signatures and several configured secrets are accepted, so keys can be rotated.
  - Constant-time comparison. Returns 401 on failure.
  - `webhook_idempotency_log` and `lipila_webhook_events` are created atomically: a repeat delivery gets 200 and
    is ignored.
  - Processing runs asynchronously in `onLipilaWebhookEvent`.
- **Statuses:**
  - Final failures: `Failed`, `Wrong PIN`, `Wrong Number`, `Insufficient Funds`, `Transaction Limit Exceeded`,
    `Cancelled`.
  - Not final: `Pending`, `Timeout`, `Network Error`, `System Error`, `Unknown` — these leave the outcome to
    reconciliation.
- **Withdrawals (`payments/withdrawals.ts`):**
  - Full balance only, for riders, sellers and agents.
  - PIN checked with bcrypt before any lock. 5 wrong PINs lock withdrawals for 15 minutes.
  - The amount is locked with `activeWithdrawalId` (one in flight per wallet) before Lipila is called.
  - Only a `Successful` callback or status lookup completes it.
  - 4xx responses release the lock. A missing response or callback goes to reconciliation every minute, then to
    `MANUAL_REVIEW` with funds still locked.
  - Every transition is written to `withdrawal_audit_log`.
  - Back office → Withdrawals lets finance resolve `MANUAL_REVIEW` items.
- **Transaction Monitor:** back office → Transactions (Lipila), Super Admin only.
  - Server-side filters and search.
  - Stat cards and payment-type breakdown.
  - Status badges shown verbatim, auto-refresh every 30 s with new-arrival toasts.
  - Detail drawer with the raw payload and times in UTC and Lusaka, plus a deep link at `/transactions/:id`.
  - CSV export with BOM and formula-injection protection.
  - Every call checks the role, is limited to 100 per minute per admin, and is written to `adminViewLog`.

## 4. Listing Studio

`studio/*` runs one step per Firestore trigger and polls Runway every minute.

- **Video listing:**
  1. Cloudinary upload.
  2. Deepgram nova-3 captions of the seller's own voice.
  3. Gemini brief: headline, a 25–40 word voiceover, the Runway prompt and hashtags. It may not invent features.
  4. ElevenLabs voiceover (multilingual v2).
  5. Cloudinary edit: 9:16 1080×1920 smart crop, stabilisation, colour lift, trim to 30 s or less, fades,
     headline and price lower-third, brand tag, voiceover over the lowered original sound, burned-in captions.
     Pre-rendered with eager transformations.
- **Photo listing:** Cloudinary enhancement (smart crop, auto-improve, sharpen, optional background removal), a
  Runway gen4.5 5-second vertical product-motion clip from the hero photo, then the same voiceover and edit.
- **Safety:**
  - Optional steps (captions, voiceover, motion) are skipped if they fail.
  - Originals stay live until the studio version is ready, and are kept so the seller can revert.
  - Results are labelled `aiEnhanced` (and `aiGeneratedMotion` for Runway clips).
- **Caps:** per seller per day, plus daily caps for Runway and voiceover. Everything is switchable in the admin
  dashboard.

## 5. Secrets and parameters

Secrets go only into Secret Manager (`firebase functions:secrets:set NAME`):

- `MAPS_API_KEY`
- `BEEM_API_KEY`, `BEEM_SECRET_KEY`
- `LIPILA_API_KEY`, `LIPILA_WEBHOOK_SECRET` (raw base64 or `whsec_…`; several, space-separated, during rotation)
- `GEMINI_API_KEY`
- `CLOUDINARY_URL`, `DEEPGRAM_API_KEY`, `ELEVENLABS_API_KEY`, `RUNWAY_API_KEY`
- `DELIVERY_CODE_SECRET`

Non-secret parameters go in `functions/.env.<projectId>` (see `functions/.env.example`):

- `LIPILA_BASE_URL` (production host), `LIPILA_CALLBACK_URL`, `LIPILA_MERCHANT_ID`
- `LIPILA_COLLECTION_STATUS_PATH`, `LIPILA_DISBURSEMENT_STATUS_PATH`
- `BEEM_APP_ID`, `BEEM_SENDER_ID`, `BEEM_APP_NAME`
- `RUNWAY_MODEL`, `ELEVENLABS_VOICE_ID`, `SMS_PROVIDER`

The Android Maps key goes in `android/local.properties` as `MAPS_ANDROID_API_KEY` (or the CI environment).

## 6. Deployment checklist

1. Run `scripts/maps-setup.sh <project> <release SHA-1>`, set the Cloud Console quotas and a billing budget.
2. Set all the secrets above and fill in `functions/.env.<project>`. Ask Lipila to confirm the production base URL
   and the status-lookup paths.
3. `firebase deploy --only firestore:rules,firestore:indexes,functions,hosting`. TTL policies:
   - `mapsCache.expiresAt`, `mapsRate.expiresAt`
   - `otp_sessions.expiresAt`, `otp_rate.expiresAt`
   - `webhook_idempotency_log.expires_at`, `adminRate.expiresAt`
4. In Lipila (Merchant 1308), register `https://<region>-<project>.cloudfunctions.net/lipila_webhook` as the
   webhook URL. Make a small live collection and disbursement and confirm both appear in the Transaction Monitor.
5. Beem:
   - Append the app hash to the OTP template (`keytool -exportcert … | xxd -p | …` or log `AppHash.compute()` once
     from a release build).
   - Confirm the sender ID is approved.
6. Run `scripts/backfill-stores.mjs` once to create `stores/*` and `users.location` for existing sellers.
7. Rebuild the APK with `MAPS_ANDROID_API_KEY` set. Builds without it fall back to OpenStreetMap and drawn maps.
