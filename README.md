# SeloNaChipa

Zambia's smart marketplace: buy, sell, deliver, earn. This repository holds the production code for:

| Folder | What it is | Stack |
| --- | --- | --- |
| `android/` | Android app for Buyers, Sellers, Agents and Riders (package `selo.Nachipa01`) | Kotlin, Jetpack Compose, Material 3, CameraX, Firebase BoM 35.2.0 |
| `backoffice/` | Web back office for platform administrators | React 19, Vite, TypeScript, Firebase Web SDK |
| `functions/` | Backend: PIN + security-question auth, escrow engine, Lipila payments, AI listings, admin APIs, scheduled jobs | Cloud Functions for Firebase (Node 22, TypeScript) |
| `firebase/` | Firestore, Storage and Realtime Database security rules, Firestore indexes | |

The specification is PRD v6.0 (Unified Android + Back Office edition) with the v6.1 discovery and v6.2 promotions upgrades, and the matching mock-ups.

## How the app and back office stay in sync

Both clients use the **same named Firestore database** in Firebase project **Mabala**:

```
ai-studio-611c950c-febe-47c1-afff-35dcb0788339
```

- Anything the app writes (orders, listings, KYC submissions, rider presence, disputes) appears in back office tables through live listeners within seconds.
- Anything an admin changes (fees in `platformConfig/default`, zones, adverts, KYC decisions, suspensions) is written by an audited Cloud Function. The app holds a live listener on the same documents, and an FCM `config-updates` topic message wakes background devices.
- Money, roles, KYC status, PINs and config are only ever written by Cloud Functions. Security rules block direct client writes to them.
- Orders snapshot the fee config at payment time, so an admin change never alters an order already in flight.

## Run it without Firebase (demo mode)

Both clients are now configured for project `mabala-f2d65` (`android/app/google-services.json`, `backoffice/.env.production`). To click through with sample data instead, temporarily remove `google-services.json` (Android) or run `npm run dev` (back office).

```bash
cd android && ./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
```

Demo logins: any 5-digit PIN that isn't trivial (not 12345 or 11111). The last digit of the number picks the role: `0971234561` Buyer, `...2` Seller, `...3` Agent, `...4` Rider. Create Profile walks through the full registration flow for every role.

```bash
cd backoffice && npm install && npm run dev  # http://localhost:5173, signed in as a demo Super Admin
```

## Connect to Firebase project Mabala

Mabala is tagged as a production project. Develop against the Emulator Suite or a staging project first.

1. **Android app** (already registered, App ID `1:237562839144:android:234228db94a7ab9370fbdd`)
   - Add SHA-1 and SHA-256 fingerprints for the debug, upload and Play App Signing keys (`cd android && ./gradlew signingReport`).
   - `google-services.json` is committed in `android/app/`. Re-download it after adding SHA fingerprints (needed for App Check and Google sign-in, not for basic use).
   - Enable App Check with Play Integrity.
2. **Web back office**
   - The Web app config for project `mabala-f2d65` is in `backoffice/.env.production`, so `npm run build` targets Mabala. `npm run dev` still uses demo data unless you create `backoffice/.env.local`.
   - Optional: add a reCAPTCHA Enterprise key for App Check.
3. **Project settings**
   - Turn on Email/Password sign-in with multi-factor authentication for admins.
   - Enable Realtime Database and Storage.
4. **Secrets and parameters**

   ```bash
   firebase functions:secrets:set LIPILA_API_KEY
   firebase functions:secrets:set LIPILA_WEBHOOK_SECRET
   firebase functions:secrets:set GEMINI_API_KEY
   firebase functions:secrets:set MAPS_API_KEY           # Routes, Geocoding, Places, Distance Matrix
   firebase functions:secrets:set DELIVERY_CODE_SECRET   # 32+ random bytes; HMAC key for delivery codes
   firebase functions:secrets:set SMS_API_KEY            # Africa's Talking (or your HTTP gateway) key
   ```

   Set `LIPILA_BASE_URL`, `LIPILA_CALLBACK_URL` (the deployed `lipila_webhook` URL) and `GEMINI_MODEL` in `functions/.env.<projectId>`.
5. **Deploy**

   ```bash
   cd functions && npm ci && cd ..
   cd backoffice && npm ci && npm run build && cd ..
   firebase deploy --only firestore,storage,database,functions,hosting
   ```

