/** Sample data so the back office runs without Firebase credentials (demo mode). Mirrors Firestore shapes. */
const ago = (min: number) => new Date(Date.now() - min * 60_000);
type Row = Record<string, unknown> & { id: string };

export const demo: Record<string, Row[]> = {
  "platformConfig": [{ id: "default", flatPlatformFeeKwacha: 5, buyerServiceFeePct: 0, riderPlatformFeePct: 8, socialFundPct: 5,
    socialFundMilestoneDeliveries: 0, heroBonusPct: 10, deliveryFeePerKm: 5, deliveryBaseFee: 5, paymentProcessingPct: 0, parcelBaseFee: 30, parcelPerKmRate: 4.5, sendingFeeRate: 15, minSendingFee: 10, parcelPlatformFeePct: 10, parcelSocialFundPct: 1, deliveryGeofenceM: 150, deliveryCodeExpiryMin: 15, deliveryCodeMaxAttempts: 5, disputeWindowHours: 24, parcelListingFee: 5,
    insurancePremiumPct: 2, insuranceMaxDeclaredValue: 20000, agentPlatformCutPct: 5, sellerRegistrationFee: 10, riderWithdrawalFee: 15,
    minWithdrawal: 50, broadcastTimeoutMin: 5, autoConfirmHours: 48, maxConcurrentDeliveries: 4, dispatchCountdownSec: 15,
    pinMaxAttempts: 5, pinLockMinutes: 30, sqMaxAttempts: 5, sqLockMinutes: 30, referralSharePct: 20, referralFlatBonusKwacha: 0,
    shareLinkExpiryDays: 3, minAppVersion: 1, boostLocalPerDay: 20, boostRegionalPerDay: 45, boostNationalPerDay: 90, storyPrice24h: 10,
    boostFrequencyCap: 3, boostSlotsPerPage: 2, unpaidOrderCancelMin: 30, version: 42, updatedBy: "chileshe@selonachipa.zm", updatedAt: ago(60 * 24 * 12) }],
  users: [
    { id: "u1", role: "BUYER", fullName: "Mwila Chanda", msisdn: "+260971234512", accountStatus: "ACTIVE", kycStatus: "NOT_REQUIRED", area: "Munali", district: "Lusaka", createdAt: ago(9000) },
    { id: "u2", role: "SELLER", fullName: "Bupe Mwanza", storeName: "Mama Bupe's Veg", msisdn: "+260977654334", accountStatus: "ACTIVE", kycStatus: "VERIFIED", area: "Soweto Market", district: "Lusaka", createdAt: ago(40000) },
    { id: "u3", role: "RIDER", fullName: "Chanda Mulenga", msisdn: "+260965551204", accountStatus: "ACTIVE", kycStatus: "VERIFIED", area: "Kabulonga", district: "Lusaka", vehicle: "Motorbike", createdAt: ago(60000) },
    { id: "u4", role: "RIDER", fullName: "Joseph Banda", msisdn: "+260955550707", accountStatus: "PENDING_VERIFICATION", kycStatus: "SUBMITTED", area: "Kabulonga", district: "Lusaka", vehicle: "Motorbike", createdAt: ago(120) },
    { id: "u5", role: "SELLER", fullName: "Bashi Tembo", storeName: "Tembo Electronics", msisdn: "+260962223311", accountStatus: "PENDING_KYC", kycStatus: "SUBMITTED", area: "Ndola CBD", district: "Ndola", createdAt: ago(180) },
    { id: "u6", role: "AGENT", fullName: "Chileshe Mulenga", storeName: "Chileshe Market Agency", msisdn: "+260961114458", accountStatus: "PENDING_KYC", kycStatus: "SUBMITTED", area: "Soweto Market", district: "Lusaka", createdAt: ago(240) },
    { id: "u7", role: "RIDER", fullName: "Grace Phiri", msisdn: "+260979990021", accountStatus: "ACTIVE", kycStatus: "REVERIFY", area: "Kitwe", district: "Kitwe", vehicle: "Bicycle", createdAt: ago(300000) },
  ],
  kycQueue: [
    { id: "u4", uid: "u4", role: "RIDER", status: "PENDING", documents: ["nrcFront", "nrcBack", "licenceFront", "licenceBack", "portrait", "vehicle"], submittedAt: ago(120) },
    { id: "u5", uid: "u5", role: "SELLER", status: "PENDING", documents: ["nrcFront", "nrcBack", "portrait", "businessReg"], submittedAt: ago(180) },
    { id: "u6", uid: "u6", role: "AGENT", status: "PENDING", documents: ["nrcFront", "nrcBack", "portrait"], submittedAt: ago(240) },
  ],
  orders: [
    { id: "SNC-4821311", state: "IN_TRANSIT", buyerName: "Mwila Chanda", sellerName: "Mama Bupe's Veg", riderName: "Chanda M.", totalNgwee: 24000, goodsNgwee: 20000, deliveryNgwee: 4000, zoneName: "Munali", isParcel: false, createdAt: ago(35), sellerPayoutStatus: "DISBURSED", riderPayoutStatus: "HELD" },
    { id: "SNC-4823012", state: "BROADCASTING", buyerName: "Lombe K.", sellerName: "Mama Bupe's Veg", totalNgwee: 19500, goodsNgwee: 16000, deliveryNgwee: 3500, zoneName: "Chilenje", isParcel: false, createdAt: ago(3) },
    { id: "PCL-2093177", state: "PICKED_UP", buyerName: "Mwila Chanda", sellerName: "Mwila Chanda", riderName: "Chanda M.", totalNgwee: 11200, goodsNgwee: 0, deliveryNgwee: 4200, zoneName: "Comesa", isParcel: true, isInsured: true, insuranceId: "INS-7KQ2-M9XA", declaredValueNgwee: 350000, createdAt: ago(25) },
    { id: "SNC-4810245", state: "PAYOUT_PENDING_RETRY", buyerName: "Natasha B.", sellerName: "Tembo Electronics", riderName: "Grace P.", totalNgwee: 249000, goodsNgwee: 245000, deliveryNgwee: 4000, zoneName: "Kitwe", isParcel: false, createdAt: ago(400) },
    { id: "SNC-4799001", state: "DELIVERED", buyerName: "Kunda M.", sellerName: "Comesa Fashions", riderName: "Chanda M.", totalNgwee: 15500, goodsNgwee: 12000, deliveryNgwee: 3500, zoneName: "Comesa", isParcel: false, createdAt: ago(1500) },
    { id: "SNC-4798866", state: "REFUNDED", buyerName: "Daliso P.", sellerName: "Kwacha Grill", totalNgwee: 10500, goodsNgwee: 7500, deliveryNgwee: 3000, zoneName: "Kabwata", isParcel: false, refundReason: "No rider was available", createdAt: ago(1800) },
  ],
  payouts: [
    { id: "SELO-SELR-SNC-4810245", ref: "SELO-SELR-SNC-4810245", leg: "seller", amountNgwee: 244500, msisdn: "+260962223311", status: "FAILED_RETRY", attempts: 3, lastError: "429 rate limit", nextAttemptAt: new Date(Date.now() + 15 * 60_000) },
    { id: "SELO-RIDW-u7-1795", ref: "SELO-RIDW-u7-1795", leg: "withdrawal", amountNgwee: 3920, msisdn: "+260979990021", status: "FAILED_RETRY", attempts: 5, lastError: "401 wallet inactive", nextAttemptAt: new Date(Date.now() + 60 * 60_000) },
    { id: "SELO-SELR-SNC-4821311", ref: "SELO-SELR-SNC-4821311", leg: "seller", amountNgwee: 19500, msisdn: "+260977654334", status: "DISBURSED", attempts: 0 },
  ],
  disputes: [{ id: "D-1042", orderId: "SNC-4799001", raisedBy: "Kunda M.", reason: "Wrong colour delivered", status: "OPEN", createdAt: ago(200) }],
  socialFundClaims: [{ id: "C-301", riderUid: "u3", riderName: "Chanda Mulenga", category: "Bike repair or accident", amountNgwee: 45000, description: "Chain and sprocket replacement", status: "PENDING", createdAt: ago(600) }],
  riderWallets: [{ id: "u3", socialFundNgwee: 2360, availableNgwee: 18600, completedDeliveries: 63, tier: "Rising" }, { id: "u7", socialFundNgwee: 980, availableNgwee: 3920, completedDeliveries: 22, tier: "Starter" }],
  riderPresence: [{ id: "u3", isOnline: true, activeCount: 2, zoneIds: ["kabulonga", "woodlands"] }, { id: "u7", isOnline: false, activeCount: 0, zoneIds: [] }],
  zones: [
    { id: "kabulonga", name: "Kabulonga", city: "Lusaka", province: "Lusaka", radiusKm: 2.5, isActive: true, centre: { lat: -15.409, lng: 28.333 } },
    { id: "woodlands", name: "Woodlands", city: "Lusaka", province: "Lusaka", radiusKm: 2.5, isActive: true, centre: { lat: -15.427, lng: 28.317 } },
    { id: "munali", name: "Munali", city: "Lusaka", province: "Lusaka", radiusKm: 2.5, isActive: true, centre: { lat: -15.389, lng: 28.377 } },
    { id: "soweto", name: "Soweto Market", city: "Lusaka", province: "Lusaka", radiusKm: 1.5, isActive: true, centre: { lat: -15.424, lng: 28.268 } },
    { id: "matero", name: "Matero", city: "Lusaka", province: "Lusaka", radiusKm: 3, isActive: false, centre: { lat: -15.373, lng: 28.247 } },
  ],
  listings: [
    { id: "l1", title: "Fresh tomatoes 5 kg", sellerName: "Mama Bupe's Veg", priceNgwee: 8000, category: "Fresh produce", status: "live", viewCount: 820, saveCount: 64 },
    { id: "l3", title: "Smartphone 128 GB", sellerName: "Tembo Electronics", priceNgwee: 245000, category: "Electronics", status: "live", viewCount: 2100, saveCount: 230 },
    { id: "l9", title: "Herbal supplement (unverified claims)", sellerName: "Unknown store", priceNgwee: 9000, category: "Beauty", status: "draft", viewCount: 0, saveCount: 0, flagged: true },
  ],
  moderationQueue: [{ id: "m1", uid: "u9", reason: "AI flagged unsafe content", paths: ["listing-uploads/u9/abc/0.jpg"], createdAt: ago(90) }],
  contentBlocks: [
    { id: "advert-1", type: "advert", slot: 1, active: true, title: "Fresh tomatoes, 20 kg crate", seller: "Mama Bupe's Veg · Soweto Market", priceNgwee: 18000, cta: "Shop now", category: "produce" },
    { id: "advert-2", type: "advert", slot: 2, active: true, title: "Smartphone 128 GB, 1-yr warranty", seller: "Bashi Tembo Electronics · Lusaka CBD", priceNgwee: 245000, cta: "Shop now", category: "electronics" },
    { id: "advert-3", type: "advert", slot: 3, active: true, title: "Nshima & chicken combo", seller: "Kwacha Grill · delivered in 30 min", priceNgwee: 7500, cta: "Order now", category: "food" },
    { id: "advert-4", type: "advert", slot: 4, active: true, title: "Solar lantern + phone charger kit", seller: "SunPower Zambia · Kitwe & Ndola", priceNgwee: 65000, cta: "Shop now", category: "solar" },
    { id: "advert-5", type: "advert", slot: 5, active: true, title: "Chitenge fabric, 6 yards", seller: "Comesa Fashions · 3 colours", priceNgwee: 12000, cta: "Shop now", category: "fashion" },
  ],
  auditLogs: [
    { id: "a1", action: "config.update", target: "platformConfig/default", before: { socialFundPct: 1.5 }, after: { socialFundPct: 2 }, actorEmail: "chileshe@selonachipa.zm", actorRole: "finance", ip: "41.60.x.x", at: ago(60 * 24 * 12) },
    { id: "a2", action: "config.update", target: "platformConfig/default", before: { socialFundMilestoneDeliveries: 25 }, after: { socialFundMilestoneDeliveries: 20 }, actorEmail: "natasha@selonachipa.zm", actorRole: "super_admin", ip: "41.60.x.x", at: ago(60 * 24 * 22) },
    { id: "a3", action: "kyc.approve", target: "users/u3", before: { kycStatus: "SUBMITTED" }, after: { kycStatus: "VERIFIED" }, actorEmail: "mutale@selonachipa.zm", actorRole: "compliance", ip: "41.60.x.x", at: ago(60 * 24 * 40) },
  ],
  admins: [
    { id: "ad1", email: "natasha@selonachipa.zm", role: "super_admin" },
    { id: "ad2", email: "chileshe@selonachipa.zm", role: "finance" },
    { id: "ad3", email: "mutale@selonachipa.zm", role: "compliance" },
  ],
  securityEvents: [{ id: "s1", type: "SECURITY_QUESTION_LOCKOUT", msisdn: "+260971110000", at: ago(45) }],
};

