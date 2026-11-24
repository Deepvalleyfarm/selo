import { initializeApp, type FirebaseApp } from "firebase/app";
import { getAuth, type Auth } from "firebase/auth";
import { getFirestore, type Firestore } from "firebase/firestore";
import { getFunctions, type Functions } from "firebase/functions";
import { getDatabase, type Database } from "firebase/database";
import { getStorage, type FirebaseStorage } from "firebase/storage";
import { initializeAppCheck, ReCaptchaEnterpriseProvider } from "firebase/app-check";

/** Same named database the Android app uses — this is what keeps both sides in sync. */
export const DB_ID = "ai-studio-611c950c-febe-47c1-afff-35dcb0788339";
export const REGION = "us-central1";

const env = import.meta.env;
export const isDemo = !env.VITE_FIREBASE_API_KEY;

let app: FirebaseApp | null = null;
export let auth: Auth | null = null;
export let db: Firestore | null = null;
export let fns: Functions | null = null;
export let rtdb: Database | null = null;
export let storage: FirebaseStorage | null = null;

if (!isDemo) {
  app = initializeApp({
    apiKey: env.VITE_FIREBASE_API_KEY, authDomain: env.VITE_FIREBASE_AUTH_DOMAIN, projectId: env.VITE_FIREBASE_PROJECT_ID,
    storageBucket: env.VITE_FIREBASE_STORAGE_BUCKET, messagingSenderId: env.VITE_FIREBASE_MESSAGING_SENDER_ID,
    appId: env.VITE_FIREBASE_APP_ID, databaseURL: env.VITE_FIREBASE_DATABASE_URL,
  });
  if (env.VITE_RECAPTCHA_ENTERPRISE_KEY) initializeAppCheck(app, { provider: new ReCaptchaEnterpriseProvider(env.VITE_RECAPTCHA_ENTERPRISE_KEY), isTokenAutoRefreshEnabled: true });
  auth = getAuth(app);
  db = getFirestore(app, DB_ID);
  fns = getFunctions(app, REGION);
  rtdb = env.VITE_FIREBASE_DATABASE_URL ? getDatabase(app) : null;
  storage = getStorage(app);
}