6. **Seed reference data and the first admin**

   ```bash
   cd functions
   GOOGLE_APPLICATION_CREDENTIALS=sa.json node scripts/seed.mjs
   GOOGLE_APPLICATION_CREDENTIALS=sa.json node scripts/grant-admin.mjs you@yourcompany.zm super_admin
   ```

   The seed adds the 12 security questions, platform config defaults, Lusaka zones, the 5 welcome adverts and categories. After that, manage admins from **Admin users** in the back office.

## v6.1 — discovery upgrade (three pillars)

**Pillar 1 — Infinity Memory** (all in the named Firestore database)
- **Collision-free keys:** every key is `entity_type + UUIDv7 + shard`, built with jump consistent hashing over 64 shards. Adding a shard never rehashes everything.
- **Co-location:** listings are sharded by seller and events by user.
- **Append-only event log:** `behaviourEvents` accepts creates only; security rules block edits and deletes. The `onBehaviourEvent` trigger builds the read models from it, so nothing else writes them:
  - listing counters and `listingDaily`;
  - `cohortMetrics`, `sellerAudience` and `adminMetrics`;
  - `shares`, `searchStats` and `likes`.
- **Versioned algorithm:** each `algorithmState/vNNNNNN` is written once and never overwritten. `current` is a pointer to the active version, so any version can be replayed or rolled back.
- **Archive, never purge:** soft-deleted listings move to the cold `archive` collection after 30 days.
- **Storage tiers:** the PRD names Redis, PostgreSQL and S3 Parquet. Here they map to Firestore (hot and warm) plus the optional Firestore → BigQuery export extension (cold), because all data must stay in the named database.

**Pillar 2 — AI video and photo listings**
- **Capture:** the seller taps "+" and chooses a 15–60 s portrait video (with countdown and waveform) or up to 4 photos (drag to reorder).
- **On the phone:** frames are extracted (1 fps for 10 s, then 1 every 5 s) and ML Kit image labelling gives an instant category.
- **Cloud AI:** Gemini drafts the title and a 3–5 sentence description. The price is a recency-weighted regional median from the last 30 days.
- **Fallback:** after 8 s (`aiTimeoutSec`) the seller gets an empty manual form with a notice.
- **Review and post:** every field must be confirmed, then the seller previews the listing as a feed card. Posting sets `pending_review`, which is auto-approved unless the content-policy check flags it.

**Pillar 3 — discovery algorithm** (`functions/src/algorithm.ts`, pure and unit tested)
- **Retrieval:** 200 candidates per page — 40% cohort, 30% regional trending, 20% posted in the last 24 h, 10% exploration. Listings shown in the last 72 h are excluded.
- **Ranking:** a weighted score with the PRD default weights, and never the same seller twice in a row.
- **Hourly:** 50-dimension buyer interest vectors, seller 7-day engagement rate, listing virality.
- **Every 6 h:** micro-tuning by the three §6.3 rules.
- **Weekly:** k-means re-clustering into 20 cohorts, an A/B test with 4 variants at 2.5% of traffic each, and flags for weak categories.
- **Guardrails:** no optimisation after 145 min a day, a break notice at 130 min, and a budget check at more than 13× weekly enquiries. Admins can adjust them; switching them off needs a `ceo: true` claim.
- **Buyer feed:**
  - Full-screen vertical feed: videos auto-play muted and pause below 50% visibility; photos auto-advance every 3 s.
  - Gestures: double-tap to like, long-press to save to a named wishlist, swipe left for "not interested", pull to refresh.
  - Follow sellers, share in-app or on WhatsApp (deep links `selonachipa://listing/{id}` and `https://mabala-f2d65.web.app/l/{id}` with a web preview).
  - Search with filters and "people also searched for"; home rails (Because you liked, Popular in your area, You searched for, Deals for you).
- **Privacy:** sellers see totals only. Admins see engaged buyers. The seller lead export includes only buyers who switched on "Let sellers contact me" (Zambia Data Protection Act 2021).

## v6.2 — Boost, Feed Story and smarter mobile money payments

**Paid reach (platform revenue)**
- **Boost** (Local K20/day, Regional K45/day, National K90/day; 1, 3, 7 or 14 days) puts a live listing into matching buyers' feeds at slots 2 and 6 and at the top of matching searches, labelled *Sponsored*, at most 3 times per buyer per day.
- **Feed Story** (K10 for 24 h) shows the listing in the Stories row on buyer Home; followed sellers come first.
- **Targeting:** districts (Local) or provinces (Regional), age bands, interest categories, people who searched for the product in the last 7 days, and followers. The seller sees an estimated audience before paying.
- **Payment:** Airtel Money, MTN Money or Zamtel Money via Lipila (`SELO-BST-` / `SELO-STY-` references). Nothing is shown to buyers until the webhook confirms the payment. A failed payment can be retried with another number; a duplicate payment is refunded automatically.
- **Results:** reach, opens, likes and enquiries per promotion (from the behaviour log) on the seller home. Admins see revenue per day and per operator, every promotion, and can end one with a pro-rata refund (**Boosts & revenue** page). All prices are in **Configuration → Promotions**.