const W = { watchTimeRatio: 0.30, like: 0.20, share: 0.18, enquiry: 0.15, save: 0.08, notInterested: -0.25, categoryMatch: 0.05, sellerEngagement: 0.04 };
const cohort = (id: number, w = W, mix = { cohort: 0.4, trending: 0.3, recent: 0.2, exploration: 0.1 }, cat: Record<string, number> = {}) =>
  ({ cohortId: id, weights: w, mix, categoryShare: cat, typeBoost: { video: 1, photo: 1 }, targetSessionMin: 25 });
const cohortsNow = { "0": cohort(0, { ...W, watchTimeRatio: 0.34 }, { cohort: 0.39, trending: 0.29, recent: 0.2, exploration: 0.12 }), "1": cohort(1), "2": cohort(2, W, undefined, { Electronics: 0.81 }),
  "3": cohort(3, { ...W, share: 0.2 }), "4": cohort(4) };
demo.algorithmState = [
  { id: "current", version: 14, reason: "micro-tune 2026-11-25_1", cohorts: cohortsNow, centroids: [], variants: [0, 1, 2, 3].map((i) => ({ ...W, like: 0.2 + i * 0.01, share: 0.18 - i * 0.01 })), updatedAt: ago(90) },
  { id: "v000014", version: 14, reason: "micro-tune 2026-11-25_1", createdBy: "system", createdAt: ago(90), changes: [
    { rule: "session_below_target", cohort: 0, detail: "avg 19.4 min < target 25: watchTime +0.02, exploration +5%" },
    { rule: "category_skip_rate", cohort: 2, detail: "electronics skip rate 64% > 60%: retrieval share −10%" }] },
  { id: "v000013", version: 13, reason: "weekly macro-tune", createdBy: "system", createdAt: ago(60 * 24 * 2), changes: [
    { rule: "recluster", detail: "18,204 buyers into 20 cohorts" }, { rule: "ab_winner", detail: "variant v2 beat control (sessions 27.1 vs 24.3 min)" },
    { rule: "weak_categories", detail: "flagged for seller growth campaigns: Building materials, Services" }] },
  { id: "v000012", version: 12, reason: "micro-tune 2026-11-22_3", createdBy: "system", createdAt: ago(60 * 24 * 3), changes: [
    { rule: "share_spike", cohort: 3, detail: "video share rate 2.4× baseline: +8% retrieval for 24 h" }] },
  { id: "v000001", version: 1, reason: "seed: PRD default weights", createdBy: "seed", createdAt: ago(60 * 24 * 40), changes: [] },
];
demo.platformConfig.push({ id: "guardrails", enabled: true, sessionCapMinutes: 145, warnAtMinutes: 130, budgetMultiplier: 13, budgetWindowWeeks: 2, updatedBy: "seed", updatedAt: ago(60 * 24 * 40) });
const day = (d: number, base: number) => ({ id: `met_2026-11-${String(25 - d).padStart(2, "0")}_daily`, type: "daily", date: `2026-11-${String(25 - d).padStart(2, "0")}`,
  sessions: Math.round(base * 1.0), sessionMs: Math.round(base * (8 + (13 - d) * 0.9) * 60_000), events_listing_view: base * 22, events_like: base * 3, events_share: Math.round(base * 0.9),
  events_enquiry_open: Math.round(base * 1.1 + (13 - d) * 9), video_listing_view: base * 14, photo_listing_view: base * 8, video_like: Math.round(base * 2.3), photo_like: Math.round(base * 0.7),
  video_share: Math.round(base * 0.75), photo_share: Math.round(base * 0.15), video_enquiry_open: Math.round(base * 0.8), photo_enquiry_open: Math.round(base * 0.3) });
