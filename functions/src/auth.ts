import { createHash, randomBytes, randomUUID } from "node:crypto";
import bcrypt from "bcryptjs";
import { getAuth } from "firebase-admin/auth";
import { FieldValue, Timestamp } from "firebase-admin/firestore";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { LIPILA_API_KEY, db, loadConfig } from "./config";
import { SECURITY_QUESTION_BANK } from "./domain-defaults";
import { normaliseAnswer, normaliseMsisdn, pinProblem, kwachaToNgwee } from "./domain";
import { lipila } from "./lipila";
import { consumeOtpToken } from "./account";
import { AppRole, requireUser, securityEvent, str } from "./util";

/**
 * PIN + security-question authentication. No SMS OTP is sent anywhere (Auth Redesign, PRD §4).
 * Secrets live in users/{uid}/private/security, which no client can read.
 */
const ROLES: AppRole[] = ["BUYER", "SELLER", "AGENT", "RIDER"];
const sha256 = (s: string) => createHash("sha256").update(s).digest("hex");

/** Question bank = built-in 12 questions, with any active Firestore wording or extra questions on top. */
async function questionBank(): Promise<{ id: string; text: string }[]> {
  const docs = await db.collection("securityQuestionBank").where("active", "==", true).get().then((s) => s.docs).catch(() => []);
  const fromDb = new Map(docs.map((d) => [d.id, String(d.get("text_en") ?? "")]));
  const ids = [...new Set([...Object.keys(SECURITY_QUESTION_BANK), ...fromDb.keys()])];
  return ids.map((id) => ({ id, text: fromDb.get(id) || SECURITY_QUESTION_BANK[id] || id }));
}
const minutes = (m: number) => Timestamp.fromMillis(Date.now() + m * 60_000);

async function token(uid: string, role: AppRole) {
  return getAuth().createCustomToken(uid, { role });
}