**Buyer payments**
- The registered number is the default payment method, shown with its operator logo (a Zamtel-registered buyer pays with Zamtel Money by default).
- **Pay with another number** detects the operator as you type: 097/077 Airtel Money, 096/076 MTN Money, 095/075 Zamtel Money, with the operator logo. Other prefixes are rejected.
- The amount is always calculated: selected items (cart or Buy now, one seller per order) + delivery + platform fee + transaction fee (`paymentProcessingPct`).
- If the prompt fails (for example insufficient funds), the order stays open with the reason. The buyer retries with another number (`order_retryPayment`, reference `SELO-COL-{id}-R{n}`), and unpaid orders close after 30 minutes.

## v6.3 — Rider app (PRD 6) and multi-stop routing

**Rider money:** 8% platform fee and 5% Social Fund on every delivery fee. The fund is ring-fenced for claims (`socialFundMilestoneDeliveries: 0`). Hero and Ambassador riders get a platform-funded +10% bonus (`heroBonusPct`). Daily auto-settle pays out at midnight.

**Screens**
- **Navigation:** five-tab bar (Home · Jobs · Earnings · Fund · More).
- **Home:** 72 dp Online/Offline toggle with a 2-second colour sweep, and four stat cards.
- **Dispatch:** full-screen card with a 15-second countdown, Social Fund line and escrow notice.
- **Active delivery:** route map, live remaining km/ETA/fee, multi-stop timeline, and a proof-of-delivery photo plus the buyer's code. Then the rider rates the buyer.
- **Jobs:** history with filters and a delivery detail screen showing the fee breakdown and the photo. A Hero tier progress bar sits at the bottom.
- **Earnings:** weekly target, auto-settle, statement and withdraw.
- **Social Fund:** 7-day chart, claim categories, tier grid and a 4-step claim flow.
- **More:** Messages (order chats and dispatch notices), Zone & availability (extra zones and working hours, which dispatch respects), Notifications, Profile (editable), and Settings & support (payout wallet, PIN, security questions, emergency contact, incident reports, weekly target).
- **Login:** role avatar, on-screen keypad, and a grey keypad during the 30-minute lockout.

**Multi-stop routes and turn-by-turn**
- **Combining jobs:** a rider can carry up to `maxConcurrentDeliveries` orders. The dispatch card shows how many km a new job adds to the current route.
- **Planning:** `domain/route/Route.kt` orders all pickups and drop-offs on the phone. Every pickup comes before its own drop-off. It starts with nearest-feasible, then improves with relocate and 2-opt moves.
- **Directions:** `route_directions` calls the Google Maps Routes API (two-wheeler, falling back to driving). `route_locate` geocodes stops that have no coordinates. The key stays server-side:

  ```bash
  firebase functions:secrets:set MAPS_API_KEY   # Routes API + Geocoding API enabled
  ```

- **In-app navigation:**
  - Live GPS with the current manoeuvre, distance and next step.
  - Voice prompts at 400, 150 and 30 m.
  - Automatic re-route when more than 50 m off the route.
  - Arrival actions (Mark collected / Confirm delivery) at each stop.
- **Fallbacks:** without a key the app shows straight-line guidance, and one tap opens the same ordered stops in Google Maps.

## v7.0 — SeloNachipa Open Network (partner API)

Other platforms (Mabala is the first) can list their products on SeloNachipa, use SeloNachipa riders for their deliveries, and make AI videos and ads, through one REST API at `/api/v1` (`functions/src/open/`). Reference: `docs/api/index.html` and the machine-readable `docs/api/openapi.json` (also served at `/api/v1/openapi.json`).

