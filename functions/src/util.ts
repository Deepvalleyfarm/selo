import { FieldValue } from "firebase-admin/firestore";
import { getMessaging } from "firebase-admin/messaging";
import { HttpsError, CallableRequest } from "firebase-functions/v2/https";
import { db } from "./config";

export type AppRole = "BUYER" | "SELLER" | "AGENT" | "RIDER";
export type AdminRole = "super_admin" | "operations" | "finance" | "compliance" | "support" | "moderator" | "analyst";

export function requireUser(req: CallableRequest, ...roles: AppRole[]): { uid: string; role: AppRole } {
  const uid = req.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Please log in again.");
  const role = req.auth?.token.role as AppRole | undefined;
  if (roles.length && (!role || !roles.includes(role))) throw new HttpsError("permission-denied", "This action isn't available for your account.");
  return { uid, role: role! };
}

/** Admin roles are custom claims set by a Super Admin. Read-only analysts are blocked here, not just in the UI. */
export function requireAdmin(req: CallableRequest, ...roles: AdminRole[]): { uid: string; role: AdminRole; email: string } {
  const role = req.auth?.token.admin as AdminRole | undefined;
  if (!req.auth || !role) throw new HttpsError("permission-denied", "Admin access required.");
  if (role !== "super_admin" && roles.length && !roles.includes(role)) throw new HttpsError("permission-denied", "Your admin role can't do this.");
  return { uid: req.auth.uid, role, email: (req.auth.token.email as string) ?? "" };
}

export async function audit(actor: { uid: string; email?: string; role?: string }, action: string, target: string,
  before: unknown, after: unknown, ip?: string) {
  await db.collection("auditLogs").add({ actorUid: actor.uid, actorEmail: actor.email ?? null, actorRole: actor.role ?? null,
    action, target, before: before ?? null, after: after ?? null, ip: ip ?? null, at: FieldValue.serverTimestamp() });
}

export async function securityEvent(type: string, data: Record<string, unknown>) {
  await db.collection("securityEvents").add({ type, ...data, at: FieldValue.serverTimestamp() });
}

/** In-app notification + FCM push to every registered device of a user. */
export async function notify(uid: string, title: string, body: string, type: string, deepLink?: string) {
  await db.collection(`notifications/${uid}/items`).add({ type, title, body, deepLink: deepLink ?? null, readAt: null,
    createdAt: FieldValue.serverTimestamp() });
  const tokens = ((await db.doc(`users/${uid}/private/devices`).get()).data()?.fcmTokens as string[] | undefined) ?? [];
  if (!tokens.length) return;
  await getMessaging().sendEachForMulticast({ tokens, notification: { title, body },
    data: { type, deepLink: deepLink ?? "" }, android: { priority: "high", notification: { channelId: "orders" } } }).catch(() => undefined);
}

export const str = (v: unknown, field: string, max = 200): string => {
  if (typeof v !== "string" || !v.trim()) throw new HttpsError("invalid-argument", `Missing ${field}.`);
  return v.trim().slice(0, max);
};
export const num = (v: unknown, field: string): number => {
  if (typeof v !== "number" || !Number.isFinite(v)) throw new HttpsError("invalid-argument", `Invalid ${field}.`);
  return v;
};
