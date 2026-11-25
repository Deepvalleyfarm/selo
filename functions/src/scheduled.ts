import { Timestamp } from "firebase-admin/firestore";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { onDocumentWritten } from "firebase-functions/v2/firestore";
import { DB_ID, LIPILA_API_KEY, db, loadConfig } from "./config";
import { broadcastConfigChange } from "./admin";
import { agentCommission } from "./domain";
import { completeDelivery, disburse, refund, retryPayout } from "./escrow";
import { notify } from "./util";

/** No rider within broadcastTimeoutMin → full refund, no fees retained. */
export const scheduled_broadcastTimeout = onSchedule({ schedule: "every 1 minutes", secrets: [LIPILA_API_KEY] }, async () => {
  const cfg = await loadConfig();
  const cutoff = Timestamp.fromMillis(Date.now() - cfg.broadcastTimeoutMin * 60_000);
  const stale = await db.collection("orders").where("state", "==", "BROADCASTING").where("broadcastAt", "<", cutoff).limit(50).get();
  for (const o of stale.docs) await refund(o.id, "No rider was available");
});

/** Buyer never confirmed: warn at half time, auto-confirm after autoConfirmHours. */
export const scheduled_autoConfirm = onSchedule({ schedule: "every 15 minutes" }, async () => {
  const cfg = await loadConfig();
  const transit = await db.collection("orders").where("state", "==", "IN_TRANSIT").limit(200).get();
  for (const o of transit.docs) {
    const since = (o.get("timeline.IN_TRANSIT") as Timestamp | undefined)?.toMillis() ?? Date.now();
    const age = (Date.now() - since) / 3_600_000;
    if (age >= cfg.autoConfirmHours) await completeDelivery(o.id, "auto_confirm");
    else if (age >= cfg.autoConfirmHours / 2 && !o.get("autoConfirmWarned")) {
      await notify(o.get("buyerUid"), "Please confirm your delivery", `We'll confirm order ${o.id} automatically in ${Math.round(cfg.autoConfirmHours - age)} hours.`, "AUTO_CONFIRM_WARNING");
      await o.ref.update({ autoConfirmWarned: true });
    }
  }
});

export const scheduled_payoutRetry = onSchedule({ schedule: "every 5 minutes", secrets: [LIPILA_API_KEY] }, async () => {
  const due = await db.collection("payouts").where("status", "==", "FAILED_RETRY").where("nextAttemptAt", "<=", Timestamp.now()).limit(50).get();
  for (const p of due.docs) await retryPayout(p.id);
});

/** Agent commissions settle daily at midnight Lusaka time, net of the platform cut. */
export const scheduled_agentSettlement = onSchedule({ schedule: "0 0 * * *", timeZone: "Africa/Lusaka", secrets: [LIPILA_API_KEY] }, async () => {
  const cfg = await loadConfig();
  const since = Timestamp.fromMillis(Date.now() - 86_400_000);
  const orders = await db.collection("orders").where("state", "==", "DELIVERED").where("deliveredAt", ">=", since).get();
  const byAgent = new Map<string, number>();
  for (const o of orders.docs) {
    const agent = o.get("agentUid"); if (!agent || o.get("agentSettled")) continue;
    const link = await db.collection("agentLinks").where("agentUid", "==", agent).where("sellerUid", "==", o.get("sellerUid")).limit(1).get();
    const rate = link.docs[0]?.get("commissionPct") ?? 10;
    byAgent.set(agent, (byAgent.get(agent) ?? 0) + agentCommission(o.get("goodsNgwee"), rate, cfg).net);
    await o.ref.update({ agentSettled: true });
  }
  const date = new Date().toISOString().slice(0, 10);
  for (const [agent, net] of byAgent) {
    const u = (await db.doc(`users/${agent}`).get()).data();
    if (u?.payoutWallet && net > 0) await disburse(`SELO-AGC-${agent.slice(0, 8)}-${date}`, "agent_commission", agent, net, u.payoutWallet);
  }
});

