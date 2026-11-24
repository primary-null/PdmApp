# Third-party components

This inventory identifies the third-party assets committed to this repository and the declared direct library families. Original PdmApp code and project materials are licensed under Apache License 2.0 unless otherwise specified; see [LICENSE](LICENSE) and [NOTICE](NOTICE). The licenses below continue to govern their respective components.

## Bundled assets

- **xterm.js 6.0.0**: MIT license. Preserve [the bundled notice](app/src/main/assets/xterm/LICENSE-xterm.txt), including its copyright statements. The JavaScript and CSS match the upstream package apart from line endings. Upstream: <https://github.com/xtermjs/xterm.js>.
- **@xterm/addon-fit 0.11.0**: MIT license. Preserve [its bundled notice](app/src/main/assets/xterm/LICENSE-addon-fit.txt). The JavaScript matches the upstream package apart from line endings. Upstream: <https://github.com/xtermjs/xterm.js/tree/master/addons/addon-fit>.
- **Orbitron Medium and Bold, version 2.001**: SIL Open Font License 1.1, with the Reserved Font Name Orbitron. The font files are unmodified Google Fonts distributions. Preserve [the bundled license](app/src/main/assets/licenses/Orbitron-OFL.txt). Upstream: <https://github.com/theleagueof/orbitron> and <https://fonts.google.com/specimen/Orbitron>.
- **Gradle wrapper and launcher scripts**: Apache License 2.0. Preserve the launcher copyright and license headers and the [Apache license text](LICENSE). The wrapper bootstraps the Gradle version in `gradle/wrapper/gradle-wrapper.properties`. Upstream: <https://github.com/gradle/gradle>.

The launcher artwork is rendered by the included Blender script. Font files from the operating system are not bundled with the artwork or redistributed by that script.

## Declared library families

AndroidX/Compose, Kotlin and kotlinx, OkHttp, and Retrofit use Apache License 2.0 in their upstream projects. Verify exact resolved versions, transitive dependencies, required notices, and any exceptions before binary distribution. Versions are declared in `gradle/libs.versions.toml` and `app/build.gradle.kts`.

Test dependencies include JUnit 4 (Eclipse Public License 1.0), Bouncy Castle (Bouncy Castle Licence), and Hamcrest (BSD license). They are fetched by Gradle rather than committed to this repository. Retain their applicable licenses and notices if redistributing test tooling or artifacts.

## Before binary or Play Store distribution

- Review the provenance of reused application code and all artwork, including launcher branding.
- Generate the resolved dependency inventory and assemble the required license/NOTICE texts.
- The current Android packaging excludes several `META-INF/LICENSE` and `META-INF/NOTICE` paths. Ensure required notices are supplied in the distributed app before shipping.
- Selecting a license for original code does not replace third-party licenses or grant rights to Proxmox trademarks.
