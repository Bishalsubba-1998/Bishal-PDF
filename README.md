# SumatraDroid

A SumatraPDF-style document reader for Android, written in Kotlin with no AndroidX dependency.

It follows the design of [SumatraPDF](https://github.com/sumatrapdfreader/sumatrapdf): the same display modes, zoom levels, themes, tabs, favorites, table of contents, command palette, keyboard shortcuts and per-file remembered state. Rendering uses [MuPDF](https://mupdf.com), the same engine SumatraPDF uses.

**Formats:** PDF, EPUB, XPS/OXPS, CBZ, MOBI, FB2.

## Build the APK (GitHub Actions)

1. Create a new GitHub repository and push this folder to it (`main` branch).
2. Open the **Actions** tab, pick **Build APK**, and click **Run workflow** (it also runs on every push).
3. When the run finishes, download the **SumatraDroid-apk** artifact from the run page. Unzip it and install `app-release.apk` on your phone (allow "install unknown apps").

To build locally instead: install JDK 17 and the Android SDK (platform 35), install Gradle 8.9, then run `gradle :app:assembleRelease`. The APK is written to `app/build/outputs/apk/release/`. The release build is signed with the debug key, so it installs directly but is not suitable for the Play Store.

## Features

| SumatraPDF feature | In SumatraDroid |
|---|---|
| Single page, facing, book view, each continuous or not | Yes, all six modes |
| Fit page, fit width, percentage zoom (8.33% to 6400%) and Sumatra's zoom steps | Yes, plus pinch zoom and double-tap |
| Rotate | Yes |
| Tabs | Yes |
| Recent files start page with thumbnails | Yes (pin, remove) |
| Table of contents sidebar | Yes (tree, current item highlighted) |
| Favorites | Yes (add, rename, delete, next / previous) |
| Find, find next / previous, highlighted hits | Yes (whole document, wraps around) |
| Text selection and copy | Yes (long-press, drag handles) |
| Links (internal, external) | Yes, plus "Show links" overlay |
| Navigate back / forward | Yes |
| Command palette (Ctrl+K) | Yes, tap the menu button or press Ctrl+K |
| Themes | All 22 of Sumatra's built-in themes |
| Invert, grayscale, follow-theme document colors, brightness, contrast | Yes |
| Trim empty margins / fit content | Yes |
| Presentation and fullscreen | Yes |
| Auto-scroll | Yes |
| Page labels | Yes |
| Document properties, print | Yes (Android print framework) |
| Reload | Yes |
| Remember page, zoom, mode, rotation per file | Yes |
| Keyboard shortcuts | Sumatra's accelerators, plus volume keys for paging |
| Annotations | Highlight, underline, strike-out, squiggly; saved as a copy |

Open files from the "+" tab, or from any file manager or browser ("Open with").

## Known limitations

- Search is case-insensitive and does not offer match-case or whole-word options.
- Text selection is limited to one page.
- Annotations are only available for PDF files. They are kept in memory and written with **Save annotated copy** (Android's document provider does not allow editing a file in place).
- This project was written without access to an Android toolchain, so it has not been compiled or run on a device by its author. If the first CI build reports a compile error, paste the log and it can be fixed quickly.
- Not supported: SumatraPDF's Windows-only features (shell integration, installer, scripting, external viewers, DjVu, CHM).

## Licenses and credits

- SumatraDroid is licensed under the **GNU AGPL v3** (see `LICENSE`), because it links MuPDF (AGPLv3, Artifex Software).
- Its design and the 22 color themes are derived from SumatraPDF by Krzysztof Kowalczyk and contributors (GPLv3 / BSD for parts). This project is not affiliated with or endorsed by the SumatraPDF project. The app icon is original artwork.
