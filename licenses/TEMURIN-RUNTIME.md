# Bundled Java runtime

The portable Windows distribution of WordNest includes an unmodified Eclipse
Temurin OpenJDK 17 runtime from the Eclipse Adoptium project. The source-only
distribution does not bundle that runtime.

- Product: Eclipse Temurin OpenJDK JRE, Windows x64, HotSpot
- Version: 17.0.20.1+1
- Upstream archive: `OpenJDK17U-jre_x64_windows_hotspot_17.0.20.1_1.zip`
- SHA-256: `bc21a93923103cdaac93ee337b0ae4365e739fde36df823dd456bc67c8a9d352`
- Official release and corresponding source downloads:
  <https://github.com/adoptium/temurin17-binaries/releases/tag/jdk-17.0.20.1%2B1>
- Official API used to locate the release:
  <https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jre&os=windows&vendor=eclipse>
- Project: <https://adoptium.net/>

OpenJDK is distributed under the GNU General Public License, version 2, with the
Classpath Exception where specified. Copyright notices, module-specific license
files, and third-party notices are retained in the packaged runtime's `legal/`
directory. Consult those original files for the applicable terms. Eclipse
Temurin, Java, and OpenJDK names belong to their respective owners.

The development computer's Oracle JDK is used only as a local build tool; its
runtime is not bundled in the portable application.