export const auth_register = onCall({ secrets: [LIPILA_API_KEY], enforceAppCheck: false }, async (req) => {
  const d = req.data ?? {};
  const role = d.role as AppRole;
  if (!ROLES.includes(role)) throw new HttpsError("invalid-argument", "Choose a role.");
  const msisdn = normaliseMsisdn(d.msisdn);
  if (!msisdn) throw new HttpsError("invalid-argument", "Enter a valid Airtel, MTN or Zamtel number.");
  const pinErr = pinProblem(d.pin);
  if (pinErr) throw new HttpsError("invalid-argument", pinErr);
  if ((await loadConfig()).signupOtpRequired && !(await consumeOtpToken(msisdn, d.otpToken)))
    throw new HttpsError("failed-precondition", "Confirm your number with the SMS code first.");
  const qs = Array.isArray(d.questions) ? d.questions as { questionId: string; answer: string }[] : [];
  if (qs.length !== 3 || new Set(qs.map((q) => q.questionId)).size !== 3) throw new HttpsError("invalid-argument", "Choose 3 different security questions.");
  const valid = new Set((await questionBank()).map((q) => q.id));
  if (qs.some((q) => !valid.has(q.questionId))) throw new HttpsError("invalid-argument", "Unknown security question.");
  if (qs.some((q) => normaliseAnswer(String(q.answer ?? "")).length < 2)) throw new HttpsError("invalid-argument", "Answers must be at least 2 characters.");

  const profile = (d.profile ?? {}) as Record<string, unknown>;
  const uid = randomUUID();
  const status = role === "BUYER" ? "ACTIVE" : role === "RIDER" ? "PENDING_VERIFICATION" : "PENDING_KYC";
  const fullName = String(profile.fullName ?? [profile.firstName, profile.otherName, profile.surname].filter(Boolean).join(" ")).slice(0, 120);
  const payoutWallet = normaliseMsisdn(d.payoutWallet);
  const referralCode = randomBytes(4).toString("hex").toUpperCase();
  let referredBy: string | null = null;
  if (typeof d.referralCode === "string" && d.referralCode.trim()) {
    const r = await db.collection("users").where("referralCode", "==", d.referralCode.trim().toUpperCase()).limit(1).get();
    referredBy = r.docs[0]?.id ?? null;
  }

  const [pinHash, answerHashes] = await Promise.all([
    bcrypt.hash(d.pin, 12),
    Promise.all(qs.map(async (q) => ({ questionId: q.questionId, answerHash: await bcrypt.hash(normaliseAnswer(q.answer), 12) }))),
  ]);

  await db.runTransaction(async (tx) => {
    const idx = db.doc(`msisdnIndex/${msisdn}`);
    if ((await tx.get(idx)).exists) throw new HttpsError("already-exists", "This number is already registered. Log in or contact support.");
    tx.create(idx, { uid, role, createdAt: FieldValue.serverTimestamp() });
    tx.create(db.doc(`users/${uid}`), {
      role, msisdn, fullName, firstName: String(profile.firstName ?? fullName.split(" ")[0] ?? ""),
      accountStatus: status, kycStatus: role === "BUYER" ? "NOT_REQUIRED" : "SUBMITTED",
      storeName: profile.storeName ?? null, area: profile.area ?? profile.hub ?? null, province: profile.province ?? null,
      district: profile.district ?? null, landmark: profile.landmark ?? null,
      geo: profile.lat ? { lat: Number(profile.lat), lng: Number(profile.lng) } : null,
      categories: profile.categories ?? [], vehicle: profile.vehicleType ?? null,
      vehicleDetails: role === "RIDER" ? { make: profile.make ?? null, model: profile.model ?? null, plate: profile.plate ?? null } : null,
      hub: profile.hub ?? null, workingDays: profile.workingDays ?? null, businessType: profile.businessType ?? null,
      payoutWallet, payoutWalletNetwork: profile.walletNetwork ?? null, referralCode, referredBy,
      acceptedTermsVersion: d.acceptedTermsVersion ?? null, consentAt: FieldValue.serverTimestamp(),
      memberSince: new Date().toLocaleString("en-GB", { month: "short", year: "numeric" }), createdAt: FieldValue.serverTimestamp(),
    });
    tx.create(db.doc(`users/${uid}/private/security`), {
      pinHash, securityQuestions: answerHashes, failedPinAttempts: 0, pinLockedUntil: null, sqAttempts: 0, sqWindowStart: null, sqLockedUntil: null,
    });
    if (role === "RIDER") tx.create(db.doc(`riderWallets/${uid}`), { availableNgwee: 0, socialFundNgwee: 0, completedDeliveries: 0, activeCount: 0,
      acceptanceRate: 100, tier: "Starter", todayNetNgwee: 0, todayDeliveries: 0, weekFund: [0, 0, 0, 0, 0, 0, 0] });
    if (role === "SELLER") tx.create(db.doc(`sellerStats/${uid}`), { earningsNgwee: 0, todayNgwee: 0, toPack: 0, liveListings: 0, lowStock: 0, monthNgwee: 0, monthOrders: 0 });
  });

  await getAuth().createUser({ uid, displayName: fullName });
  await getAuth().setCustomUserClaims(uid, { role });

  if ((role === "SELLER" || role === "AGENT") && payoutWallet) {
    const cfg = await loadConfig();
    const ref = `SELO-REG-${uid.slice(0, 8)}`;
    await db.doc(`registrationFees/${ref}`).set({ uid, amountNgwee: kwachaToNgwee(cfg.sellerRegistrationFee), status: "PENDING", createdAt: FieldValue.serverTimestamp() });
    await lipila.collect(ref, kwachaToNgwee(cfg.sellerRegistrationFee), payoutWallet, "SeloNaChipa registration fee").catch(() => undefined);
  }
  return { token: await token(uid, role), uid };
});