demo.adminMetrics = [...Array.from({ length: 14 }, (_, i) => day(i, 900 + (13 - i) * 60)),
  { id: "met_2026-11-23_weak_categories", type: "weak_categories", date: "2026-11-23", categories: ["Building materials", "Services"], median: 0.11 }];
demo.leads = [
  { id: "b1", uid: "u1", name: "Mwila Chanda", msisdn: "+260971234512", area: "Munali", district: "Lusaka", consent: true, likes: 4, saves: 2, enquiries: 1, listings: 3, lastActive: Date.now() - 3_600_000 },
  { id: "b2", uid: "u8", name: "Kunda Mumba", msisdn: "+260966661234", area: "Kabwata", district: "Lusaka", consent: false, likes: 2, saves: 0, enquiries: 2, listings: 2, lastActive: Date.now() - 86_400_000 },
  { id: "b3", uid: "u9", name: "Natasha Banda", msisdn: "+260955559876", area: "Chilenje", district: "Lusaka", consent: true, likes: 1, saves: 1, enquiries: 0, listings: 1, lastActive: Date.now() - 2 * 86_400_000 },
];

// v6.2 paid promotions and platform revenue
const tg = (x: Record<string, unknown>) => ({ ageBands: [], provinces: [], districts: [], interests: [], searchIntent: true, followers: true, ...x });
demo.promotions = [
  { id: "p7Qm2LxA9cRt", type: "boost", listingId: "l3", listingTitle: "Smartphone 128 GB", sellerName: "Tembo Electronics", reach: "REGIONAL", days: 7, priceNgwee: 31_500,
    walletMsisdn: "+260977412233", status: "ACTIVE", targeting: tg({ provinces: ["Lusaka", "Copperbelt"], ageBands: ["18–24", "25–34"], interests: ["Phones & accessories", "Electronics"] }),
    estimatedReach: 14_200, stats: { impressions: 9_840, clicks: 612, likes: 301, enquiries: 88 }, createdAt: ago(60 * 26), startsAt: ago(60 * 26), endsAt: new Date(Date.now() + 5 * 86_400_000), paidRef: "SELO-BST-p7Qm2LxA9cRt" },
  { id: "s3Hk8VbN1wQe", type: "story", listingId: "l1", listingTitle: "Fresh tomatoes 5 kg", sellerName: "Mama Bupe's Veg", reach: "LOCAL", days: 1, priceNgwee: 1_000,
    walletMsisdn: "+260955301122", status: "ACTIVE", targeting: tg({ provinces: ["Lusaka"], districts: ["Lusaka", "Chilanga"], interests: ["Fresh produce"] }),
    estimatedReach: 2_300, stats: { impressions: 1_480, clicks: 212, likes: 97, enquiries: 31 }, createdAt: ago(300), startsAt: ago(300), endsAt: new Date(Date.now() + 19 * 3_600_000), paidRef: "SELO-STY-s3Hk8VbN1wQe" },
  { id: "q2Wf6TyU4pLs", type: "boost", listingId: "l1", listingTitle: "Fresh tomatoes 5 kg", sellerName: "Mama Bupe's Veg", reach: "LOCAL", days: 3, priceNgwee: 6_000,
    walletMsisdn: "+260966778899", status: "PAYMENT_FAILED", failureReason: "Insufficient funds on MTN Money 0966 ••• 899.", targeting: tg({ districts: ["Lusaka"] }),
    estimatedReach: 1_900, stats: { impressions: 0, clicks: 0, likes: 0, enquiries: 0 }, createdAt: ago(40), collectionRef: "SELO-BST-q2Wf6TyU4pLs" },
  { id: "e9Rr5DsF3gHj", type: "boost", listingId: "l3", listingTitle: "Smartphone 128 GB", sellerName: "Tembo Electronics", reach: "NATIONAL", days: 3, priceNgwee: 27_000,
    walletMsisdn: "+260977412233", status: "ENDED", targeting: tg({}), estimatedReach: 41_000, stats: { impressions: 30_112, clicks: 1_402, likes: 690, enquiries: 141 },
    createdAt: ago(60 * 24 * 9), startsAt: ago(60 * 24 * 9), endsAt: ago(60 * 24 * 6), paidRef: "SELO-BST-e9Rr5DsF3gHj" },
];
const ops = ["Airtel Money", "MTN Money", "Zamtel Money"];
demo.platformRevenue = Array.from({ length: 14 }, (_, d) => [
  ...Array.from({ length: 2 + (d % 4) }, (_, i) => ({ id: `rb_${d}_${i}`, source: "boost", amountNgwee: [6_000, 13_500, 31_500, 9_000][(d + i) % 4], operator: ops[(d + i) % 3], at: ago(60 * 24 * d + 30 + i * 70) })),
  ...Array.from({ length: 1 + (d % 3) }, (_, i) => ({ id: `rs_${d}_${i}`, source: "feed_story", amountNgwee: 1_000, operator: ops[(d + i + 1) % 3], at: ago(60 * 24 * d + 50 + i * 90) })),
  { id: `rf_${d}`, source: "order_fees", amountNgwee: 2_400 + (d * 517) % 3_000, operator: ops[d % 3], at: ago(60 * 24 * d + 120) },
]).flat();
demo.orders[1] = { ...demo.orders[1], paymentStatus: "PAID", walletMsisdn: "+260955301122" };
demo.orders.unshift({ id: "SNC-4823390", state: "INITIATED", buyerName: "Chipo N.", sellerName: "Tembo Electronics", totalNgwee: 252_470, goodsNgwee: 245_000,
  deliveryNgwee: 4_000, processingFeeNgwee: 2_470, zoneName: "Kalingalinga", isParcel: false, createdAt: ago(4), paymentStatus: "FAILED",
  paymentFailure: "Insufficient funds on Zamtel Money 0955 ••• 210.", walletMsisdn: "+260955120210" });


