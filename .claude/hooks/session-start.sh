#!/bin/bash
# Installs the Android SDK and warms the Gradle cache so builds and tests work in
# Claude Code on the web sessions. Does nothing on Roger's PC.
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
	exit 0
fi

cd "${CLAUDE_PROJECT_DIR:-.}"

export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
	tmp="$(mktemp -d)"
	curl -sSLo "$tmp/clt.zip" https://dl.google.com/android/repository/commandlinetools-linux-9862592_latest.zip
	mkdir -p "$ANDROID_HOME/cmdline-tools"
	unzip -qo "$tmp/clt.zip" -d "$tmp"
	rm -rf "$ANDROID_HOME/cmdline-tools/latest"
	mv "$tmp/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
	rm -rf "$tmp"
fi
yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null 2>&1 || true

if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
	echo "export ANDROID_HOME=$ANDROID_HOME" >>"$CLAUDE_ENV_FILE"
fi

# Download dependencies up front; retry because the session proxy can rate-limit bursts.
for attempt in 1 2 3; do
	./gradlew --quiet --max-workers=2 help && break
	sleep $((attempt * 20))
done
