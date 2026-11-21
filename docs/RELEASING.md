# Releasing

## How a release happens

Nothing to do by hand except merging a PR. [release-please](https://github.com/googleapis/release-please) reads the commit messages on `dev`:

| Commit | Next version | In the CHANGELOG |
|--------|--------------|------------------|
| `fix: …` | patch (0.1.0 → 0.1.1) | Fixed |
| `feat: …` | minor (0.1.0 → 0.2.0) | Added |
| `feat!: …` or a `BREAKING CHANGE:` footer | minor while below 1.0, then major | Added, marked breaking |
| `docs:`, `chore:`, `ci:`, `refactor:`, `test:` | none | hidden |

Commits that don't follow [Conventional Commits](https://www.conventionalcommits.org/) are ignored.

1. After each push to `dev`, [`release.yml`](../.github/workflows/release.yml) opens or updates a PR titled `chore(dev): release X.Y.Z`. It bumps `version.txt` (the app's only version source; the version code comes from it, 1.2.3 → 10203) and adds the notes to [CHANGELOG.md](../CHANGELOG.md).
2. Merge it when you want to ship. Edit the PR first to reword the notes.
3. The merge tags `vX.Y.Z` and creates the GitHub release. The same workflow then runs the tests, builds the APK, signs it, checks the signature and attaches `viewfinder-X.Y.Z.apk`.

If the APK job fails, fix the cause and re-run that job from the Actions tab; it replaces the APK on the existing release.

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
