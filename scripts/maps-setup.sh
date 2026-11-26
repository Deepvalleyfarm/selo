#!/usr/bin/env bash
# Google Maps Platform setup for SeloNaChipa — enables ONLY the four approved APIs, turns the rest off,
# and creates two restricted keys. Run once per project (requires gcloud auth as a project owner).
#   ./scripts/maps-setup.sh mabala-f2d65 <ANDROID_SHA1_FINGERPRINT>
set -euo pipefail
PROJECT="${1:?project id}"; SHA1="${2:?release signing SHA-1 (keytool -list -v -keystore …)}"; PKG="selo.Nachipa01"
gcloud config set project "$PROJECT"

echo "1) Enable the approved APIs"
gcloud services enable places.googleapis.com geocoding-backend.googleapis.com routes.googleapis.com maps-android-backend.googleapis.com apikeys.googleapis.com

echo "2) Disable everything else from Maps Platform (ignore 'not enabled' messages)"
for s in places-backend.googleapis.com directions-backend.googleapis.com distance-matrix-backend.googleapis.com addressvalidation.googleapis.com \
         roads.googleapis.com airquality.googleapis.com solar.googleapis.com elevation-backend.googleapis.com timezone-backend.googleapis.com \
         pollen.googleapis.com weather.googleapis.com routeoptimization.googleapis.com street-view-image-backend.googleapis.com \
         geolocation.googleapis.com maps-embed-backend.googleapis.com maps-backend.googleapis.com static-maps-backend.googleapis.com \
         navigationsdk.googleapis.com areainsights.googleapis.com; do
  gcloud services disable "$s" --force 2>/dev/null || true
done

echo "3) Server key: Places API (New) + Geocoding + Routes only (used by Cloud Functions)"
gcloud services api-keys create --display-name="selo-maps-server" \
  --api-target=service=places.googleapis.com --api-target=service=geocoding-backend.googleapis.com --api-target=service=routes.googleapis.com
echo "   → copy the keyString and run: firebase functions:secrets:set MAPS_API_KEY"

echo "4) Android key: Maps SDK for Android only, locked to the app's package + signing certificate"
gcloud services api-keys create --display-name="selo-maps-android" \
  --api-target=service=maps-android-backend.googleapis.com --allowed-application="sha1_fingerprint=${SHA1},package_name=${PKG}"
echo "   → put the keyString in android/local.properties as MAPS_ANDROID_API_KEY=… (or the CI secret) and rebuild"

cat <<'NOTE'
5) In Cloud Console → APIs & Services → each API → Quotas, set per-day request quotas a little above the
   platform caps (mapsDailyCap* in the admin dashboard) as a hard backstop, and create a Billing budget with
   alerts at 50/90/100 %. Firestore TTL: gcloud firestore fields ttls update expiresAt --collection-group=mapsCache --database=<db> --enable-ttl
NOTE
