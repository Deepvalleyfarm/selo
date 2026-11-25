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

`android/app/src/test/.../ScreensScreenshotTest.kt` renders 50 screens with Robolectric and Roborazzi:

```bash
./gradlew testDebugUnitTest -Proborazzi.test.record=true
```

The PNGs are written to `app/build/outputs/roborazzi`.

## Before go-live

- **Lipila:** confirm the endpoint paths, field names and webhook signature header in `functions/src/lipila.ts` against the signed Lipila contract. They follow the PRD integration notes but have not been tested against the live API.
- **Gemini:** confirm the model name in `GEMINI_MODEL` and the per-request media size limits.
- **Google Maps:** routing uses Google Maps navigation intents today. Embedding Maps SDK views and Directions API optimisation needs a Maps API key.
- **Store and legal:** add the Terms, Privacy and Data Policy texts, the Play Store listing and the release signing keys (CI reads them from `SELO_KEYSTORE*` environment variables).
