# Development

## Source layout

`app/src/main` contains the app, `app/src/test` contains JVM tests, and `gradle` contains the wrapper and dependency catalog. Icon generation scripts are under `tools` and require Blender or Pillow as appropriate; they are optional for building the existing app.

Test fixtures use synthetic names and reserved example addresses. Protocol constants and routing ranges used to test networking behavior are independent of any deployment.

## Verification

Run Gradle checks sequentially in one invocation:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
```

These checks cover source, JVM tests, lint, and debug assembly. Runtime compatibility, live-server behavior, release signing, and Play Store acceptance require separate verification.

## Publication checks

Follow the [publication checklist](PUBLICATION.md) for every upload, including private repositories. Secret scanning alone does not identify personal information, machine names, or identifying Git metadata.

The Gitleaks configuration narrowly allows two reviewed JavaScript export aliases in bundled xterm.js that the generic API-key rule otherwise flags. It does not exclude the entire vendor file.

## Release work still required

Finish dependency/provenance and branding reviews; configure production signing and app bundles; prepare a privacy policy, Data safety declarations, reviewer access/demo instructions, store assets, and the applicable Google Play testing requirements. Sharing source is separate from release acceptance.
