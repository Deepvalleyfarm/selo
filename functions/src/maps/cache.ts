/**
 * Two-tier cache for Google results: an in-process LRU (hot keys, zero cost) in front of Firestore `mapsCache`
 * (shared by every instance). Entries carry `expiresAt`; a Firestore TTL policy on that field deletes them
 * (gcloud firestore fields ttls update expiresAt --collection-group=mapsCache). Google's terms allow caching
 * coordinates for up to 30 days; locations a user *saves* are stored permanently on their own record instead.
 */
import { createHash } from "node:crypto";
import { Timestamp } from "firebase-admin/firestore";
import { db } from "../config";

const MAX_LOCAL = 5_000;
const local = new Map<string, { exp: number; v: unknown }>();

export const cacheId = (kind: string, key: string) => `${kind}_${createHash("sha1").update(key).digest("hex").slice(0, 32)}`;

function localGet<T>(id: string): T | undefined {
  const e = local.get(id);
  if (!e) return undefined;
  if (e.exp < Date.now()) { local.delete(id); return undefined; }
  local.delete(id); local.set(id, e); // LRU bump
  return e.v as T;
}
function localSet(id: string, v: unknown, exp: number) {
  local.set(id, { exp, v });
  if (local.size > MAX_LOCAL) local.delete(local.keys().next().value as string);
}

export async function cacheGet<T>(kind: string, key: string): Promise<T | undefined> {
  const id = cacheId(kind, key);
  const hit = localGet<T>(id);
  if (hit !== undefined) return hit;
  const d = await db.doc(`mapsCache/${id}`).get().catch(() => null);
  if (!d?.exists) return undefined;
  const exp = (d.get("expiresAt") as Timestamp | undefined)?.toMillis() ?? 0;
  if (exp < Date.now()) return undefined;
  const v = d.get("v") as T;
  localSet(id, v, exp);
  return v;
}

export async function cacheSet(kind: string, key: string, v: unknown, days: number) {
  const id = cacheId(kind, key);
  const exp = Date.now() + Math.min(30, Math.max(1, days)) * 86_400_000;
  localSet(id, v, exp);
  await db.doc(`mapsCache/${id}`).set({ kind, v, expiresAt: Timestamp.fromMillis(exp), at: Timestamp.now() }).catch(() => undefined);
}

/** Collapses concurrent identical lookups into one Google call. */
const inflight = new Map<string, Promise<unknown>>();
export function once<T>(id: string, fn: () => Promise<T>): Promise<T> {
  const cur = inflight.get(id);
  if (cur) return cur as Promise<T>;
  const p = fn().finally(() => inflight.delete(id));
  inflight.set(id, p);
  return p;
}

/** Test hook. */
export function _clearLocalCache() { local.clear(); inflight.clear(); }
