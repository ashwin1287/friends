# Signing the Bondwidth APK

Bondwidth is built and released entirely from GitHub Actions. To install each
new build as a normal update of the previous one, every APK must be signed
with the **same** key. This document is the one-time setup and the recovery
procedure.

## Why this matters

Android refuses to install an APK whose signing certificate differs from the
one currently installed. Without a persistent keystore reused across CI runs,
every new build would be signed by an ephemeral debug key generated on that
runner, and the OS would block the install with "App not installed".

The build is also wired so that `versionCode` auto-increments from
`github.run_number`, so each CI build looks like a distinct version to the OS.

## A) Generate the keystore on GitHub Actions

This is a **public** repository, and Actions artifacts on a public repo can be
downloaded by third parties. So the workflow never uploads the raw key: it
encrypts the whole bundle with a passphrase only you hold and uploads just the
ciphertext.

1. Pick a strong, random passphrase (e.g. 30+ characters from a password
   manager) and save it — you need it to decrypt the bundle.
2. Add it as a repository secret named **`KEYSTORE_EXPORT_PASSPHRASE`** under
   **Settings → Secrets and variables → Actions**.
3. Go to **Actions → "Generate signing keystore" → Run workflow → Run**. The
   run fails fast if that secret is not set.
4. When it finishes, download the artifact **`signing-keystore-encrypted`**
   (it contains one file, `keystore-bundle.enc`).
5. Decrypt it locally — the exact command is also printed in the run's job
   summary:
   ```sh
   read -rs EXPORT_PASSPHRASE && export EXPORT_PASSPHRASE   # type your passphrase
   openssl enc -d -aes-256-cbc -pbkdf2 -iter 600000 \
     -in keystore-bundle.enc -out bundle.tar -pass env:EXPORT_PASSPHRASE
   tar xf bundle.tar
   ```
   This yields `signing-keystore.b64` (the value for `SIGNING_KEYSTORE_BASE64`)
   and `credentials.txt` (the store/key passwords and the alias).

## B) Store four repository secrets

Under **Settings → Secrets and variables → Actions**, create these four
repository secrets. Names must match exactly:

- `SIGNING_KEYSTORE_BASE64` — paste the entire base64 string from
  `signing-keystore.b64`.
- `SIGNING_STORE_PASSWORD` — paste from `credentials.txt`.
- `SIGNING_KEY_PASSWORD` — paste from `credentials.txt` (same value as the
  store password, by convention).
- `SIGNING_KEY_ALIAS` — paste from `credentials.txt` (`bondwidth`).

The encrypted artifact auto-deletes after 7 days and is harmless even before
then. Once the secrets are saved you can re-run the workflow at any time to
produce a fresh encrypted bundle.

## C) Back up the keystore — CRITICAL

Save the base64 blob and the three passwords in your password manager. If the
keystore is ever lost you cannot publish an update that installs over the
existing app. You would have to uninstall, reinstall, and restore from backup
every time you wanted a new build.

## D) One-time migration on your phone

Existing builds were signed with ephemeral debug keys, so the first
properly-signed build will not install over them. Do this once:

1. Open the currently-installed Bondwidth app and export a backup
   (**Settings → Backup & restore → Export…**) to a safe location
   (USB drive, encrypted email to yourself, etc.).
2. Uninstall Bondwidth.
3. Wait for the next CI build to publish a Release (tagged
   `v1.0.0-build.N.1` for some new `N`).
4. Install the new signed APK.
5. Open Bondwidth and import the backup file.

From this point on, every CI build installs as a normal update with no data
loss.

## E) If the keystore is ever lost

- The existing install keeps working forever; you just cannot update it.
- To resume updates: re-run step **A** to generate a fresh keystore, replace
  the four secrets, and repeat step **D** once.

## F) Rotating the key (after a suspected exposure)

If the signing key may have leaked, rotate it:

1. Regenerate by repeating steps **A**–**B**: a fresh keystore and new secrets,
   which means a new signing certificate.
2. Do the one-time on-device migration in step **D** once (export a backup,
   uninstall, install the next signed build, import the backup). Android treats
   the new certificate as a different signer, so an in-place update across the
   rotation is not possible — this is expected.

Older releases signed with the previous key keep working until uninstalled;
they just can't be updated by builds signed with the new key.

## G) Google Play: app signing key and upload key

