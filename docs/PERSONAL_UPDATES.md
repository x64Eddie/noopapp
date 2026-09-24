# Personal build: shipping updates to the phone

The phone runs `com.noop.whoop.debug`, sideloaded and signed with the debug key from the build server.
Android only installs an update over it when the new APK has the **same application id**, the **same
signing key** and a **higher versionCode**. The pipeline below guarantees all three.

## One-time setup

1. Put the signing key in the repo checkout (git-ignored) and in GitHub, and record the fingerprint CI
   checks the built APK against (also kept out of tracked source, as a repo variable):
   ```bash
   scp <build host>:.android/debug.keystore android/fork-debug.keystore
   gh secret set FORK_DEBUG_KEYSTORE_B64 -R x64Eddie/noopapp < <(base64 -i android/fork-debug.keystore)
   sha256=$(keytool -list -v -keystore android/fork-debug.keystore -storepass android \
     | sed -n 's/.*SHA256: *//p' | tr -d ':' | tr 'A-F' 'a-f')
   gh variable set SIGNING_SHA256 -R x64Eddie/noopapp --body "$sha256"
   ```
   Confirm it matches the phone's installed app: Settings → Apps → NOOP → App details → signing
   certificate, or `keytool -list -v -keystore android/fork-debug.keystore -storepass android`.
2. On the phone, install [Obtainium](https://github.com/ImranR98/Obtainium) and add
   `https://github.com/x64Eddie/noopapp`. It polls the releases and installs updates with one tap
   (Android 12+ can update silently once Obtainium has installed the app once).
3. Before the first install of a personal build: in NOOP, Settings → Backup → export a `.noopbak`.

The in-app daily update check (`UpdateCheck`) polls the same repo: `BuildConfig.PERSONAL_UPDATE_REPO`,
a build-time property (`-PpersonalUpdateRepo=<owner>/<repo>`, default `x64Eddie/noopapp`), not a literal
in source — forking this pipeline to another repo needs no source edit, just that property (and the two
above: `FORK_DEBUG_KEYSTORE_B64`, `SIGNING_SHA256`).

## Every update

Merge to `main`. `.github/workflows/personal-release.yml` builds
`assembleFullRelease -PpersonalRelease -PpersonalBuild=<run number>` and publishes release
`v<version>.<run>` with the APK attached. Obtainium (and the app's own daily update check) offers it.

Manual build without CI:
```bash
cd android && ./gradlew assembleFullRelease -PpersonalRelease -PpersonalBuild=<n higher than installed>
adb install -r app/build/outputs/apk/full/release/app-full-release.apk
```

## Pulling upstream

```bash
git fetch upstream && git rebase upstream/main   # upstream = ryanbr/noop; on a branch, test, then merge
```
Upstream Room migrations run on first launch; the chain has no destructive fallback, so data is kept.
