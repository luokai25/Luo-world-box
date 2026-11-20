# Luo World Box

A god-simulator sandbox for **Android**. Shape a world, spawn life, and watch villages grow into
kingdoms that trade, fight and burn. Free for everyone:

- **No ads, no purchases, no locked features.** Every power is unlocked from the first launch.
- **No permissions** at all (no internet, no tracking).
- **Every successful build is released automatically** as a signed APK on the
  [Releases](../../releases) page.

> Luo World Box is an independent game. All code and art are written for this project;
> it uses no assets or code from other games.

## Install

1. Open the latest [release](../../releases/latest) on your phone.
2. Download `LuoWorldBox-vX.Y.Z.apk` and open it (allow "install unknown apps" for your browser if asked).
3. Android 8.0 (API 26) or newer.

Updates install over the old version, and your world is auto-saved when you leave the game.

## How it is built and released

`.github/workflows/release.yml` runs on every push to `main`:

1. Builds a signed release APK with Gradle.
2. **Only if the build succeeds**, creates the release `v0.1.<run number>` with the APK attached.

A failed build publishes nothing. The version code is the CI run number, so each release is a clean update.

The APK is signed with the committed `app/luoworldbox.jks` key. It is a public hobby key: fine for
sideloading, but replace it with a private key (and store it as a GitHub secret) before any store release.

## Project layout

```
app/src/main/java/com/luokai/luoworldbox/
  core/        pure-Kotlin simulation (no Android imports): terrain generation, creatures,
               villages, kingdoms, wars, fire, disasters, powers, saves, reports
  GameView.kt  SurfaceView game loop, renderer, HUD, touch controls
  MainActivity.kt  menus, world-config screen, save slots
  Powers.kt    the power bar (tabs and buttons)
docs/REVERSE_ENGINEERING.md   what the reference screenshots show and what is implemented
```

## Build locally

Android Studio (Hedgehog or newer) can open the folder directly. From a terminal with JDK 17,
the Android SDK and Gradle 8.9:

```
gradle assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`.
