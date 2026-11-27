/**
 * Turns changes in the existing order and parcel engines into partner webhooks. No polling: Firestore triggers on the
 * same documents the apps use. Event ids are versioned by the new status, so a re-run trigger never duplicates.
 */
import { onDocumentUpdated } from "firebase-functions/v2/firestore";
import { DB_ID } from "../config";
import { deliveryEventsFor, OPEN_SECRETS } from "./model";
import { emit } from "./webhooks";

export const onPartnerParcelChange = onDocumentUpdated({ document: "parcels/{id}", database: DB_ID, secrets: OPEN_SECRETS }, async (e) => {
  const b = e.data?.before.data(), a = e.data?.after.data();
  if (!a?.partnerId || !b || a.status === b.status) return;
  const { deliveryView } = await import("./logistics");
  for (const type of deliveryEventsFor(b.status, a.status))
    await emit(a.partnerId, a.env ?? "production", type, e.params.id, { delivery: deliveryView(e.params.id, a) }, `${a.status}`);
});

/** Delivery progress of SeloNachipa marketplace orders for partner products is sent to the originating platform. */
export const onPartnerOrderChange = onDocumentUpdated({ document: "orders/{id}", database: DB_ID, secrets: OPEN_SECRETS }, async (e) => {
  const b = e.data?.before.data(), a = e.data?.after.data();
  if (!a?.partnerId || !b || a.state === b.state) return;
  const env = a.partnerEnv ?? "production";
  const { partnerOrderView } = await import("./commerce");
  const map: Record<string, string> = { RIDER_ASSIGNED: "RIDER_ASSIGNED", PICKED_UP: "PICKED_UP", IN_TRANSIT: "IN_TRANSIT", DELIVERED: "DELIVERED", COMPLETED: "COMPLETED" };
  if (map[a.state]) for (const type of deliveryEventsFor(map[b.state] ?? b.state, map[a.state]))
    await emit(a.partnerId, env, type, e.params.id, { order: await partnerOrderView(e.params.id) }, a.state);
  // refunded after acceptance (e.g. no rider found) → the originating platform must reverse its sale
  if (a.state === "REFUNDED" && a.partnerAcceptance === "ACCEPTED") await emit(a.partnerId, env, "order.cancelled", e.params.id, { order: await partnerOrderView(e.params.id), reason: a.refundReason ?? null });
});
