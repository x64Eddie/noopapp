# Personal build: shipping updates to the phone

The phone runs `com.noop.whoop.debug`, sideloaded and signed with the debug key from the build server.
Android only installs an update over it when the new APK has the **same application id**, the **same
signing key** and a **higher versionCode**. The pipeline below guarantees all three.

## One-time setup

1. Put the signing key in the repo checkout (git-ignored) and in GitHub:
   ```bash
   scp precision:.android/debug.keystore android/fork-debug.keystore
   gh secret set FORK_DEBUG_KEYSTORE_B64 -R x64Eddie/noopapp < <(base64 -i android/fork-debug.keystore)
   ```
   Check the fingerprint matches the installed app:
   `keytool -list -v -keystore android/fork-debug.keystore -storepass android | grep SHA256`
   → `03:91:A6:B4:…:9B:19:B3`.
2. On the phone, install [Obtainium](https://github.com/ImranR98/Obtainium) and add
   `https://github.com/x64Eddie/noopapp`. It polls the releases and installs updates with one tap
   (Android 12+ can update silently once Obtainium has installed the app once).
3. Before the first install of a personal build: in NOOP, Settings → Backup → export a `.noopbak`.

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
git fetch ryanbr && git rebase ryanbr/main   # on a branch; resolve, test, then merge to main
```
Upstream Room migrations run on first launch; the chain has no destructive fallback, so data is kept.
