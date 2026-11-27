/**
 * Turns changes in the existing parcel engine into partner delivery webhooks. No polling: Firestore triggers on the
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
