# FlatLaf 3.7.2

WordNest 0.4.1 uses the unmodified FlatLaf library by FormDev Software GmbH and its contributors. FlatLaf provides this application's look and feel and window decorations. It is distributed under Apache License 2.0; the complete upstream license is retained in `FlatLaf-APACHE-2.0.txt` and inside the upstream JAR. Application code, dictionary data and other independent resources retain their own licensing.

- Project: https://github.com/JFormDesigner/FlatLaf
- Fixed release: https://github.com/JFormDesigner/FlatLaf/releases/tag/3.7.2
- Library: https://repo1.maven.org/maven2/com/formdev/flatlaf/3.7.2/flatlaf-3.7.2.jar
- Windows x64 native library: https://repo1.maven.org/maven2/com/formdev/flatlaf/3.7.2/flatlaf-3.7.2-windows-x86_64.dll
- Upstream license: https://raw.githubusercontent.com/JFormDesigner/FlatLaf/3.7.2/LICENSE

SHA-256 values, checked against the respective `.sha256` files on Maven Central:

```text
917aff3963c88d797d0fd9b9ccbd70f7681c101df9d11c59e2bc7a3a6c0fabf4  flatlaf-3.7.2.jar
533407841892e294f70e4f1443ebddecbea83b7f0bd2af876181505073b677d5  flatlaf-3.7.2-windows-x86_64.dll
```

Both files are retained under the source project's `lib/` directory. `scripts/build.ps1` verifies their checksums before compilation, copies them unchanged to `app/lib/` in the Windows image, and adds `lib/flatlaf-3.7.2.jar` to the application's JAR manifest class path. The x64 DLL is placed next to the FlatLaf JAR as recommended by the upstream native-library distribution guide, so supported Windows systems can load it without first extracting it to a temporary directory. The original JAR also retains its bundled native resources.

Relevant official documentation:

- Window decorations: https://www.formdev.com/flatlaf/window-decorations/
- Title bar settings: https://www.formdev.com/flatlaf/components/titlebar/
- Native library distribution: https://www.formdev.com/flatlaf/native-libraries/

WordNest sets its own light paper and green title bar colors through Swing UI defaults. It does not alter system theme, registry, desktop, taskbar or other applications. Windows 10/11 native decoration support preserves the window system controls and native move/resize/snap behavior; Swing-decorated fallback is enabled when native decoration support is unavailable. Actual on-screen window behavior still requires a normal GUI acceptance check, which was not performed during the user's desktop-control restriction.