// v6.4 parcels, escrow ledger, delivery codes and support
const fees = (deliveryNgwee: number, extra: Record<string, number> = {}) => { const sending = Math.max(1_000, Math.min(Math.round(deliveryNgwee * .15), deliveryNgwee / 2));
  return { deliveryNgwee, sendingNgwee: sending, insuranceNgwee: 0, totalNgwee: deliveryNgwee + sending, riderEarningsNgwee: Math.round(deliveryNgwee * .89),
    platformFeeNgwee: Math.round(deliveryNgwee * .10), socialFundNgwee: deliveryNgwee - Math.round(deliveryNgwee * .89) - Math.round(deliveryNgwee * .10), ...extra }; };
const stop = (neighbourhood: string, address: string) => ({ neighbourhood, address, label: address });
demo.parcels = [
  { id: "PCL-7K2M9Q", status: "AWAITING_DELIVERY_CODE", cityId: "lusaka", cityName: "Lusaka", senderName: "Mwila Chanda", senderMsisdn: "+260971234512",
    receiver: { name: "Natasha Banda", msisdn: "+260955559876", uid: "u9" }, pickup: stop("Munali", "Plot 12, Great East Rd"), dropoff: stop("Chilenje", "House 44, Chilenje South"),
    weightBand: "1–3 kg", category: "Documents", description: "Signed tenancy papers", riderName: "Chanda Mulenga", riderPlate: "BAK 4471", riderUid: "u3", distanceKm: 9.4, distanceApproximate: false,
    fees: fees(7_230), escrowStatus: "HELD", codeSummary: { state: "sent", channel: "push", attempts: 1, codesIssued: 1 }, arrivedAt: ago(6), createdAt: ago(70) },
  { id: "PCL-3X8B1D", status: "AWAITING_DELIVERY_CODE", cityId: "lusaka", cityName: "Lusaka", senderName: "Tembo Electronics", senderMsisdn: "+260977412233",
    receiver: { name: "Kunda Mumba", msisdn: "+260966661234", uid: null }, pickup: stop("Cairo Road", "Shop 8, Cairo Rd"), dropoff: stop("Kabwata", "Flat 3B, Kabwata Estates"),
    weightBand: "Under 1 kg", category: "Electronics", description: "Phone charger", riderName: "Mutale Phiri", riderPlate: "ALB 902", riderUid: "u10", distanceKm: 5.1, distanceApproximate: true,
    fees: fees(5_295), escrowStatus: "HELD", codeSummary: { state: "locked", channel: "sms", attempts: 5, codesIssued: 2 }, arrivedAt: ago(22),
    unavailable: { reportedAt: ago(9), response: null }, createdAt: ago(95) },
  { id: "PCL-9P4R2T", status: "IN_TRANSIT", cityId: "kitwe", cityName: "Kitwe", senderName: "Bupe Mwanza", senderMsisdn: "+260977654334",
    receiver: { name: "Joseph Lungu", msisdn: "+260971110022", uid: null }, pickup: stop("Nkana East", "12 Freedom Ave"), dropoff: stop("Parklands", "7 Mpezeni Rd"),
    weightBand: "3–5 kg", category: "Food", description: "Groceries", riderName: "Chanda Mulenga", riderPlate: "BAK 4471", riderUid: "u3", distanceKm: 4.2,
    fees: fees(4_890, { insuranceNgwee: 500 }), escrowStatus: "HELD", createdAt: ago(40) },
  { id: "PCL-2H6N8C", status: "AWAITING_RIDER", cityId: "lusaka", cityName: "Lusaka", senderName: "Natasha Banda", senderMsisdn: "+260955559876",
    receiver: { name: "Mwila Chanda", msisdn: "+260971234512", uid: "u1" }, pickup: stop("Chilenje", "House 44, Chilenje South"), dropoff: stop("Woodlands", "Stand 210, Woodlands"),
    weightBand: "Under 1 kg", category: "Clothing", description: "Dress", distanceKm: 6.8, fees: fees(6_060), escrowStatus: "HELD", createdAt: ago(3) },
  { id: "PCL-5D1F7G", status: "COMPLETED", cityId: "lusaka", cityName: "Lusaka", senderName: "Mwila Chanda", senderMsisdn: "+260971234512",
    receiver: { name: "Kunda Mumba", msisdn: "+260966661234", uid: "u8" }, pickup: stop("Munali", "Plot 12, Great East Rd"), dropoff: stop("Kabwata", "Flat 3B, Kabwata Estates"),
    weightBand: "1–3 kg", category: "Documents", description: "School certificates", riderName: "Chanda Mulenga", riderUid: "u3", distanceKm: 11.2,
    fees: fees(8_040), escrowStatus: "RELEASED", codeSummary: { state: "verified", channel: "push", attempts: 1 },
    verification: { verifiedAt: ago(60 * 30), riderGeo: { lat: -15.4486, lng: 28.2865 }, positionSource: "client", channel: "push", attempts: 1 }, createdAt: ago(60 * 31) },
  { id: "PCL-8L3V6W", status: "DELIVERY_FAILED", cityId: "ndola", cityName: "Ndola", senderName: "Tembo Electronics", senderMsisdn: "+260977412233",
    receiver: { name: "Grace Zulu", msisdn: "+260965000111", uid: null }, pickup: stop("Ndola CBD", "Broadway Rd"), dropoff: stop("Kansenshi", "18 Kansenshi Rd"),
    weightBand: "Under 1 kg", category: "Electronics", description: "Earphones", riderName: "Mutale Phiri", riderUid: "u10", distanceKm: 3.6,
    fees: fees(4_620), escrowStatus: "HELD_PENDING_ADMIN", failureReason: "receiver_unavailable", returnStatus: "RETURN_TO_SENDER", failedAt: ago(60 * 5), createdAt: ago(60 * 7),
    codeSummary: { state: "expired", channel: "sms", attempts: 0 } },
];
demo.escrow_ledger = demo.parcels.filter((p) => p.escrowStatus !== "PENDING").flatMap((p) => {
  const f = p.fees as Record<string, number>; const at = p.createdAt as Date;
  const held = [["delivery_fee", f.deliveryNgwee], ["sending_fee", f.sendingNgwee], ...(f.insuranceNgwee ? [["insurance", f.insuranceNgwee]] : [])]
    .map(([leg, a], i) => ({ id: `lh_${p.id}_${i}`, kind: "parcel", orderId: p.id, type: "HELD", leg, party: "escrow", amountNgwee: a, override: false, by: "system", at: new Date(at.getTime() + 60_000) }));
  const rel = p.escrowStatus !== "RELEASED" ? [] : [["delivery_fee", "rider", f.riderEarningsNgwee], ["delivery_fee", "platform", f.platformFeeNgwee], ["delivery_fee", "rider_social_fund", f.socialFundNgwee], ["sending_fee", "platform", f.sendingNgwee]]
    .map(([leg, party, a], i) => ({ id: `lx_${p.id}_${i}`, kind: "parcel", orderId: p.id, type: "RELEASED", leg, party, amountNgwee: a, override: false, by: "system", at: ago(60 * 30) }));
  return [...held, ...rel];
});
demo.escrow_ledger.push({ id: "lo_1", kind: "order", orderId: "SNC-4811020", type: "HELD", leg: "goods", party: "escrow", amountNgwee: 45_000, override: false, by: "system", at: ago(60 * 3) });
demo.deliveryCodeAttempts = [
  { id: "ca1", kind: "parcel", orderId: "PCL-3X8B1D", action: "request", result: "issued", lat: -15.4352, lng: 28.2861, at: ago(21) },
  ...[1, 2, 3, 4, 5].map((n) => ({ id: `ca${n + 1}`, kind: "parcel", orderId: "PCL-3X8B1D", action: "verify", result: n === 5 ? "locked" : "wrong", lat: -15.4352, lng: 28.2861, at: ago(20 - n * 2) })),
  { id: "ca9", kind: "parcel", orderId: "PCL-7K2M9Q", action: "request", result: "issued", lat: -15.4544, lng: 28.3062, at: ago(5) },
  { id: "ca10", kind: "parcel", orderId: "PCL-5D1F7G", action: "verify", result: "verified", lat: -15.4486, lng: 28.2865, at: ago(60 * 30) },
];
demo.riderFlags = [{ id: "u10", riderUid: "u10", reason: "repeated_code_lockouts", count24h: 3, status: "OPEN", flaggedAt: ago(15) }];
demo.admin_audit_log = [{ id: "al1", action: "parcel.edit_receiver", kind: "parcel", orderId: "PCL-9P4R2T", actorEmail: "chileshe@selonachipa.zm", reason: "Sender called: receiver's old number was off", at: ago(30) }];
demo.supportTickets = [
  { id: "t1", uid: "u1", role: "BUYER", reference: "SUP-A1B2C3", name: "Mwila Chanda", msisdn: "+260971234512", subject: "Rider says code is wrong", category: "Delivery",
    orderRef: "PCL-7K2M9Q", status: "OPEN", lastMessage: "I read the code from my app but the rider says it doesn't work.", lastFrom: "user", createdAt: ago(8), updatedAt: ago(8) },
  { id: "t2", uid: "u2", role: "SELLER", reference: "SUP-D4E5F6", name: "Bupe Mwanza", msisdn: "+260977654334", subject: "Payout not received", category: "Payments",
    orderRef: "", status: "AWAITING_USER", lastMessage: "We've retried the payout — please confirm it arrived.", lastFrom: "support", createdAt: ago(60 * 20), updatedAt: ago(60 * 2) },
];
demo["supportTickets/t1/messages"] = [{ id: "m1", from: "user", text: "I read the code from my app but the rider says it doesn't work.", at: ago(8) }];
demo["supportTickets/t2/messages"] = [{ id: "m2", from: "user", text: "My payout for yesterday's sale hasn't arrived.", at: ago(60 * 20) },
  { id: "m3", from: "support", agent: "Chileshe", text: "We've retried the payout — please confirm it arrived.", at: ago(60 * 2) }];
