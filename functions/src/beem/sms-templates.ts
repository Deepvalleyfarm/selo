/**
 * Server-side SMS templates. Apps never send a message body: they name an event and pass the variables.
 * Selonachipa and Mabala templates live side by side; each application only uses its own.
 */
const clip = (s: unknown, n = 40) => String(s ?? "").replace(/\s+/g, " ").trim().slice(0, n);

export const SMS_TEMPLATES = {
  PARCEL_COLLECTED: (riderName: string, trackingId: string, trackUrl?: string) =>
    `Hi! Your parcel (${clip(trackingId, 20)}) has been collected by ${clip(riderName)} and is on its way. Track on Selonachipa${trackUrl ? `: ${trackUrl}` : "."}`,
  PARCEL_DELIVERED: (trackingId: string) =>
    `Your parcel (${clip(trackingId, 20)}) has been delivered. Thank you for using Selonachipa!`,
  ORDER_CONFIRMED: (orderId: string, sellerName: string) =>
    `Your order #${clip(orderId, 20)} from ${clip(sellerName)} has been confirmed on Selonachipa.`,
  RIDER_ASSIGNED: (riderName: string, phone: string) =>
    `A rider has been assigned to your order. ${clip(riderName)} will contact you on ${clip(phone, 16)}.`,
  ACCOUNT_CREATED: (name: string) =>
    `Welcome to Selonachipa, ${clip(name, 30)}! Your account is active. Start shopping or selling today.`,
  MABALA_WELCOME: (farmName: string) =>
    `Welcome to Mabala, ${clip(farmName)}! Your farm management account is ready at mabala.cloud.`,
  // Delivery confirmation (Parcels v2): contains a secret, so its body is redacted in sms_logs.
  DELIVERY_CODE: (code: string, what: string, minutes: number) =>
    `Your Selonachipa delivery code for ${clip(what, 30)} is ${code}. Only give it to the rider once you have your item. It expires in ${minutes} minutes.`,
  DELIVERY_NOTICE: (text: string) => clip(text, 300),
} as const;
export type SmsTemplate = keyof typeof SMS_TEMPLATES;

/** Events whose bodies contain secrets; sms_logs stores them with digits masked. */
export const SENSITIVE_EVENTS = new Set<string>(["DELIVERY_CODE", "OTP_REQUESTED"]);
export const redactBody = (text: string) => text.replace(/\d{4,}/g, (m) => "•".repeat(m.length));