- **Discovery, not resale.** Partner products are listings with an `origin` (platform, seller, product id, `checkoutUrl`) and go through the same moderation and feed as any listing. Buyers tap **Buy on {platform}** and pay on the partner's own checkout; SeloNachipa never sells them (they can't enter the cart, and `order_create` refuses them). Partners keep their own orders, payments and stock.
- **Delivery at SeloNachipa's fee.** `POST /delivery/quotes` returns SeloNachipa's parcel tariff (delivery + service fee, plus `openExpressPremiumPct` for Express, default 25%). The partner charges its buyer exactly that and owes it to SeloNachipa in full; SeloNachipa splits it to rider, platform and Social Fund as configured. Deliveries are ordinary parcels in the existing engine (dispatch, pickup PIN, delivery code, tracking), one per `externalOrderRef`. Riders see "via {platform}" on the job.
- **AI videos and ads.** `POST /products/:id/video` runs the existing Listing Studio for a partner product (Runway product motion, voiceover, captions, branded 9:16 edit; webhooks `media.video.ready` / `media.video.failed`). `POST /ads` runs the AI Ad Generator (story and square images around the real photo, plus WhatsApp, Facebook and TikTok copy). Daily caps: `openStudioDailyCap` (50) and `openAdDailyCap` (200) per partner.
- **Security.** OAuth 2.0 client credentials (1-hour HS256 tokens). Client secrets are stored as scrypt hashes and webhook secrets AES-256-GCM encrypted. Partner and client are re-checked on every call, so revocation and suspension apply at once. Scopes are checked against the services enabled for the partner. Per-minute rate limits; `Idempotency-Key` on every write; an audit record and usage counters per call. Partners see only their own data, and of a rider only the first name and plate while the job is active.
- **Webhooks.** Standard Webhooks signatures (`webhook-id`, `webhook-timestamp`, `webhook-signature`), retries at 1 min, 5 min, 15 min, 1 h, 3 h, 6 h, 12 h and 24 h, history and replay.
- **Settlement.** `openLedger/{partner}/entries`: `DELIVERY_CHARGE`, `DELIVERY_REVERSAL`, `API_USAGE`, `MEDIA_USAGE`, `SUBSCRIPTION`, `SETTLEMENT`. Monthly billing job `scheduled_openBilling`.
- **Sandbox.** `sbx_` clients. Sandbox products are never shown to buyers, sandbox deliveries never reach riders (they advance one status a minute and fire webhooks), and sandbox AI media returns marked samples without calling the paid providers.
- **Back office → Developer & Platform Integrations** (`/integrations`, Super Admin): approve, suspend or reject platforms; enable Commerce/Logistics, scopes, sandbox/production and rate limits; set pricing (subscription, per call, per AI video, per AI ad); create, rotate and revoke credentials; webhooks and replay; usage, products, deliveries, settlement and audit per platform; manual rider assignment.
- **Developer portal** at `/developers`: sign up, register a platform, sandbox credentials, a console that runs sample calls, the API reference and guides, and the production request.
- **Mabala connector** (Mabala repo, branch `selonachipa-open-network`): channel toggles on the product form, product and stock sync with a checkout link back to Mabala, the SeloNachipa delivery fee at Mabala's checkout, rider requests, signed webhook receiver, and the sale journal posted once to the vendor's farm node.

### Turning it on

1. Create the secrets (any long random values):
   ```bash
   firebase functions:secrets:set OPEN_API_JWT_SECRET --project mabala-f2d65
   ```
   ```bash
   firebase functions:secrets:set OPEN_API_ENC_KEY --project mabala-f2d65
   ```
   Rotating `OPEN_API_ENC_KEY` makes stored webhook secrets unreadable: partners must set their webhook again.
2. Enable **Email/Password** sign-in in Firebase Authentication (developer portal accounts).
3. Deploy functions, Firestore rules and indexes, and hosting (`/api/v1/**` and `/developers` are routed in `firebase.json`).
4. Approve the platform in the back office and give it production access.
5. On Mabala's server, set `SELONACHIPA_API_BASE` (`https://mabala-f2d65.web.app/api/v1`), `SELONACHIPA_CLIENT_ID`, `SELONACHIPA_CLIENT_SECRET` and `SELONACHIPA_WEBHOOK_SECRET`, and point the platform's webhook at `https://<mabala host>/api/selonachipa/webhook`.

Not included: delegated (user-authorised) OAuth flows; only client credentials are supported.

## v6.9.7 — agreements and address cards

- **Agreements in the app** (`assets/legal`): the Terms of Use, Privacy Policy, Seller Terms, and Rider and Agent Terms, converted from the company's Word documents.
  - The Terms of Use links to the other three (clause 1.6), and each document links back. A tab row switches between them.
  - Copying and screenshots are blocked: text isn't selectable, and `FLAG_SECURE` is set while an agreement is open, including the sign-up dialog window. That also blocks screen recording and the Recents preview.
  - Where to find them:
    - each role's sign-up terms step ("Read before you accept", with role-specific links);
    - Account → Terms of Use & policies, and Rider → Settings;
    - the welcome screen footer.
