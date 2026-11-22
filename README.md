# Luo World Box

A god-simulator sandbox for **Android**. Shape a world, spawn life, and watch villages grow into
kingdoms that trade, fight and burn. Free for everyone:

- **No ads, no purchases, no locked features.** Every power is unlocked from the first launch.
- **No permissions** at all (no internet, no tracking).
- **Every successful build is released automatically** as a signed APK on the
  [Releases](../../releases) page.

> Luo World Box is an independent game. All code and art are written for this project;
> it uses no assets or code from other games.

## What is in the game

- **Detailed creatures:** 24×24 shaded sprites with 4-frame walk cycles, species details (antlers, tusks,
  beards, bushy tails, wings), colour variants so villagers are not clones, shadows, kids drawn smaller.
- **Designed effects:** explosions with shockwave, sparks, smoke and debris plus screen shake; branching
  lightning with a flash; rain and acid clouds; tornado funnels with swirling debris; black holes with an
  accretion disk; erupting volcanoes; meteors with fiery trails; flames and smoke on burning land; spawn
  sparkles; earthquake cracks; sparks when villagers chop, mine and build.
- **Pixel-art everything:** every creature, tree and house is a real sprite (walk cycles, facing,
  kingdom-coloured roofs that grow from huts to big houses). Terrain has relief shading and foam shorelines.
- **Cosmic worlds:** new worlds default to 2224×2224 tiles (about 5 million), ten times the area of the
  Titan size and about 300 times the standard size. Sizes go from Tiny 64 through Titan 704 and
  Gargantuan 1280 to Cosmic 2224. World generation uses every CPU core, and if a phone cannot hold a world
  that big the game automatically falls back to a smaller one. Bigger worlds get more continents, islands and mountain ranges,
  and new worlds start with herds, predators and tribes already living in them.
- **Daily life you can watch:** villagers have jobs. Woodcutters chop trees with axes, farmers work the
  fields with hoes, miners swing pickaxes, builders raise new houses with hammers, hunters carry spears.
  They carry wood, crops, ore and meat home, **walk into their houses** to store it, eat and rest, and
  sleep inside at night while windows glow and chimneys smoke. Tools and carried goods are drawn on them.
  Mined ore improves a village's tools, so work gets faster.
- **Living world:** villages build houses and farm fields, kingdoms form, wage war and **capture villages**,
  fires spread, volcanoes erupt, zombies infect, dragons burn.
- **55+ powers** in six tabs, three brush-size steps, speed control, 7 world ages with events.
- **Minimap**, event toasts, world history, population graph, three save slots plus autosave.
- **Synthesised sound effects** (no audio files shipped) and saved settings.

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