export const auth_loginWithPin = onCall(async (req) => {
  const msisdn = normaliseMsisdn(req.data?.msisdn);
  const generic = "Incorrect number or PIN.";
  if (!msisdn || typeof req.data?.pin !== "string") throw new HttpsError("invalid-argument", generic);
  const idx = await db.doc(`msisdnIndex/${msisdn}`).get();
  if (!idx.exists) throw new HttpsError("unauthenticated", generic);
  const { uid, role } = idx.data() as { uid: string; role: AppRole };
  const cfg = await loadConfig();
  const secRef = db.doc(`users/${uid}/private/security`);
  const sec = (await secRef.get()).data()!;
  const locked = sec.pinLockedUntil as Timestamp | null;
  if (locked && locked.toMillis() > Date.now())
    throw new HttpsError("resource-exhausted", `Too many attempts. Locked until ${locked.toDate().toLocaleTimeString("en-GB", { timeZone: "Africa/Lusaka", hour: "2-digit", minute: "2-digit" })}.`);
  const user = (await db.doc(`users/${uid}`).get()).data()!;
  if (user.accountStatus === "SUSPENDED") throw new HttpsError("permission-denied", "This account is suspended. Contact support.");

  if (!(await bcrypt.compare(req.data.pin, sec.pinHash))) {
    const failed = (sec.failedPinAttempts ?? 0) + 1;
    const lock = failed >= cfg.pinMaxAttempts;
    await secRef.update({ failedPinAttempts: lock ? 0 : failed, pinLockedUntil: lock ? minutes(cfg.pinLockMinutes) : null });
    if (lock) await securityEvent("PIN_LOCKOUT", { uid, msisdn });
    throw new HttpsError("unauthenticated", lock ? `Too many attempts. Locked for ${cfg.pinLockMinutes} minutes.`
      : `${generic} ${cfg.pinMaxAttempts - failed} attempts left.`);
  }
  await secRef.update({ failedPinAttempts: 0, pinLockedUntil: null });
  await getAuth().revokeRefreshTokens(uid); // one active device session per account
  await db.doc(`users/${uid}`).update({ lastLoginAt: FieldValue.serverTimestamp() });
  await securityEvent("LOGIN", { uid, ip: req.rawRequest.ip ?? null });
  return { token: await token(uid, role) };
});

/** Returns the user's own 3 questions. Unknown numbers get 3 plausible questions so numbers can't be enumerated. */
export const auth_getRecoveryQuestions = onCall(async (req) => {
  const msisdn = normaliseMsisdn(req.data?.msisdn);
  if (!msisdn) throw new HttpsError("invalid-argument", "Enter a valid number.");
  const bank = await questionBank();
  const text = (id: string) => bank.find((b) => b.id === id)?.text ?? SECURITY_QUESTION_BANK[id] ?? id;
  const idx = await db.doc(`msisdnIndex/${msisdn}`).get();
  if (!idx.exists) {
    const h = parseInt(sha256(msisdn).slice(0, 8), 16);
    const pick = [0, 1, 2].map((i) => bank[(h + i * 5) % bank.length]);
    return { hasQuestions: true, questions: pick.map((b) => ({ id: b.id, text: b.text })) };
  }
  const sec = (await db.doc(`users/${idx.get("uid")}/private/security`).get()).data();
  const qs = (sec?.securityQuestions ?? []) as { questionId: string }[];
  if (qs.length < 3) return { hasQuestions: false };
  return { hasQuestions: true, questions: qs.map((q) => ({ id: q.questionId, text: text(q.questionId) })) };
});

export const auth_verifySecurityAnswers = onCall(async (req) => {
  const msisdn = normaliseMsisdn(req.data?.msisdn);
  const answers = (req.data?.answers ?? []) as { questionId: string; answer: string }[];
  const fail = "One or more answers did not match. Try again.";
  if (!msisdn || answers.length !== 3) throw new HttpsError("invalid-argument", fail);
  const idx = await db.doc(`msisdnIndex/${msisdn}`).get();
  if (!idx.exists) { await bcrypt.hash("timing-equaliser", 12); throw new HttpsError("permission-denied", fail); }
  const uid = idx.get("uid") as string;
  const cfg = await loadConfig();
  const secRef = db.doc(`users/${uid}/private/security`);
  const sec = (await secRef.get()).data()!;
  const lockedUntil = sec.sqLockedUntil as Timestamp | null;
  if (lockedUntil && lockedUntil.toMillis() > Date.now())
    throw new HttpsError("resource-exhausted", `Too many failed attempts. Try again in ${Math.ceil((lockedUntil.toMillis() - Date.now()) / 60000)} minutes. If you are still locked out, contact support.`);

  const stored = sec.securityQuestions as { questionId: string; answerHash: string }[];
  const results = await Promise.all(stored.map((s) => {
    const a = answers.find((x) => x.questionId === s.questionId);
    return bcrypt.compare(normaliseAnswer(String(a?.answer ?? "")), s.answerHash);
  }));
  if (!results.every(Boolean)) {
    const windowStart = sec.sqWindowStart as Timestamp | null;
    const fresh = !windowStart || Date.now() - windowStart.toMillis() > cfg.sqLockMinutes * 60_000;
    const attempts = (fresh ? 0 : sec.sqAttempts ?? 0) + 1;
    const lock = attempts >= cfg.sqMaxAttempts;
    await secRef.update({ sqAttempts: lock ? 0 : attempts, sqWindowStart: fresh ? Timestamp.now() : windowStart,
      sqLockedUntil: lock ? minutes(cfg.sqLockMinutes) : null });
    if (lock) await securityEvent("SECURITY_QUESTION_LOCKOUT", { uid, msisdn, ip: req.rawRequest.ip ?? null });
    throw new HttpsError("permission-denied", lock ? `Too many failed attempts. Try again in ${cfg.sqLockMinutes} minutes.` : fail);
  }
  const raw = randomBytes(24).toString("hex");
  await secRef.update({ sqAttempts: 0, sqWindowStart: null, sqLockedUntil: null, resetTokenHash: sha256(raw), resetTokenExpires: minutes(10) });
  return { resetToken: `${uid}.${raw}` };
});

