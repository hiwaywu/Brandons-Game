# Brandon's Game

Android minigame collection. **Minigame No.1 — Coin Grab**

## How to play

- **Move** with the left joystick
- **Attack** with the red ATK button (random attack each time)
- Grab coins before the **3:00** timer ends
- Dodge **fireballs** — you have **3 lives**
- Successful attacks steal **3 coins** while the Steal Coin effect is active (**10 seconds**)
- Close-range attacks (Throw, Punch, Kick) miss if you are too far — and attacks can miss randomly
- Touch the **blue dragon** for **+50% speed** (the blue dragon moves at **2×** your base speed)
- Other dragons grant random abilities: magnet, shield, slow fireballs, rage

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
