#!/usr/bin/env bash
set -euo pipefail

readonly apk_dir="${1:-$RUNNER_TEMP/startup-apk}"
readonly artifact_zip="$RUNNER_TEMP/minified-apk.zip"

download_apk() {
  local artifact_id=""
  local attempt
  mkdir -p "$apk_dir"
  for attempt in {1..90}; do
    artifact_id="$(
      gh api "repos/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID/artifacts" \
        --jq '.artifacts[] | select(.name == "minified-apk") | .id' \
        | head -1
    )"
    if [[ -n "$artifact_id" ]]; then
      gh api "repos/$GITHUB_REPOSITORY/actions/artifacts/$artifact_id/zip" > "$artifact_zip"
      unzip -q "$artifact_zip" -d "$apk_dir"
      return
    fi
    sleep 5
  done
  echo "Timed out waiting for minified-apk from run $GITHUB_RUN_ID" >&2
  exit 1
}

download_apk

readonly apk="$(find "$apk_dir" -type f -name '*.apk' -print -quit)"
readonly keystore="$RUNNER_TEMP/startup-check.jks"

if [[ -z "$apk" ]]; then
  echo "No release APK was downloaded" >&2
  exit 1
fi

dump_errors() {
  adb logcat -d -v threadtime '*:E' | tail -300
}

keytool -genkeypair -noprompt \
  -keystore "$keystore" \
  -storepass startup-check -keypass startup-check \
  -alias startup-check -keyalg RSA -keysize 2048 -validity 1 \
  -dname "CN=Autobile startup check"
"$ANDROID_HOME/build-tools/35.0.0/apksigner" sign \
  --ks "$keystore" \
  --ks-pass pass:startup-check \
  --key-pass pass:startup-check \
  "$apk"

adb install "$apk"
adb logcat -c
launch="$(adb shell am start -W -n com.autobile/com.autobile.app.MainActivity || true)"
printf '%s\n' "$launch"
if ! grep -Fq 'Status: ok' <<< "$launch"; then
  dump_errors
  exit 1
fi

sleep 3
if [[ -z "$(adb shell pidof com.autobile)" ]]; then
  dump_errors
  exit 1
fi

resumed="$(adb shell dumpsys activity activities | grep -F 'mResumedActivity' || true)"
if ! grep -Fq 'com.autobile' <<< "$resumed"; then
  dump_errors
  exit 1
fi
