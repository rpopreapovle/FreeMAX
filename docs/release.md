# Release build and signing

## What lives where

| Item | Location |
|---|---|
| Release keystore | Outside the repository, path in `FREEMAX_KEYSTORE_FILE` |
| Passwords and alias | `~/.freemax-release.env` (chmod 600) |
| R8 rules | `app/proguard-rules.pro` |
| Version | `app/build.gradle.kts` (`defaultConfig`) |

Secrets are **never committed**: the keystore and `.env` live outside the
repository. `.gitignore` excludes `*.keystore`, `*.jks`, `*.env`,
`release.properties` and `local.properties`.

## `~/.freemax-release.env` format

```sh
FREEMAX_KEYSTORE_FILE=/path/to/release.keystore
FREEMAX_KEYSTORE_PASSWORD=<password>
FREEMAX_KEY_ALIAS=<alias>
FREEMAX_KEY_PASSWORD=<password>
```

`app/build.gradle.kts` reads environment variables with these names first,
then the file above. A release build without signing secrets fails with a
clear error instead of “Keystore file not set”.

## Build a signed release

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:assembleRelease \
  -PFREEMAX_STT_API_KEY= \
  -PFREEMAX_STT_ENDPOINT=
```

The empty STT values are important for public builds: they guarantee that no
private speech-recognition key or endpoint is baked into the APK.

Result: `app/build/outputs/apk/release/app-release.apk`.

Verify the signature:

```sh
$ANDROID_HOME/build-tools/<version>/apksigner verify --print-certs \
    app/build/outputs/apk/release/app-release.apk
```

## Versioning

`versionCode` and `versionName` are in `app/build.gradle.kts`
(`defaultConfig`). Increment `versionCode` for every distributed build,
otherwise Android will not install the new APK over the old one.

## What is different in release

- **No GeckoView.** The in-app website login lives in `app/src/debug` and is
  connected as `debugImplementation`. Release uses the “sign in with a token”
  flow (`PasteTokenScreen`): get the token from `web.max.ru` → DevTools →
  `localStorage.getItem('__oneme_auth')`.
- **No debug hooks.** `DebugNightHook` and the adb-only test extras live in
  `app/src/debug` and are not part of the release APK. In debug builds they are
  reachable only through the non-exported `DebugActivity` and require the
  explicit `freemax_test_enable=true` extra.
- `minifyEnabled` and `shrinkResources` are on. `Log.d`/`Log.v` are stripped,
  because payload logs may contain tokens or messages. Useful `Log.i/e`
  remain.

## Distribution

Upload the signed APK to the GitHub Releases page of the project. Keep the
release keystore safe: without it you cannot ship an update that installs over
an existing installation (the signatures must match).
