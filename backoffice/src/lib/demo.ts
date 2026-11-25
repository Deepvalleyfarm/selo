/** Sample data so the back office runs without Firebase credentials (demo mode). Mirrors Firestore shapes. */
const ago = (min: number) => new Date(Date.now() - min * 60_000);
type Row = Record<string, unknown> & { id: string };

export const demo: Record<string, Row[]> = {
  "platformConfig": [{ id: "default", flatPlatformFeeKwacha: 5, buyerServiceFeePct: 0, riderPlatformFeePct: 0, socialFundPct: 2,
    socialFundMilestoneDeliveries: 20, deliveryFeePerKm: 5, deliveryBaseFee: 5, paymentProcessingPct: 0, parcelListingFee: 5,
    insurancePremiumPct: 2, insuranceMaxDeclaredValue: 20000, agentPlatformCutPct: 5, sellerRegistrationFee: 10, riderWithdrawalFee: 15,
    minWithdrawal: 50, broadcastTimeoutMin: 5, autoConfirmHours: 48, maxConcurrentDeliveries: 4, dispatchCountdownSec: 15,
    pinMaxAttempts: 5, pinLockMinutes: 30, sqMaxAttempts: 5, sqLockMinutes: 30, referralSharePct: 20, referralFlatBonusKwacha: 0,
    shareLinkExpiryDays: 3, minAppVersion: 1, version: 42, updatedBy: "chileshe@selonachipa.zm", updatedAt: ago(60 * 24 * 12) }],
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