- **Address cards everywhere:** the seller shop location, registration addresses, the main location and saved addresses use the same card as parcels and checkout (`AddressCard`).

## v6.9.6 — route-style pickup and delivery

- **Send a parcel and checkout** use one rounded route card (`RouteAddressCard`), in the style of ride-hailing apps:
  - **From** has a red ring: the sender's GPS for parcels, or the seller's shop (fixed) at checkout. **To** has a search icon.
  - Tapping a row turns it into a search box with a clear (×) button and a **Map** button.
  - Suggestions appear under the card with a category icon (mall, gym, police, school, estate, market…), the area and the distance from you.
  - With nothing typed, it offers "Use my current location", saved places (Home, Work, Shop) and recently used places.
  - It keeps all the existing behaviour: Places search near the user, on-device cache, GPS accuracy check with map nudge, a landmark within 10 m, and remembered places.
- Parcels now choose From and To together on step 1.

## v6.9.4 — rider profile and role reports

- **Demo:** no registration fee. Sellers and agents tap "Create my account", and all roles are active straight away.
- **Rider statement:** a formatted A4 PDF for this month, last month, the last 90 days or all deliveries. It has a header, summary boxes, one row per delivery (gross, fee, Social Fund, bonus, net), the column header repeated on every page, totals and page numbers. It's shared through the share sheet.
- **Zone & availability:** a new "Neighbourhoods you cover" section. Riders pick a city, then up to 10 neighbourhoods or **Whole city**, which means every neighbourhood in that city (`riderAreas.wholeCity`; parcel dispatch honours it).
- **Monthly reports by role:**
  - Riders get net earnings from their own deliveries, split into marketplace orders and parcels, plus where the money went, busiest areas, best day, and rider tips.
  - Agents get net commission at each seller's rate, sales made for sellers, active sellers, sellers onboarded, listings created, commission by seller, and agent tips.
  - Sellers keep their sales report.
  - Highlights and tips titles are role-specific, and agents can open the report from their home screen.
- **TPIN:** exactly 10 digits, numbers only. The app drops letters, spaces and symbols as you type and shows an x/10 counter; the server rejects anything else (`tpinProblem`).
- **Rider tab bar:** fixed on every screen, including during turn-by-turn navigation and while a job offer is showing.

## v6.9 — growth tools, rider safety, smart dispatch

- **AI Ad Generator** (`ad_generate`, `adgen.ts`):
  - The seller uploads one product photo.
  - Gemini reads it and writes a description plus WhatsApp Status, Facebook and TikTok copy with hashtags.
  - Cloudinary composes a 9:16 and a 1:1 promotional image around the real photo: headline, price badge, optional offer and "Order on SeloNaChipa". The product itself is never redrawn.
  - Limit: 20 ads per seller per day.
- **Offline browsing** (`OfflineCatalog`):
  - Every product loaded is kept on the phone (400 newest).
  - With no connection, the feed, home rails, search, product and shop pages fall back to it, and an "You're offline" banner shows. Photos come from the image cache.
- **Rider Safety Centre** (`safety.ts`, rider app → More → Safety Centre, plus an SOS button on the active delivery screen):
  - SOS texts the emergency contact a live-location link (`/s/{token}`) and alerts operations (back office → Rider safety).
  - Incident location sharing.
  - Verified rider ID with a QR code that opens `/v/{code}`.
  - Geotagged delivery evidence photos.
  - Optional accident cover is OFF until an insurer is confirmed. Finance enters the insurer in back office → Rider cover (insurer): registered name, group policy number, claims phone and email, policy terms link, premium per delivery, cover amount and benefits. Then they switch it on (`admin_setRiderCover`, audited).
    - Switching on is refused while any insurer field is missing.
    - Riders can opt in only while cover is on, and only after accepting the policy terms.
    - Premiums are deducted only while cover is on and the rider has opted in. They're held for the insurer in `riderCoverPremiums` for finance to remit, and aren't counted as platform revenue.
    - Switching off stops all deductions immediately.
    - Paying premiums over: Finance ticks unpaid premiums on the Rider cover page, enters the bank or mobile money transfer reference and the amount sent, then clicks "Mark as paid" (`admin_markPremiumsRemitted`).
      - The server accepts it only if every selected premium is still unpaid, all are for one insurer, and the amount equals their total.
      - Each payment is saved as a `coverRemittances` record and audited.
  - Seven short safety lessons.
