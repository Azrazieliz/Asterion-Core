# AsterionCore — permanent Android signing and in-place updates

## Why the old APKs cannot replace one another

The v2.0.3, v2.0.4 and v2.0.5 GitHub Actions APKs were signed by **different,
ephemeral Android debug keys**, even though the application ID stayed
`com.ailm.android`. Android requires an identical signer (or a valid signing
certificate rotation lineage) AND an increasing `versionCode` for an in-place
APK update. Changing the version number does not change that restriction.

The v2.0.3/v2.0.4/v2.0.5 signing SHA-256 fingerprints differ:

- v2.0.3: `d649d5a3b1ab0749f07a48c1934a23665e1128329da3c7c23da111c4284024cc`
- v2.0.4: `bbe02f33e122b1ace01c3146904f8471934fdd10d86eed94d64e8cdf1286563d`
- v2.0.5: `00338dac634666bcb3c40a24bac2cf552e68395752e2e6a8901fd2467961c0c0`

A new permanent signing key cannot update an already installed version signed
by those earlier ephemeral keys. Do not uninstall an existing installation
unless you have a verified recovery plan for ALL private data, including
downloaded models, SQLite databases and preferences. Android cloud backup
is explicitly disabled in the current manifest via `fullBackupContent=false`,
and Fusion JSON export is not a complete application backup.

## One-time key setup — on your phone, no PC required

Create and retain **one** signing keystore that is yours. For example, in
Termux on Android (install from an authentic Termux distribution):

```bash
pkg update
pkg install openjdk-17 coreutils
keytool -genkeypair -v -storetype PKCS12 \
  -keystore "$HOME/asterion-release.jks" \
  -alias asterion \
  -keyalg RSA -keysize 3072 -validity 10000
base64 -w 0 "$HOME/asterion-release.jks" > "$HOME/asterion-release-key.b64"
```

Use a strong, memorable **store password** and private key password.
Save the `.jks` somewhere safe and separately back it up securely (encrypted)
in two places. You must retain the private key indefinitely: losing it
prevents future in-place updates. **Never commit or share the .jks, its
base64 text, or its passwords publicly.**

In your browser on the phone:
**GitHub > Azrazieliz/Asterion-Core > Settings >
Secrets and variables > Actions > Repository secrets**.

Create these four encrypted secrets:

| Secret | Value |
|---|---|
| `ASTERION_SIGNING_KEYSTORE_BASE64` | Contents of `asterion-release-key.b64` |
| `ASTERION_SIGNING_STORE_PASSWORD` | Keystore password |
| `ASTERION_SIGNING_KEY_ALIAS` | `asterion` (or your chosen alias) |
| `ASTERION_SIGNING_KEY_PASSWORD` | Private key password (often same as store password for PKCS12) |

Do **not** paste the passwords or base64 file in issues, workflow inputs,
commits, screenshots, or ChatGPT messages.

## Build and download consistently signed updates

Once configured, GitHub Actions' **Asterion Core Android Build** produces
two separate artifacts on `main`:

- `AsterionCore-debug-apk`: **for CI testing only**. Never use it to update
  a stable installation; its debug signing key changes between runners.
- `AsterionCore-stable-signed-apk`: the version to install on Android
  for future updates. It uses the same permanent signing key every time.

The signed-release job does not publish anything if the four secrets are
missing. It verifies APK signatures before upload. After the first successful
signed build, copy the printed signing certificate SHA-256 into a non-secret
repository Actions variable `ASTERION_RELEASE_CERT_SHA256`. Future builds
then **fail** if someone accidentally replaces the signing key.

Each future update must preserve `applicationId = "com.ailm.android"`,
increase `versionCode`, and use the **same stable signing key**. Open
the downloaded `app-release.apk` on your phone and Android will offer
**Update** (not reinstall).

If Android rejects the first permanent-key APK due to signature mismatch,
preserve the old installation until you've verified what can be exported
and restored. The migration from existing debug-signature installs is a
distinct data-preservation task.
