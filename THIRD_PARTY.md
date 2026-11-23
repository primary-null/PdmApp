# Third-party components

This initial inventory covers bundled assets and declared direct library families. It is not a complete transitive dependency audit or a certification of release compliance. Original PdmApp code and project materials are licensed under Apache License 2.0 unless otherwise specified; see [LICENSE](LICENSE) and [NOTICE](NOTICE).

## Bundled assets

- **xterm.js and its fit addon**: MIT license. Preserve [the bundled notice](app/src/main/assets/xterm/LICENSE-xterm.txt), including its copyright statements. The terminal CSS also carries embedded notices. Upstream: <https://github.com/xtermjs/xterm.js>.
- **Orbitron font**: SIL Open Font License 1.1, with the Reserved Font Name Orbitron. Preserve [the bundled license](app/src/main/assets/licenses/Orbitron-OFL.txt). Upstream: <https://github.com/theleagueof/orbitron>.

## Declared library families

AndroidX/Compose, Kotlin and kotlinx, OkHttp, and Retrofit use Apache License 2.0 in their upstream projects. Verify exact resolved versions, transitive dependencies, required notices, and any exceptions before binary distribution. Versions are declared in `gradle/libs.versions.toml` and `app/build.gradle.kts`.

JUnit and Bouncy Castle are declared for tests. Audit their resolved test dependencies separately if redistributing test tooling or artifacts.

## Before public or Play Store distribution

- Review the provenance of reused application code and all artwork, including launcher branding.
- Generate the resolved dependency inventory and assemble the required license/NOTICE texts.
- The current Android packaging excludes several `META-INF/LICENSE` and `META-INF/NOTICE` paths. Ensure required notices are supplied in the distributed app before shipping.
- Selecting a license for original code does not replace third-party licenses or grant rights to Proxmox trademarks.
