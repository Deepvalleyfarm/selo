import { PARCEL_DEFAULTS } from "./parcel-rules";

/** Defaults mirror PRD v6.0 section 8.4 (+ Parcels v2 §3/§9/§17); the live values are in platformConfig/default. */
export const DEFAULT_CONFIG = {
  ...PARCEL_DEFAULTS,
  flatPlatformFeeKwacha: 5, buyerServiceFeePct: 0, riderPlatformFeePct: 8, socialFundPct: 5,
  socialFundMilestoneDeliveries: 0, heroBonusPct: 10, deliveryFeePerKm: 5, deliveryBaseFee: 5, paymentProcessingPct: 0,
  parcelListingFee: 5, insurancePremiumPct: 2, insuranceMaxDeclaredValue: 20000, agentPlatformCutPct: 5,
  sellerRegistrationFee: 10, riderWithdrawalFee: 15, minWithdrawal: 20, broadcastTimeoutMin: 5,
  autoConfirmHours: 48, maxConcurrentDeliveries: 4, dispatchCountdownSec: 15, pinMaxAttempts: 5,
  pinLockMinutes: 30, sqMaxAttempts: 5, sqLockMinutes: 30, referralSharePct: 20, referralFlatBonusKwacha: 0,
  shareLinkExpiryDays: 3, minAppVersion: 1,
  // v6.2 paid promotions (kwacha) and how often a boost may appear
  boostLocalPerDay: 20, boostRegionalPerDay: 45, boostNationalPerDay: 90, storyPrice24h: 10, boostFrequencyCap: 3, boostSlotsPerPage: 2,
  unpaidOrderCancelMin: 30,
};
export type PlatformConfig = typeof DEFAULT_CONFIG;

/** Built-in 12-question bank (same IDs as the Android app and seed script). Firestore wording overrides it. */
export const SECURITY_QUESTION_BANK: Record<string, string> = {
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
