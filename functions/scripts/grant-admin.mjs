// Bootstraps the first Super Admin (later admins are managed in the back office):
//   GOOGLE_APPLICATION_CREDENTIALS=sa.json node scripts/grant-admin.mjs admin@example.com super_admin
import { initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";

const [email, role = "super_admin"] = process.argv.slice(2);
if (!email) { console.error("Usage: node scripts/grant-admin.mjs <email> [role]"); process.exit(1); }
initializeApp();
const auth = getAuth();
const user = await auth.getUserByEmail(email).catch(() => auth.createUser({ email }));
await auth.setCustomUserClaims(user.uid, { admin: role });
await getFirestore("ai-studio-611c950c-febe-47c1-afff-35dcb0788339").doc(`admins/${user.uid}`).set({ email, role, updatedBy: "bootstrap" }, { merge: true });
console.log(`${email} is now ${role}. Send them a password reset link from the Firebase console and enforce MFA.`);
