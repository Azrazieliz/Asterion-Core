# AsterionCore: one permanent Android signing identity

**User has an existing signing key. Reuse it. Never create a replacement
release key or rotate it automatically. Never commit a keystore or passwords.**

The installed app's data (including imported AI models) survives an Android
APK update only when Android accepts it as an update: the APK must have the
same application ID and signing identity, and its versionCode must be higher
than the installed version.

## One-time configuration using your existing key

In the repository's **Settings > Secrets and variables > Actions**, add
these four encrypted *repository secrets* from the existing keystore:

- `ASTERION_SIGNING_KEYSTORE_BASE64`: base64 representation of the exact
  existing keystore (do not paste this into ChatGPT or commit it).
- `ASTERION_SIGNING_STORE_PASSWORD`: existing keystore password.
- `ASTERION_SIGNING_KEY_ALIAS`: existing alias.
- `ASTERION_SIGNING_KEY_PASSWORD`: key password.

Under **Variables**, set `ASTERION_RELEASE_CERT_SHA256` to the lowercase
SHA-256 fingerprint of the **public certificate** from this exact signing key.
The fingerprint is *not* a password or the private key. This workflow refuses
to publish an update APK if the fingerprint is unset or doesn't match.

Run the workflow on `main` after setup. Install the artifact named
`AsterionCore-stable-signed-apk`, **not** the debug artifact.
The same keystore and pin must be used for every future signed version.
Never send the key or passwords into a conversation.

## Migrating once from the existing debug installation

A debug-signed APK and an existing release-signed APK generally use different
certificates. In that case Android prevents an in-place update.

The user has allowed **one final model reimport**, but do not uninstall until
any needed records and settings are backed up. Installing the first signed
release may require removing the differently signed installation; that removes
private files, including locally imported models. Reimport the models once.
Afterward, keep the same release signing key and increase `versionCode` with
every build so future APKs can update without wiping private data.

An installed debug APK can only be updated directly by an APK using the *same
debug certificate*. GitHub-hosted debug jobs generate ephemeral debug keys and
are not appropriate for dependable update delivery.

**Important:** A green GitHub build is not proof of update compatibility.
Only the signed-release job checks that the user-provided certificate matches
the pinned fingerprint. An APK cannot be called `update-compatible` without it.
