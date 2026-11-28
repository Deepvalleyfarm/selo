// Bootstraps the first Super Admin (later admins are managed in the back office):
//   GOOGLE_APPLICATION_CREDENTIALS=sa.json node scripts/grant-admin.mjs admin@example.com super_admin
import { initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";

const args = process.argv.slice(2);
const ceo = args.includes("--ceo");
const [email, role = "super_admin"] = args.filter((a) => a !== "--ceo");
if (!email) { console.error("Usage: node scripts/grant-admin.mjs <email> [role]"); process.exit(1); }
initializeApp();
const auth = getAuth();
const user = await auth.getUserByEmail(email).catch(() => auth.createUser({ email }));
// --ceo grants the CEO-level override needed to switch off the anti-addiction guardrails.
// Firebase Auth is shared with Mabala: keep the account's existing claims and only add SeloNaChipa's admin role.
await auth.setCustomUserClaims(user.uid, { ...(user.customClaims ?? {}), admin: role, ...(ceo ? { ceo: true } : {}) });
await getFirestore("ai-studio-611c950c-febe-47c1-afff-35dcb0788339").doc(`admins/${user.uid}`).set({ email, role, updatedBy: "bootstrap" }, { merge: true });
console.log(`${email} is now ${role}${ceo ? " with CEO override" : ""}. Send them a password reset link from the Firebase console and enforce MFA.`);
