# Markdown Reader

A tiny, lightweight Android app for reading Markdown (`.md`) files. No ads, no
accounts, no unnecessary permissions — just pick a file and read it.

## Table of contents

- [Features](#features)
- [Choose how to build it](#choose-how-to-build-it)
- [Option A — GitHub Actions (no install at all)](#option-a--github-actions-no-install-at-all)
- [Option B — Command-line SDK tools (~250 MB, no GUI)](#option-b--command-line-sdk-tools-250-mb-no-gui)
- [Option C — Android Studio (~1–1.5 GB, full IDE)](#option-c--android-studio-1–15-gb-full-ide)
- [Installing the APK on your phone](#installing-the-apk-on-your-phone)
- [Using the app](#using-the-app)
- [Project structure](#project-structure)
- [Notes](#notes)

## Features

- Open any `.md` file from local storage, Google Drive, Downloads, etc. (via the
  standard Android file picker — no storage permission needed)
- Renders headings, bold/italic, lists, links, blockquotes, code blocks, tables,
  and strikethrough
- **Jump to any heading at any time** — a floating "Contents" button (bottom
  right) is available while reading. Tap it to see every heading in the file
  and jump straight to it, or jump back to the top, without scrolling manually
- **Day/night mode** — matches your phone's system light/dark setting
  automatically, or you can override it from the theme icon in the top bar
  (System default / Light / Dark). Your choice is remembered.
- Can also be opened directly from a file manager via "Open with → Markdown Reader"
- Single screen, minimal UI, small APK (~2–3 MB)

Built with Kotlin + [Markwon](https://noties.io/Markwon/) (a lightweight,
well-established Markdown rendering library for Android).

## Choose how to build it

Turning this source code into an installable app needs a build step — Android
apps have to be compiled and signed before a phone will run them. Android
Studio is the option most guides point you to, but **it's a multi-gigabyte
download** just to get one small APK out of it. Two much lighter options exist:

| Option | What you install | Approx. size | Best if... |
|---|---|---|---|
| **A. GitHub Actions** | Nothing at all locally | 0 MB | You just want the APK file, don't want to install any dev tools |
| **B. Command-line tools** | JDK + Android SDK command-line tools | ~250–400 MB | You're comfortable with a terminal and want a lean local setup |
| **C. Android Studio** | Full IDE | ~1–1.5 GB+ | You want to browse/edit the code with a graphical editor and emulator |

All three produce the exact same app. Pick whichever fits — you don't need to
do more than one.

## Option A — GitHub Actions (no install at all)

This builds the APK in the cloud using the included workflow file
(`.github/workflows/build-apk.yml`) and hands you a finished APK to download —
nothing to install on your computer.

1. Create a free account at https://github.com if you don't have one.
2. Create a new **public or private repository** (any name).
3. Upload the entire contents of this unzipped `MarkdownReader` folder into
   that repository (on github.com you can drag-and-drop files with
   **Add file → Upload files**, or use `git push` if you're familiar with git).
4. Once the files are pushed to the `main` branch, go to the **Actions** tab
   of your repository. A workflow called **"Build APK"** will run automatically
   (this takes 2–4 minutes).
5. When it finishes (green checkmark), click into that workflow run, scroll
   down to **Artifacts**, and download **markdown-reader-debug-apk**. It's a
   zip containing `app-debug.apk`.
6. Copy that APK to your phone and install it — see
   [Installing the APK on your phone](#installing-the-apk-on-your-phone).

If you ever edit the code and push again, a new APK is built automatically
each time.

## Option B — Command-line SDK tools (~250 MB, no GUI)

This uses only a JDK and the Android **command-line tools** (a small subset of
Android Studio with no graphical interface), then builds from a terminal.

1. **Install a JDK 17** if you don't already have one:
   - macOS: `brew install openjdk@17`
   - Linux: `sudo apt install openjdk-17-jdk` (Debian/Ubuntu) or your
     distro's equivalent
   - Windows: install [Temurin 17](https://adoptium.net/) and add it to PATH

2. **Download the Android command-line tools** (not full Android Studio) from
   https://developer.android.com/studio#command-tools — pick the "Command
   line tools only" package for your OS, and unzip it somewhere, e.g.
   `~/android-sdk/cmdline-tools/latest/` (the `latest` folder should directly
   contain `bin/`, `lib/`, etc.).

3. **Set environment variables** (add to `~/.zshrc`, `~/.bashrc`, or your
   Windows environment variables):
   ```
   export ANDROID_HOME=~/android-sdk
   export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools
   ```
   Restart your terminal after this.

4. **Install just the pieces this project needs** (platform, build tools, and
   accept the licenses):
   ```
   sdkmanager --licenses
   sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
   ```

5. **Point the project at your SDK.** In the unzipped `MarkdownReader` folder,
   create a file named `local.properties` containing:
   ```
   sdk.dir=/absolute/path/to/android-sdk
   ```
   (On Windows, use double backslashes, e.g. `C\:\\Users\\you\\android-sdk`.)

6. **Build it:**
   ```
   cd MarkdownReader
   ./gradlew assembleDebug
   ```
   (On Windows, run `gradlew.bat assembleDebug` instead.) The first run
   downloads Gradle itself and the project's dependencies, so it needs
   internet access and will take a few minutes.

7. The finished APK is at:
   ```
   app/build/outputs/apk/debug/app-debug.apk
   ```
   Copy it to your phone — see
   [Installing the APK on your phone](#installing-the-apk-on-your-phone).

## Option C — Android Studio (~1–1.5 GB+, full IDE)

Use this if you'd like a graphical editor, an emulator, or plan to keep making
changes to the code interactively.

1. Install **Android Studio** from https://developer.android.com/studio.
2. Open Android Studio → **File → Open** → select the unzipped
   `MarkdownReader` folder, and let Gradle sync (first sync needs internet).
3. **Run on your phone over USB:**
   - On your phone: **Settings → About phone**, tap **Build number** 7 times
     to enable Developer Options.
   - **Settings → Developer options** → enable **USB debugging**.
   - Plug in your phone, accept the "Allow USB debugging?" prompt.
   - Select your phone in Android Studio's device dropdown and click the
     green **Run ▶** button. It installs and opens automatically.
4. **Or build an APK to install manually:**
   **Build → Build Bundle(s) / APK(s) → Build APK(s)**, then find it at
   `app/build/outputs/apk/debug/app-debug.apk` and follow
   [Installing the APK on your phone](#installing-the-apk-on-your-phone).

## Installing the APK on your phone

Once you have an `app-debug.apk` file (from any option above):

1. Get it onto your phone (email it to yourself, AirDrop/Nearby Share, USB
   cable, Google Drive — whatever's convenient).
2. Tap the APK file on your phone to install it.
3. Android will likely ask you to allow "Install unknown apps" for whichever
   app you opened the file with — it'll link you straight to that setting.
   Allow it, then go back and tap the APK again.
4. Open **Markdown Reader** from your app drawer once installed.

## Using the app

1. Open the app and tap **Open Markdown File**.
2. Pick any `.md` file from your device or a connected cloud storage app.
3. The rendered file appears immediately, scrollable.
4. While reading, tap the round **Contents** button floating at the bottom
   right to open a list of every heading in the document (indented by heading
   level). Tap any heading — or "Top of document" — to jump straight there.
5. You can also long-press a `.md` file in a file manager app and choose
   **"Open with → Markdown Reader"** once it's installed.
6. Tap the sun/moon icon in the top bar to switch between **System default**,
   **Light**, and **Dark** — it applies instantly and is remembered next time
   you open the app.

> The Contents button only appears for files that actually contain Markdown
> headings (lines starting with `#`, `##`, etc.).

## Project structure

```
MarkdownReader/
├── .github/workflows/build-apk.yml  # GitHub Actions: builds APK on push
├── app/
│   ├── build.gradle                 # app dependencies (Markwon, AndroidX)
│   └── src/main/
│       ├── AndroidManifest.xml      # app + file-association config
│       ├── java/.../MainActivity.kt # all app logic
│       └── res/                     # layouts, colors (incl. dark mode), strings
├── gradlew / gradlew.bat            # command-line build launchers
├── gradle/wrapper/                  # Gradle wrapper (used by gradlew)
├── build.gradle                     # root Gradle config
├── settings.gradle
└── gradle.properties
```

## Notes

- Minimum supported Android version: Android 5.0 (Lollipop, API 21) — covers
  virtually all active Android devices.
- No internet, storage, or camera permissions are requested. Files are read
  through Android's Storage Access Framework, which only grants access to the
  specific file you choose.
- The APK produced by all three options above is a **debug build**, signed
  with an auto-generated debug key — perfectly fine for installing on your
  own device, but not intended for distribution on the Play Store.
- To customize the app icon or name, edit `app/src/main/res/values/strings.xml`
  (name) or add your own image to `res/mipmap` folders (icon).