Google Play uses two keys. Keep them apart:

| | App signing key | Upload key |
| --- | --- | --- |
| What it is | **our existing release key** (the `SIGNING_*` keystore above) | a separate key used only to send bundles to Play |
| Who signs with it | Play, when it serves the app to devices; our CI, for the F-Droid/GitHub APK | our CI (`release.yml`), when it signs `Bondwidth-X.Y.Z.aab` |
| Repository secrets | `SIGNING_*` (unchanged) | `UPLOAD_*` (below) |
| If it is lost | no more updates on any channel | Play can reset it (Play Console → App integrity → request upload key reset) |

### The rule: our release key is Play's app signing key

Play installs, F-Droid installs and GitHub sideloads only update over each
other if they carry the same signature. So in **Play App Signing** we upload
our existing release key **once**, with PEPK. We never let Google generate an
app signing key: a Google-generated key would give Play installs a different
signature, and users could not switch between Play and F-Droid without
uninstalling. The choice is made when the app is first set up in Play
Console and cannot simply be undone later, so get it right the first time.

One-time upload, on a trusted machine (never in CI):

1. In Play Console, open the app → **Test and release → App integrity → App
   signing**, choose **Use a different key** (or **Change app signing key**)
   → **Export and upload a key from Java keystore**. Download `pepk.jar` and
   the encryption public key (`encryption_public_key.pem`) it offers.
2. Decode the release keystore from your password-manager backup (step C),
   e.g. `base64 -d < signing-keystore.b64 > release.jks`.
3. Run the exact command Play Console shows. It looks like this:
   ```sh
   java -jar pepk.jar --keystore=release.jks --alias=bondwidth \
     --output=encrypted-private-key.zip --include-cert --rsa-aes-encryption \
     --encryption-key-path=encryption_public_key.pem
   ```
4. Upload `encrypted-private-key.zip` in the same dialog, together with the
   upload certificate from the next section.
5. Delete `release.jks` and `encrypted-private-key.zip` from that machine.
6. Check that **App signing key certificate → SHA-256** in Play Console equals
   the SHA-256 that `release.yml` prints for `AllowedAPKSigningKeys`.

### Create the upload key

Generate it locally with `keytool` (JDK 17+). Use a password manager for the
password; by convention the store and key passwords are the same:

```sh
keytool -genkeypair -v \
  -keystore upload-keystore.jks -storetype PKCS12 \
  -alias upload -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Bondwidth upload key"
```

Export its certificate for Play Console (step 4 above, or **App integrity →
Upload key certificate** if app signing is already set up):

```sh
keytool -export -rfc -keystore upload-keystore.jks -alias upload \
  -file upload_certificate.pem
```

### Store four more repository secrets

Under **Settings → Secrets and variables → Actions**:

- `UPLOAD_KEYSTORE_BASE64` — `base64 -i upload-keystore.jks` (macOS) or
  `base64 -w0 upload-keystore.jks` (Linux), the whole output on one line.
- `UPLOAD_STORE_PASSWORD` — the keystore password.
- `UPLOAD_KEY_ALIAS` — `upload`.
- `UPLOAD_KEY_PASSWORD` — the key password.

Back up `upload-keystore.jks` and its password in your password manager, then
delete the local copy.

### What CI does with it

For a clean `vX.Y.Z` tag, `release.yml` first builds and attaches the signed
APK exactly as before. Then it builds `bundleRelease` **without** the
`SIGNING_*` variables (so Gradle leaves the bundle unsigned, as on F-Droid),
signs it with `jarsigner` and the upload key, prints the upload certificate's
SHA-256 in the job log, and attaches `Bondwidth-X.Y.Z.aab`. That SHA-256 must
match **Upload key certificate** in Play Console. The Gradle signing config is
not touched, so F-Droid's reproducible APK build is unaffected.

If any `UPLOAD_*` secret is missing, the bundle steps are skipped with a
warning and the APK release still succeeds.

## Local development

Local Gradle builds do **not** require the signing secrets. The
`signingConfigs.debug` block in `app/build.gradle.kts` uses the keystore only
when all four `SIGNING_*` environment variables are set and the keystore file
exists (as they are in CI). Locally it falls back to AGP's auto-generated debug
key. Without those variables `./gradlew bundleRelease` gives an unsigned
bundle; the upload key is only ever applied by `release.yml`.
