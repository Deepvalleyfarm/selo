/**
 * Every Beem response code the module can see, with the user-facing message, the admin explanation and the
 * recommended action (used by the monitor's Active Errors panel).
 */
export type BeemCategory = "ok" | "user" | "config" | "service" | "session";
export interface BeemCode { meaning: string; user: string; admin: string; action: string; category: BeemCategory }

export const BEEM_OTP_CODES: Record<number, BeemCode> = {
  100: { meaning: "SMS sent successfully", user: "", admin: "OTP SMS accepted by Beem.", action: "None.", category: "ok" },
  101: { meaning: "Failed to send SMS", user: "We couldn't send an SMS right now. Please try again.",
    admin: "Beem could not deliver the OTP SMS. Possible cause: Beem service degradation or insufficient SMS credits.",
    action: "Check your Beem account balance and the Beem status page.", category: "service" },
  102: { meaning: "Invalid phone number", user: "That number is not valid. Please check and try again.",
    admin: "The number passed local validation but Beem rejected it.", action: "Usually the user's typo. If frequent, check the number cleaning rules.", category: "user" },
  103: { meaning: "Phone number missing", user: "Something went wrong. Please try again.",
    admin: "Request reached Beem without an msisdn (should be impossible after validation).", action: "Report to engineering: validation bypass.", category: "config" },
  104: { meaning: "Application ID missing", user: "Verification is temporarily unavailable. Please try again later.",
    admin: "BEEM_APP_ID is empty in the function configuration.", action: "Set BEEM_APP_ID in functions/.env.<project> and redeploy.", category: "config" },
  106: { meaning: "Application not found", user: "Verification is temporarily unavailable. Please try again later.",
    admin: "Beem does not recognise the configured application ID.", action: "Confirm the App ID under Beem portal → OTP → Applications.", category: "config" },
  107: { meaning: "Application inactive", user: "Verification is temporarily unavailable. Please try again later.",
    admin: "The application is inactive on Beem.", action: "Log into the Beem portal and reactivate the application.", category: "config" },
  108: { meaning: "No channel found", user: "Verification is temporarily unavailable. Please try again later.",
    admin: "The Beem application has no SMS channel attached.", action: "Attach an SMS channel (sender ID) to the application in the Beem portal.", category: "config" },
  113: { meaning: "pinId not found", user: "Verification session expired. Please restart.",
    admin: "Beem has no record of that pinId (expired or wrong).", action: "None unless frequent.", category: "session" },
  114: { meaning: "Incorrect Pin", user: "That code is incorrect. Please try again.", admin: "User typed a wrong code.", action: "None.", category: "user" },
  115: { meaning: "Pin TimeOut", user: "That code has expired. Please request a new one.",
    admin: "The PIN expired before verification.", action: "If frequent, SMS delivery may be slow — check Beem delivery reports.", category: "user" },
  116: { meaning: "Attempts Exceeded", user: "Too many incorrect attempts. Please request a new code.", admin: "Too many wrong codes for one PIN.", action: "None. Watch for brute-force patterns.", category: "user" },
  117: { meaning: "Valid Pin", user: "", admin: "Number verified.", action: "None.", category: "ok" },
  118: { meaning: "Duplicate Pin", user: "This code has already been used. Please request a new one.", admin: "A PIN was submitted again after success.", action: "None.", category: "user" },
};

export const HTTP_ERRORS: Record<string, BeemCode> = {
  AUTH: { meaning: "Invalid credentials", user: "Verification is temporarily unavailable. Please try again later.",
    admin: "Beem rejected the API key / secret key (HTTP 401/403).", action: "Re-enter BEEM_API_KEY and BEEM_SECRET_KEY in Secret Manager; rotate if leaked.", category: "config" },
  NETWORK: { meaning: "Cannot reach Beem", user: "We couldn't send an SMS right now. Please try again.",
    admin: "Network error or timeout calling Beem (after one retry).", action: "Check Beem status; if persistent, check outbound networking.", category: "service" },
  SERVER: { meaning: "Beem server error", user: "We couldn't send an SMS right now. Please try again.",
    admin: "Beem returned a 5xx error.", action: "Usually transient. Check the Beem status page if it continues.", category: "service" },
  CONFIG: { meaning: "Module not configured", user: "Verification is temporarily unavailable. Please try again later.",
    admin: "Beem credentials or App ID / sender ID are missing in this deployment.", action: "Set the BEEM_* secrets and parameters and redeploy.", category: "config" },
  SMS_REJECTED: { meaning: "SMS rejected", user: "", admin: "Beem rejected the SMS (invalid sender ID, no credit or invalid recipient).",
    action: "Check the sender ID is approved and the account has credit.", category: "service" },
};

export const describeCode = (code: number | null | undefined): BeemCode | null => (code != null ? BEEM_OTP_CODES[code] ?? null : null);