- **Smart dispatch** (`dispatch.ts`):
  - Each rider gets a 0–100 score: distance to the seller (30), distance to the buyer (10), fit with their current route (15), availability and GPS freshness (15), acceptance and completion history (20), and estimated pickup time (10).
  - Jobs go out in waves: top 3, then top 8 a minute later, then everyone eligible.
  - The top 10 scores are stored on each job.
- **Monthly performance reports** (`reports.ts`):
  - Sellers see sales, earnings, orders, items, average order, cancellations, deal share and top products. Riders see earnings, deliveries, km, per-delivery average, Social Fund, acceptance and completion.
  - Each report compares with the month before and adds highlights and tips.
  - Saved on the 1st of each month with a notification; the current month is available to date.
- **Marketing Centre** (`marketing.ts`, `pricing.ts`):
  - Discount campaigns, Buy One Get One Free, limited-time offers (up to 7 days, optional unit cap) and 2–5 product bundles.
  - Prices are worked out on the server in both the checkout quote and order creation, so buyers pay exactly what they see. Products show a deal badge with a countdown.
  - Listing discounts are now honoured at checkout too (previously only the app showed them).
- Community broker hub "Katanjinjin Zone" is now **Kamwala Shopping Centre**.

## v6.8 — corporate sign-up, reachable buttons, smarter location picker

- **Sellers and agents: Individual or Corporate.**
  - Individuals give only their ID; individual agents must use their NRC. There's no certificate step.
  - Corporates give only the organisation's details: registered name, PACRA registration number, 10-digit TPIN and registered address, plus the certificate of incorporation. Nothing personal is asked.
  - The server checks the same rules (`corporateOf` / `identityOf` in `auth.ts`). The registration number is the unique identity for a corporate account.
  - The back office KYC review shows the account type, registration number, TPIN and address.
- **Layout:**
  - Every screen now keeps its content above the phone's navigation bar and the keyboard, so Log out and other bottom buttons are fully tappable.
  - Bottom-tab tap areas are larger.
- **Location picker:**
  - "Use my location" keeps reading GPS until the fix is within about 25 m, or 12 s pass, and keeps the phone's own coordinates.
  - A rough fix (over 60 m) opens the map on that spot so the user can nudge the pin.
  - The app asks for precise location when only approximate was allowed.
  - While typing, saved and recently used places that match appear instantly. Google suggestions are biased to within 12 km of the user.
  - Stored suggestions must match every typed word, and the nearest come first.
  - Every confirmed place is remembered on the phone (40 places) and shown as "Recently used". "Save as Home / Work / Shop / Other" is one tap after confirming.

## v6.7 — addresses, traffic-aware routes, shops and receipts

- **Use my location** uses the Fused Location Provider. It asks to turn GPS on when it is off, then tries a fresh high-accuracy fix (15 s), the last known fix, and finally the platform LocationManager.
- **Address search** biases Places Autocomplete (New) to the user's position and shows "x km from you" on every suggestion. These distances are straight-line; delivery fees still use the road distance from the Routes API.
- **Parcels** start from the sender's current location automatically. Both ends are editable as **From (pickup)** and **To (delivery)**, and the fee uses the From→To road distance.
- **Landmarks**: reverse geocoding picks a prominent building or place within 10 m of the pin. A saved address within 10 m wins.
- **Rider routes**: traffic-aware Routes with alternatives. The shortest route is picked; within 3 %, the faster one wins. The rider sees:
  - traffic colouring (green, yellow, red);
  - the delay and ETA;
  - a Yango-style light map with ring start and end markers and an ETA bubble;
  - Hide and overview buttons.
  These routes bill at the Routes Pro SKU. The `routesTrafficAware` config switches them off.
- **Mobile money logos** appear beside every wallet and phone-number field.
- **Selo Ledger** replaces Selo Wallet for sellers, riders and agents. Buyers have no wallet.
- **Shops**: buyers see the registered business name, kept in sync on rename by `onSellerProfileChange`. Tapping a shop, its map pin or the seller name on a feed card opens the shop page and its live products (`shop_profile`).
- **Profile pictures and banners**: every user can have a profile picture. Sellers must upload a shop banner at sign-up.
- **Receipts**: every confirmed payment gets a receipt issued by SeloNaChipa on behalf of the seller, agent or rider. This covers orders, parcels, Boost or Story, and registration.
  - Receipt numbers are deterministic (`SNC-R-YYMMDD-XXXXXX`).
  - Each receipt has a public verification page at `/r/{token}`.
  - The app shares it as a PDF via WhatsApp or email.

