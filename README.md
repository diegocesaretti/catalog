# Snap Penalty Wear 0.1.0

A lightweight Wear OS penalty-kick prototype controlled by wrist orientation and finger snaps.

## Included in this MVP

- Wrist aiming with `TYPE_GAME_ROTATION_VECTOR` and a smoothed red target dot.
- Tap the crosshair button to recenter the neutral wrist position.
- Finger-snap shooting through the watch microphone.
- Adjustable aim sensitivity and snap sensitivity from 1 to 10.
- Live microphone meter and snap-test indicator in Settings.
- Tap-to-shoot fallback.
- Random goalkeeper dives: left, center or right; high or low.
- Three goalkeeper difficulty levels.
- Five-shot rounds, score counter, ball flight, haptics and restart flow.
- Round-screen friendly 2.5D renderer with no external runtime libraries.

## Build requirements

- JDK 17
- Gradle 8.13
- Android Gradle Plugin 8.13.2
- Android SDK 36
- Android SDK Build Tools 35.0.0

Build with:

```bash
gradle :app:assembleDebug
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Device test

The motion and microphone controls must be tuned on a physical watch. The initial target is the Galaxy Watch 4 Classic running Wear OS.

1. Install the APK with ADB.
2. Grant microphone access.
3. Hold the watch naturally and tap the crosshair icon.
4. Tilt/rotate the wrist to move the red dot.
5. Snap with the other hand to shoot.
6. Open the gear icon and tune both sensitivities.

## Renderer direction

This version deliberately uses an efficient Canvas-based 2.5D renderer. A later version can replace `GameView` with OpenGL ES while keeping `WristAimController`, `SnapDetector`, preferences and game logic.

## Prototype art

The stadium, pitch, net and goal are drawn procedurally in the app, so the prototype does not depend on external image assets.