/** Resets rider "today" counters and rolls the 7-day Social Fund chart at 00:05 Lusaka. */
export const scheduled_dailyReset = onSchedule({ schedule: "5 0 * * *", timeZone: "Africa/Lusaka" }, async () => {
  const wallets = await db.collection("riderWallets").get();
  for (const w of wallets.docs) {
    const week = [...((w.get("weekFund") as number[]) ?? [0, 0, 0, 0, 0, 0, 0]).slice(1), 0];
    await w.ref.update({ todayNetNgwee: 0, todayDeliveries: 0, weekFund: week });
  }
  const sellers = await db.collection("sellerStats").get();
  for (const s of sellers.docs) await s.ref.update({ todayNgwee: 0 });
});

/** Back office → app sync: wake background devices whenever platform config changes. */
export const onConfigUpdate = onDocumentWritten({ document: "platformConfig/default", database: DB_ID }, async (e) => {
  const v = e.data?.after?.get("version");
  if (typeof v === "number") await broadcastConfigChange(v);
});

/** Zone deactivated in the back office → notify riders who had it active. */
export const onZoneUpdate = onDocumentWritten({ document: "zones/{zoneId}", database: DB_ID }, async (e) => {
  const before = e.data?.before?.data(), after = e.data?.after?.data();
  if (!before?.isActive || after?.isActive !== false) return;
  const riders = await db.collection("riderZones").where("activeZoneIds", "array-contains", e.params.zoneId).get();
  for (const r of riders.docs) await notify(r.id, "Zone Update", `${after.name} has been deactivated. Review your active zones.`, "ZONE_DEACTIVATED", "selo://rider/zones");
});

/** Content edits made directly in the back office are audited server-side too. */
export const onContentWrite = onDocumentWritten({ document: "contentBlocks/{id}", database: DB_ID }, async (e) => {
  await db.collection("auditLogs").add({ actorUid: e.data?.after?.get("updatedBy") ?? null, action: "content.write", target: `contentBlocks/${e.params.id}`,
    before: e.data?.before?.data() ?? null, after: e.data?.after?.data() ?? null, at: Timestamp.now() });
});

/** v6.2: orders whose payment never completed are closed after unpaidOrderCancelMin (default 30 min). */
export const scheduled_cancelUnpaid = onSchedule({ schedule: "every 10 minutes" }, async () => {
  const cfg = await loadConfig();
  const cutoff = Timestamp.fromMillis(Date.now() - (cfg.unpaidOrderCancelMin ?? 30) * 60_000);
  const stale = await db.collection("orders").where("state", "==", "INITIATED").where("createdAt", "<", cutoff).limit(200).get();
  for (const o of stale.docs) {
    await o.ref.update({ state: "CANCELLED", "timeline.CANCELLED": Timestamp.now(), cancelReason: "Payment not completed", paymentStatus: o.get("paymentStatus") === "PAID" ? "PAID" : "FAILED" });
    await notify(o.get("buyerUid"), "Order closed", `Order ${o.id} was closed because the payment wasn't completed. Nothing was charged.`, "ORDER_CANCELLED");
  }
});

/** v6.2: Boosts and Feed Stories end on time; unpaid promotions are dropped after a day. */
export const scheduled_endPromotions = onSchedule({ schedule: "every 15 minutes" }, async () => {
  const now = Timestamp.now();
  const ended = await db.collection("promotions").where("status", "==", "ACTIVE").where("endsAt", "<=", now).limit(300).get();
  for (const p of ended.docs) {
    await p.ref.update({ status: "ENDED", endedAt: now });
    await db.doc(`listings/${p.get("listingId")}`).set({ [p.get("type") === "story" ? "storyPromoId" : "boostPromoId"]: null }, { merge: true });
    const s = p.get("stats") ?? {};
    await notify(p.get("sellerUid"), p.get("type") === "story" ? "Feed Story ended" : "Boost ended",
      `${p.get("listingTitle")}: reached ${s.impressions ?? 0} buyers, ${s.clicks ?? 0} opened it, ${s.enquiries ?? 0} enquiries.`, "PROMO_ENDED");
  }
  const stale = await db.collection("promotions").where("status", "in", ["PENDING_PAYMENT", "PAYMENT_FAILED"])
    .where("createdAt", "<", Timestamp.fromMillis(Date.now() - 86_400_000)).limit(300).get();
  for (const p of stale.docs) await p.ref.update({ status: "EXPIRED" });
});
