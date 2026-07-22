# SlotBot Lab

A deliberately isolated Android test project for experimenting with a pull-to-refresh automation loop.

## Important safety property

The AccessibilityService is currently restricted to:

`com.example.slotbotlab`

That means this test build is configured to receive accessibility events only from its own mock app, not from Glovo or other apps.

## What the mock screen does

- Shows fixed filters: `10:00-23:59` and `BEG WEST`
- Supports real pull-to-refresh
- Every third refresh creates a fake session
- Fake sessions contain a real `Book` button
- The automation service swipes down, waits for refresh, finds exact visible text `Book`, and clicks it
- The UI shows refresh count, booked count, detected buttons, and click attempts
- `CREATE TEST SESSION` lets you create a slot immediately

## Build requirements

- Android Studio with Android SDK 36 installed
- JDK 17
- Gradle wrapper/version: 8.13
- Android Gradle Plugin: 8.13.2
- Kotlin: 2.3.21

The project intentionally does not include generated Gradle wrapper binary files. Android Studio can sync it, or you can generate the wrapper with a local Gradle 8.13 installation:

`gradle wrapper --gradle-version 8.13`

## Test flow

1. Open the project in Android Studio.
2. Let Gradle sync.
3. Run the app on the phone.
4. Tap `OPEN ACCESSIBILITY SETTINGS`.
5. Enable `SlotBot Lab automation`.
6. Return to the app.
7. Tap `START BOT`.
8. Leave the mock session screen visible.

Expected result:

- Bot performs a downward swipe every 5 seconds.
- Every third successful mock refresh creates one session.
- Bot finds the exact `Book` text and presses its clickable button.
- The button changes to `Booked`.

## If Accessibility is blocked after sideloading

Recent Android versions can treat accessibility access from sideloaded apps as a restricted setting. Only enable restricted settings for an APK that you built yourself and trust.

## Current limitations

This is an MVP test harness:
- no real Glovo integration
- no background/locked-screen operation
- no recovery navigation
- no multi-screen state machine
- the service is package-restricted to this app
