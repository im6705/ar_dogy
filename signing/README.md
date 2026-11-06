# Public development signing identity

`development.keystore` is an intentionally public development key. Its store and
key password are both `android`; its alias is `androiddebugkey`. It is checked in
so development prereleases can update existing installs consistently. It provides
no proof of publisher identity: anybody can use this key. Verify APK downloads
against the repository release and its SHA-256 manifest.

These development APKs use the `release` build type with debugging disabled, but
they are **not production-signed releases**. Never use this key for Play Store
publishing or a trusted production distribution. A future production channel
needs a private signing key held outside the repository, an explicit signing
configuration, and an installation migration decision.

No personal signing credentials or GitHub tokens are included in the app.