demo.disputes = demo.disputes.map((d) => ({ ...d, kind: "order" }));
demo.disputes.push({ id: "D-1051", kind: "parcel", orderId: "PCL-5D1F7G", raisedBy: "Kunda M.", reason: "not_received", description: "Says delivered but I never got it.", status: "OPEN", createdAt: ago(60 * 2),
  verification: { verifiedAt: ago(60 * 30), riderGeo: { lat: -15.4486, lng: 28.2865 }, positionSource: "client", channel: "push", attempts: 1 }, codeSummary: { state: "verified" },
  codeAttempts: [{ result: "verified", lat: -15.4486, lng: 28.2865, at: ago(60 * 30) }] });

const listeners = new Set<() => void>();
export const demoSubscribe = (fn: () => void) => { listeners.add(fn); return () => { listeners.delete(fn); }; };
export const demoChanged = () => listeners.forEach((l) => l());

export function demoPatch(coll: string, id: string, patch: Record<string, unknown>) {
  const rows = demo[coll] ?? (demo[coll] = []);
  const i = rows.findIndex((r) => r.id === id);
  if (i >= 0) rows[i] = { ...rows[i], ...patch }; else rows.push({ id, ...patch });
  demo[coll] = [...rows];
  demoChanged();
}

export function demoAudit(action: string, target: string, before: unknown, after: unknown) {
  demo.auditLogs = [{ id: `a${Date.now()}`, action, target, before, after, actorEmail: "you (demo)", actorRole: "super_admin", ip: "demo", at: new Date() }, ...demo.auditLogs];
}