export const auth_resetPin = onCall(async (req) => {
  const [uid, raw] = String(req.data?.resetToken ?? "").split(".");
  const pinErr = pinProblem(req.data?.pin);
  if (pinErr) throw new HttpsError("invalid-argument", pinErr);
  if (!uid || !raw) throw new HttpsError("permission-denied", "Reset link expired. Start again.");
  const secRef = db.doc(`users/${uid}/private/security`);
  await db.runTransaction(async (tx) => {
    const sec = (await tx.get(secRef)).data();
    const exp = sec?.resetTokenExpires as Timestamp | undefined;
    if (!sec || sec.resetTokenHash !== sha256(raw) || !exp || exp.toMillis() < Date.now())
      throw new HttpsError("permission-denied", "Reset expired. Start again.");
    tx.update(secRef, { pinHash: await bcrypt.hash(req.data.pin, 12), resetTokenHash: FieldValue.delete(), resetTokenExpires: FieldValue.delete(),
      failedPinAttempts: 0, pinLockedUntil: null });
  });
  await getAuth().revokeRefreshTokens(uid);
  await securityEvent("PIN_RESET", { uid });
  return { ok: true };
});

export const auth_changePin = onCall(async (req) => {
  const { uid } = requireUser(req);
  const pinErr = pinProblem(req.data?.newPin);
  if (pinErr) throw new HttpsError("invalid-argument", pinErr);
  const secRef = db.doc(`users/${uid}/private/security`);
  const sec = (await secRef.get()).data()!;
  if (!(await bcrypt.compare(String(req.data?.currentPin ?? ""), sec.pinHash))) throw new HttpsError("permission-denied", "Current PIN is incorrect.");
  await secRef.update({ pinHash: await bcrypt.hash(req.data.newPin, 12) });
  await securityEvent("PIN_CHANGED", { uid });
  return { ok: true };
});

/** Records uploaded KYC image paths (kyc/{uid}/...) and puts the user in the compliance queue. */
export const auth_submitKyc = onCall(async (req) => {
  const { uid, role } = requireUser(req);
  const docs = (req.data?.documents ?? {}) as Record<string, string>;
  if (Object.values(docs).some((p) => !String(p).startsWith(`/kyc/${uid}/`) && !String(p).startsWith(`kyc/${uid}/`)))
    throw new HttpsError("invalid-argument", "Invalid document path.");
  await db.doc(`users/${uid}/private/kyc`).set({ documents: docs, submittedAt: FieldValue.serverTimestamp() }, { merge: true });
  await db.doc(`kycQueue/${uid}`).set({ uid, role, status: "PENDING", documents: Object.keys(docs), submittedAt: FieldValue.serverTimestamp() });
  return { ok: true };
});

export const user_registerFcmToken = onCall(async (req) => {
  const { uid } = requireUser(req);
  const t = str(req.data?.token, "token", 400);
  await db.doc(`users/${uid}/private/devices`).set({ fcmTokens: FieldValue.arrayUnion(t) }, { merge: true });
  return { ok: true };
});
