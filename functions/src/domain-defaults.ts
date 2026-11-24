/** Defaults mirror PRD v6.0 section 8.4; the live values are in platformConfig/default. */
export const DEFAULT_CONFIG = {
  flatPlatformFeeKwacha: 5, buyerServiceFeePct: 0, riderPlatformFeePct: 0, socialFundPct: 2,
  socialFundMilestoneDeliveries: 20, deliveryFeePerKm: 5, deliveryBaseFee: 5, paymentProcessingPct: 0,
  parcelListingFee: 5, insurancePremiumPct: 2, insuranceMaxDeclaredValue: 20000, agentPlatformCutPct: 5,
  sellerRegistrationFee: 10, riderWithdrawalFee: 15, minWithdrawal: 50, broadcastTimeoutMin: 5,
  autoConfirmHours: 48, maxConcurrentDeliveries: 4, dispatchCountdownSec: 15, pinMaxAttempts: 5,
  pinLockMinutes: 30, sqMaxAttempts: 5, sqLockMinutes: 30, referralSharePct: 20, referralFlatBonusKwacha: 0,
  shareLinkExpiryDays: 3, minAppVersion: 1,
};
export type PlatformConfig = typeof DEFAULT_CONFIG;
