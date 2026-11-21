# Releasing

## Cutting a release

The version lives in one place: `viewfinder.version` in `gradle.properties`. The version code comes from it (1.2.3 → 10203), so every release must have a higher version than the last.

1. In [CHANGELOG.md](../CHANGELOG.md), move the notes under `## [Unreleased]` into a new `## [X.Y.Z] - YYYY-MM-DD` section.
2. Set `viewfinder.version=X.Y.Z` and commit.
3. Tag and push:

   ```bash
   git tag vX.Y.Z && git push origin vX.Y.Z
   ```

[`release.yml`](../.github/workflows/release.yml) then:

1. runs the tests (the same job as CI),
2. fails if the tag isn't `v` + `viewfinder.version`, or CHANGELOG.md has no section for it,
3. builds the release APK, signs it and checks the signature,
4. publishes a GitHub release named `Viewfinder X.Y.Z` with `viewfinder-X.Y.Z.apk` and the CHANGELOG section as notes.

To fix a failed release, delete the tag (`git push --delete origin vX.Y.Z && git tag -d vX.Y.Z`), fix, and tag again.

## Signing

Releases are signed with one key, kept in two places:

| Where | What |
|-------|------|
| `~/.android/viewfinder-release.jks` | The keystore (PKCS12, alias `viewfinder`, valid until 2054). |
| macOS Keychain, item `viewfinder-release-keystore` | Its password. The store and key passwords are the same. |
| GitHub secrets | `VIEWFINDER_KEYSTORE_BASE64`, `VIEWFINDER_KEYSTORE_PASSWORD`, `VIEWFINDER_KEY_ALIAS`, `VIEWFINDER_KEY_PASSWORD`, for the workflow. |

**Back up the keystore and its password together, outside this machine** (a password manager is fine). GitHub secrets can't be read back. If the key is lost, the next release can't update an installed copy: it has to be uninstalled first, which deletes the app's data.

Key fingerprint (SHA-256):

```
5D:88:42:80:F1:C4:CB:35:36:FB:E9:1B:1D:F2:0C:D5:25:44:A2:CF:82:E8:1D:43:AC:4D:BA:7E:B8:1B:4D:1A
```

### A signed build on this machine

```bash
export VIEWFINDER_KEYSTORE=~/.android/viewfinder-release.jks
export VIEWFINDER_KEY_ALIAS=viewfinder
export VIEWFINDER_KEYSTORE_PASSWORD="$(security find-generic-password -s viewfinder-release-keystore -w)"
export VIEWFINDER_KEY_PASSWORD="$VIEWFINDER_KEYSTORE_PASSWORD"
./gradlew :app:assembleRelease      # app/build/outputs/apk/release/app-release.apk
```

Without those variables, `assembleRelease` builds an unsigned APK.

### Debug and release installs

Debug builds are signed with the SDK's debug key, releases with this one. Android won't update one with the other, so moving between them means uninstalling first, which deletes the app's data.

### Replacing the secrets

```bash
base64 -i ~/.android/viewfinder-release.jks | tr -d '\n' | gh secret set VIEWFINDER_KEYSTORE_BASE64
security find-generic-password -s viewfinder-release-keystore -w | tr -d '\n' | gh secret set VIEWFINDER_KEYSTORE_PASSWORD
security find-generic-password -s viewfinder-release-keystore -w | tr -d '\n' | gh secret set VIEWFINDER_KEY_PASSWORD
gh secret set VIEWFINDER_KEY_ALIAS --body viewfinder
```
