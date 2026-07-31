#!/usr/bin/env bash
# Generate a release keystore and (optionally) upload it to GitHub Actions secrets.
#
# Secrets created:
#   ANDROID_KEYSTORE_BASE64
#   ANDROID_KEYSTORE_PASSWORD
#   ANDROID_KEY_ALIAS
#   ANDROID_KEY_PASSWORD
#
# Usage:
#   ./scripts/create-android-release-keystore.sh              # generate only
#   ./scripts/create-android-release-keystore.sh --upload      # generate + gh secret set
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="${ROOT}/android/.signing"
KEYSTORE="${OUT_DIR}/ezsos-release.keystore"
ALIAS="${ANDROID_KEY_ALIAS:-ezsos}"
UPLOAD=0

for arg in "$@"; do
  case "$arg" in
    --upload) UPLOAD=1 ;;
    -h|--help)
      sed -n '1,20p' "$0"
      exit 0
      ;;
  esac
done

if ! command -v keytool >/dev/null 2>&1; then
  echo "keytool not found (install a JDK)" >&2
  exit 1
fi

mkdir -p "$OUT_DIR"
chmod 700 "$OUT_DIR"

if [ -f "$KEYSTORE" ]; then
  echo "Keystore already exists: $KEYSTORE" >&2
  echo "Delete it first if you want to rotate." >&2
  exit 1
fi

STORE_PASS="$(openssl rand -base64 24 | tr -d '/+=' | head -c 32)"
KEY_PASS="$STORE_PASS"

keytool -genkeypair \
  -keystore "$KEYSTORE" \
  -storetype PKCS12 \
  -alias "$ALIAS" \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -storepass "$STORE_PASS" \
  -keypass "$KEY_PASS" \
  -dname "CN=EZ SOS, OU=Mobile, O=EZ SOS, L=Unknown, ST=Unknown, C=US"

B64_FILE="${OUT_DIR}/ezsos-release.keystore.base64"
base64 -w0 "$KEYSTORE" > "$B64_FILE" 2>/dev/null || base64 "$KEYSTORE" | tr -d '\n' > "$B64_FILE"
chmod 600 "$KEYSTORE" "$B64_FILE"

ENV_FILE="${OUT_DIR}/github-secrets.env"
umask 077
cat > "$ENV_FILE" <<EOF
ANDROID_KEYSTORE_BASE64=$(cat "$B64_FILE")
ANDROID_KEYSTORE_PASSWORD=${STORE_PASS}
ANDROID_KEY_ALIAS=${ALIAS}
ANDROID_KEY_PASSWORD=${KEY_PASS}
EOF
chmod 600 "$ENV_FILE"

echo "Wrote:"
echo "  $KEYSTORE"
echo "  $B64_FILE"
echo "  $ENV_FILE"
echo
echo "Local release build:"
echo "  export ANDROID_KEYSTORE_PATH=\"$KEYSTORE\""
echo "  export ANDROID_KEYSTORE_PASSWORD='…'  # from $ENV_FILE"
echo "  export ANDROID_KEY_ALIAS='$ALIAS'"
echo "  export ANDROID_KEY_PASSWORD='…'"
echo "  (cd android && ./gradlew assembleRelease)"
echo

if [ "$UPLOAD" -eq 1 ]; then
  if ! command -v gh >/dev/null 2>&1; then
    echo "gh CLI not found; cannot upload secrets" >&2
    exit 1
  fi
  if ! gh auth status >/dev/null 2>&1; then
    echo "gh is not authenticated. Run: gh auth login" >&2
    exit 1
  fi
  gh secret set ANDROID_KEYSTORE_BASE64 < "$B64_FILE"
  gh secret set ANDROID_KEYSTORE_PASSWORD --body "$STORE_PASS"
  gh secret set ANDROID_KEY_ALIAS --body "$ALIAS"
  gh secret set ANDROID_KEY_PASSWORD --body "$KEY_PASS"
  echo "Uploaded four secrets to the GitHub repository."
else
  echo "Upload to GitHub (requires gh auth):"
  echo "  $0 --upload"
  echo "Or set manually from $ENV_FILE in GitHub → Settings → Secrets and variables → Actions"
fi
