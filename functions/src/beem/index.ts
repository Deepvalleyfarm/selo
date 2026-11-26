/** Shared Beem instances for this deployment (Firestore-backed). */
import { db } from "../config";
import { BeemOtpService } from "./beem-otp.service";
import { BeemSmsService } from "./beem-sms.service";
import { firestoreStore } from "./beem.store";

export const beemStore = firestoreStore(db);
export const otpService = new BeemOtpService(beemStore);
export const smsService = new BeemSmsService(beemStore);
export { BEEM_SECRETS } from "./beem.config";
export { SMS_TEMPLATES } from "./sms-templates";
