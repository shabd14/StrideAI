# Stride AI

A futuristic, privacy-first Android pedometer built with Kotlin and Jetpack Compose.

## Included

- Live step counting through Android's hardware step counter (with step-detector fallback)
- Daily goal, distance, calorie, and active-time estimates
- An on-device AI-style coach that recommends a paced next action and adaptive daily goal
- Persisted daily totals and a seven-day pulse chart
- Hindi-first futuristic neon interface

## Run it

Open this folder in Android Studio (with JDK 17 and Android SDK 35), let Gradle sync, and run it on a physical Android phone. On Android 10+, grant the **Physical activity** permission when requested. The Android emulator usually does not expose a real step sensor.

The coach is local and uses recent activity patterns; it sends no health data to a server.
