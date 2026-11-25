// Seeds reference data into the named database. Run once per project:
//   GOOGLE_APPLICATION_CREDENTIALS=sa.json node scripts/seed.mjs
// Against the emulator: FIRESTORE_EMULATOR_HOST=127.0.0.1:8080 GCLOUD_PROJECT=demo-selo node scripts/seed.mjs
import { initializeApp } from "firebase-admin/app";
import { getFirestore, FieldValue } from "firebase-admin/firestore";

initializeApp();
const db = getFirestore("ai-studio-611c950c-febe-47c1-afff-35dcb0788339");

const questions = {
  q_mother_maiden_name: "What is your mother's maiden name?",
  q_first_school: "What was the name of your first school?",
  q_favourite_dish: "What is your favourite traditional Zambian dish?",
  q_birth_town: "What is the name of the village or town where you were born?",
  q_childhood_nickname: "What was your childhood nickname?",
  q_best_friend: "What is the first name of your best friend growing up?",
  q_favourite_teacher: "What is the name of your favourite teacher?",
  q_first_phone: "What was the make of your first phone?",
  q_father_first_name: "What is your father's first name?",
  q_childhood_street: "What is the name of the street you grew up on?",
  q_football_team: "What is your favourite football team?",
  q_first_job: "What was your first job?",
};

const zones = [
  ["kabulonga", "Kabulonga", -15.409, 28.333], ["woodlands", "Woodlands", -15.427, 28.317], ["munali", "Munali", -15.389, 28.377],
  ["chilenje", "Chilenje", -15.446, 28.318], ["matero", "Matero", -15.373, 28.247], ["soweto", "Soweto Market", -15.424, 28.268],
  ["comesa", "Comesa Market", -15.417, 28.283], ["cbd", "Lusaka CBD", -15.416, 28.283], ["kabwata", "Kabwata", -15.437, 28.302],
];

const adverts = [
  ["Fresh tomatoes, 20 kg crate", "Mama Bupe's Veg · Soweto Market", 18000, "Shop now", "produce", 0xff7a0016, 0xffe2334d],
  ["Smartphone 128 GB, 1-yr warranty", "Bashi Tembo Electronics · Lusaka CBD", 245000, "Shop now", "electronics", 0xff1a1a2e, 0xff4b3b8f],
  ["Nshima & chicken combo", "Kwacha Grill · delivered in 30 min", 7500, "Order now", "food", 0xff9a3412, 0xfff59e0b],
  ["Solar lantern + phone charger kit", "SunPower Zambia · Kitwe & Ndola", 65000, "Shop now", "solar", 0xff065f46, 0xff0d9488],
  ["Chitenge fabric, 6 yards", "Comesa Fashions · 3 colours", 12000, "Shop now", "fashion", 0xff86198f, 0xffdb2777],
];

const batch = db.batch();
for (const [id, text] of Object.entries(questions)) batch.set(db.doc(`securityQuestionBank/${id}`), { text_en: text, active: true });
batch.set(db.doc("platformConfig/default"), {
  flatPlatformFeeKwacha: 5, buyerServiceFeePct: 0, riderPlatformFeePct: 8, socialFundPct: 5, socialFundMilestoneDeliveries: 0, heroBonusPct: 10,
  deliveryFeePerKm: 5, deliveryBaseFee: 5, paymentProcessingPct: 0, parcelListingFee: 5, insurancePremiumPct: 2,
  insuranceMaxDeclaredValue: 20000, agentPlatformCutPct: 5, sellerRegistrationFee: 10, riderWithdrawalFee: 15, minWithdrawal: 50,
  broadcastTimeoutMin: 5, autoConfirmHours: 48, maxConcurrentDeliveries: 4, dispatchCountdownSec: 15, pinMaxAttempts: 5,
  pinLockMinutes: 30, sqMaxAttempts: 5, sqLockMinutes: 30, referralSharePct: 20, referralFlatBonusKwacha: 0, shareLinkExpiryDays: 3,
  minAppVersion: 1, version: 1, updatedBy: "seed", updatedAt: FieldValue.serverTimestamp(),
}, { merge: true });
for (const [id, name, lat, lng] of zones) batch.set(db.doc(`zones/${id}`), { name, city: "Lusaka", province: "Lusaka", centre: { lat, lng }, radiusKm: 2.5, isActive: true });
adverts.forEach(([title, seller, priceNgwee, cta, category, colorA, colorB], i) =>
  batch.set(db.doc(`contentBlocks/advert-${i + 1}`), { type: "advert", slot: i + 1, active: true, title, seller, priceNgwee, cta, category, colorA, colorB,
    startsAt: null, endsAt: null, updatedBy: "seed" }));
["Fresh produce", "Groceries", "Fast food", "Electronics", "Fashion", "Home", "Beauty", "Hardware", "Baby"].forEach((c, i) =>
  batch.set(db.doc(`categories/${c.toLowerCase().replace(/\s+/g, "-")}`), { name: c, order: i, active: true }));
batch.set(db.doc("platformConfig/guardrails"), { enabled: true, sessionCapMinutes: 145, warnAtMinutes: 130, budgetMultiplier: 13, budgetWindowWeeks: 2, updatedBy: "seed" }, { merge: true });
batch.set(db.doc("platformConfig/default"), { aiTimeoutSec: 8, videoMinSec: 15, videoMaxSec: 60, maxPhotos: 4 }, { merge: true });
const W = { watchTimeRatio: 0.30, like: 0.20, share: 0.18, enquiry: 0.15, save: 0.08, notInterested: -0.25, categoryMatch: 0.05, sellerEngagement: 0.04 };
const cohort0 = { cohortId: 0, weights: W, mix: { cohort: 0.4, trending: 0.3, recent: 0.2, exploration: 0.1 }, categoryShare: {}, typeBoost: { video: 1, photo: 1 }, targetSessionMin: 25 };
const algo = { version: 1, cohorts: { "0": cohort0 }, centroids: [], variants: [W, W, W, W], reason: "seed: PRD default weights" };
batch.set(db.doc("algorithmState/v000001"), { ...algo, changes: [], createdBy: "seed", createdAt: FieldValue.serverTimestamp() });
batch.set(db.doc("algorithmState/current"), { ...algo, updatedAt: FieldValue.serverTimestamp() });
await batch.commit();
console.log("Seeded question bank, platform config, guardrails, algorithm v1, zones, adverts and categories.");
