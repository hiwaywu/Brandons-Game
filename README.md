# Brandon's Game

Android minigame collection. **Minigame No.1 — Coin Grab**

## How to play

- **Move** with the left joystick
- Grab coins before the **3:00** timer ends
- Dodge **fireballs** from cannons — you have **3 lives**
- Touch the **red rage dragon**, then bump rivals to steal **3 coins** (rage lasts **10 seconds**)
- Touch the **blue dragon** for **+50% speed** (the blue dragon moves at **2×** your base speed)
- Other dragons grant random abilities: magnet, shield, slow fireballs

## Build APK

```bash
./gradlew assembleRelease
```

APK output: `app/build/outputs/apk/release/app-release-unsigned.apk`

Or use the signed/debug build:

```bash
./gradlew assembleDebug
```

## Requirements

- JDK 17+
- Android SDK 35