## v6.5 — Google Maps, Beem OTP/SMS, Lipila production payments, Listing Studio

Full architecture, data model, cost controls, monitoring and the deployment checklist are in
[`docs/ARCHITECTURE-v6.5.md`](docs/ARCHITECTURE-v6.5.md). In short:
- **Google Maps:** only Places API (New), Geocoding, Routes and Maps SDK for Android — anything else is blocked in code.
  Delivery fee = K45 + K5.50/km on road distance (admin-configurable). Rider routes only after acceptance, cached,
  re-routed only after real deviation. Live buyer tracking, Yango-style pin picker, "Shops near you" with clustering.
  Run `scripts/maps-setup.sh` to enable the four APIs and create restricted keys.
- **Beem:** sign-up OTP with SMS auto-read on Android, transactional SMS templates, logs and a Super Admin monitor.
- **Lipila:** production client, Standard-Webhooks signature verification with idempotency, full-balance withdrawals
  with locks, reconciliation and manual review, and a Super Admin transaction monitor.
- **Listing Studio:** Gemini + Deepgram + ElevenLabs + Cloudinary + Runway turn seller videos and photos into
  branded vertical clips with voiceover and captions (originals kept, labelled AI-enhanced).

## v6.4 — Parcels v2, delivery codes, addresses, account and support

**Parcels (same city, up to 5 kg, 20 cities)**
- Fees: delivery = K30 + K4.50/km (K30 minimum); sending fee = 15% of delivery, at least K10 and at most 50% of delivery; the rider gets 89% of the delivery fee, the platform 10% and the Social Fund 1%. Optional insurance at 2% of declared value (minimum K5, up to K5,000).
- Three-step sender flow (route → receiver → price and pay), payment held in escrow, broadcast to riders in the pickup neighbourhood, 4-digit pickup PIN, public tracking link for the receiver.

