#!/usr/bin/env bash
# Build the actual app from a clean checkout and retain verifiable release evidence.
set -euo pipefail
cd "$(dirname "$0")/.."

commit="${BUILD_COMMIT:-$(git rev-parse HEAD)}"
tag="${BUILD_TAG:-}"
version_code="${BUILD_VERSION_CODE:-1}"
if [[ -n "$tag" ]]; then
  [[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+([.-][A-Za-z0-9.-]+)?$ ]] || { echo 'Expected a version tag such as v0.1.0'; exit 1; }
  version_name="${tag#v}"
else
  version_name="0.1.0-dev.${commit:0:8}"
fi
[[ "$version_code" =~ ^[1-9][0-9]*$ ]] || { echo 'Version code must be a positive integer'; exit 1; }

bash ./gradlew --no-daemon --console=plain :app:assembleRelease :app:lintRelease \
  "-PappVersionName=$version_name" "-PappVersionCode=$version_code"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[[ -n "$sdk" ]] || { echo 'Set ANDROID_HOME to the Android SDK'; exit 1; }
build_tools="$sdk/build-tools/36.0.0"
mkdir -p dist
apk="ar-dogy-${version_name}-dev.apk"
cp app/build/outputs/apk/release/app-release.apk "dist/$apk"
"$build_tools/apksigner" verify --verbose --print-certs "dist/$apk" > dist/apk-inspection.txt
"$build_tools/aapt" dump badging "dist/$apk" >> dist/apk-inspection.txt

export INFO_COMMIT="$commit" INFO_TAG="$tag" INFO_NAME="$version_name" INFO_CODE="$version_code" INFO_APK="$apk"
python3 - <<'PY'
import hashlib, json, os, pathlib, re, zipfile
out = pathlib.Path('dist')
apk = out / os.environ['INFO_APK']
inspection = (out / 'apk-inspection.txt').read_text()
assert "package: name='com.im6705.ardogy'" in inspection
assert "launchable-activity: name='com.im6705.ardogy.MainActivity'" in inspection
assert f"versionCode='{os.environ['INFO_CODE']}'" in inspection
assert f"versionName='{os.environ['INFO_NAME']}'" in inspection
with zipfile.ZipFile(apk) as archive:
    assert archive.getinfo('assets/models/dog.glb').file_size > 1000
info = {
    'applicationId': 'com.im6705.ardogy',
    'commit': os.environ['INFO_COMMIT'], 'tag': os.environ['INFO_TAG'],
    'versionName': os.environ['INFO_NAME'], 'versionCode': int(os.environ['INFO_CODE']),
    'apk': apk.name, 'apkSha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
    'signing': 'PUBLIC DEVELOPMENT KEY — not production signing',
    'signerCertificateSha256': re.search(r'Signer #1 certificate SHA-256 digest: (\S+)', inspection).group(1),
    'compileSdk': 36, 'minSdk': 24, 'targetSdk': 36,
    'hardwareValidation': 'Not established by this build; perform documented real-device acceptance.'
}
(out / 'build-info.json').write_text(json.dumps(info, indent=2) + '\n')
names = [apk.name, 'build-info.json', 'apk-inspection.txt']
(out / 'SHA256SUMS.txt').write_text(''.join(f'{hashlib.sha256((out / n).read_bytes()).hexdigest()}  {n}\n' for n in names))
print(json.dumps(info, indent=2))
PY
