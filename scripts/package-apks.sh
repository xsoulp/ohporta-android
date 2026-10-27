#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_dir"
version="$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' app/build.gradle.kts)"
mkdir -p artifacts
cp app/build/outputs/apk/debug/app-debug.apk "artifacts/APT-Access-${version}-fingerprint.apk"
cp app/build/outputs/apk/pin/app-pin.apk "artifacts/APT-Access-${version}-pin.apk"
(
    cd artifacts
    sha256sum "APT-Access-${version}-fingerprint.apk" "APT-Access-${version}-pin.apk" > SHA256SUMS.txt
)