**Delivery code (parcels and marketplace orders)**
- The rider asks for a code only inside a 150 m geofence. The server makes a random 5-digit code, stores only its HMAC, and sends it to the receiver by push (SMS if the push isn't acknowledged in 30 s). The rider never sees it.
- 15-minute expiry, 5 attempts then lock, 2-minute cooldown, at most 3 codes per delivery; every attempt is logged with GPS; riders with 3 lockouts in 24 h are flagged.
- A correct code releases escrow in one idempotent transaction (append-only `escrow_ledger`). DELIVERED becomes COMPLETED after the dispute window. Receiver-unavailable and DELIVERY_FAILED flows and admin override (reason + evidence, audited) are included.
- **Change from 6.3:** sellers are now paid when the buyer's code is verified (`sellerReleaseOn: "code"`), not at pickup. Minimum withdrawal is K20 and withdrawals need the PIN.

**Addresses:** one shared address field everywhere — live autocomplete (saved addresses, the shared platform store, Google Places, then the neighbourhood gazetteer), "Use my location", and "Pick on map" (OpenStreetMap tiles via osmdroid, no key needed). Fees use road distance (Distance Matrix) and fall back to straight line × 1.3, labelled "approximate".

**Account:** Log In / Sign Up tabs with SMS OTP before registration, Forgot PIN with an expiring reset, Change PIN, view/update security questions (re-authentication required, answers hashed), saved addresses, notification settings, and Help & support (FAQ, contact form, threaded replies) for every role.

**Shopping:** Add to cart (with quantity) and Buy on every product card and detail sheet; the cart persists across restarts and flags out-of-stock items; checkout shows the distance-based delivery fee before you pay.

**Back office:** Parcels (detail, ledger, override, refund, change receiver), Escrow ledger with CSV export, Delivery-code monitor, Override log, Support inbox, a "Parcels v2" config group, and the verification record on disputes.

**SMS:** set `SMS_PROVIDER` (`africastalking`, `http` or `log`), `SMS_USERNAME`, `SMS_SENDER_ID` and, for `http`, `SMS_HTTP_URL` in `functions/.env.<projectId>`. With `log` nothing is sent — receivers without the app won't get codes.

## What is in the box

**Android** (`android/app/src/main/java/selo/Nachipa01`)
- Welcome and login with 5 auto-sliding sponsored adverts, mobile number + 5-digit PIN, no OTP.
- Registration wizards for all four roles:
  - Buyer: 6 steps.
  - Seller: 9 steps, including KYC, Lipila wallet name lookup and the K10 fee.
  - Agent: 8 steps, including operating hub and commission agreement.
  - Rider: 11 steps, including licence, vehicle, working hours, Social Fund consent and referral.
- Forgot PIN: 3 security questions checked together on the server, single-use 10-minute reset token, lockouts.
- Buyer:
  - Home (wallet card, tiles, escrow banner), feed and search.
  - Checkout with the escrow-lock explainer, and the escrow-locked confirmation.
  - Live tracking with an in-app delivery code.
  - Profile.
- SeloExpress parcels: quote, insurance (premium, `INS-XXXX-XXXX`) and tracking.
- Seller:
  - Dashboard.
  - AI listing flow: CameraX video (30 s) and photos (10), gallery and WhatsApp share, Gemini processing checklist, review and publish.
- Rider:
  - Online toggle and 15-second dispatch card.
  - Collect (releases the seller payout) and deliver with the buyer's code.
  - Social Fund with milestone progress.
- Agent home with pending and approved states.
- Shared `Validation` and `Fees` rules mirror `functions/src/domain.ts`, and both are unit tested.

**Back office** (`backoffice/src`)
- Overview with KPIs, the payout retry queue, live order states and the KYC queue.
- Users (suspend, manual PIN recovery) and KYC review with documents from Storage.
- Orders and parcels with the append-only escrow ledger.
- Riders and live map, and Zones.
- Escrow and payouts (retry, mark paid), Disputes (refund, release, split), and the Social Fund as a separate account with claims.
- Listings moderation, and Banners and content (edit the 5 welcome adverts live).
- Notifications.
- Configuration (every fee and limit, with version check and history), Reports (CSV), Audit log, and Admin users (role claims).

**Functions** (`functions/src`)
- `auth.ts`: register, login with PIN, recovery questions with enumeration protection, verify answers, reset PIN, change PIN, KYC submission, FCM tokens.
- `escrow.ts`: `order_create`, `parcel_create`, `lipila_webhook` (HMAC verified and idempotent), broadcast to zone riders, atomic accept, pickup (seller leg), delivery (rider leg + Social Fund + milestone unlock), refunds, withdrawals and the payout retry queue.
- `listings.ts`: Gemini listing generator and publish, with safety flagging.
- `admin.ts`: config, KYC, suspend, manual reset token, payouts, disputes, claims, moderation, notifications and roles. Every call is audited.
- `scheduled.ts`:
  - Scheduled jobs: broadcast timeout refund, 48 h auto-confirm, payout retry, daily agent settlement at 00:00 Lusaka time, and daily counters.
  - Triggers: config, zone and content changes.

## Tests

```bash
cd android && ./gradlew testDebugUnitTest      # domain rules: PIN, MSISDN, escrow split, insurance, commission
cd functions && npm test                        # auth rules, money rules, state machine, retry schedule, webhook signature
cd functions && npm run test:rules               # Firestore security rules in the emulator (needs Java)
cd backoffice && npm run build                  # type-check + production bundle
```

`android/app/src/test/.../ScreensScreenshotTest.kt` renders 89 screens with Robolectric and Roborazzi:

```bash
./gradlew testDebugUnitTest -Proborazzi.test.record=true
```

The PNGs are written to `app/build/outputs/roborazzi`.

## Before go-live

- **Lipila:** confirm the endpoint paths, field names and webhook signature header in `functions/src/lipila.ts` against the signed Lipila contract. They follow the PRD integration notes but have not been tested against the live API.
- **Gemini:** confirm the model name in `GEMINI_MODEL` and the per-request media size limits.
- **Google Maps:** set `MAPS_API_KEY` for Places autocomplete, road distances and turn-by-turn. Without it the app uses the neighbourhood gazetteer and approximate distances.
- **SMS gateway:** delivery codes and OTPs need a live SMS provider (`SMS_PROVIDER` + `SMS_API_KEY`) and an approved sender ID.
- **Open Network:** create `OPEN_API_JWT_SECRET` and `OPEN_API_ENC_KEY`, enable Email/Password sign-in for the developer portal, and set the Mabala connector's `SELONACHIPA_*` server variables (see v7.0 above).
- **Store and legal:** add the Terms, Privacy and Data Policy texts, the Play Store listing and the release signing keys (CI reads them from `SELO_KEYSTORE*` environment variables).
