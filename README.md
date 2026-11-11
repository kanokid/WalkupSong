# Walkup Song

Walkup Song is a simple Android app that allows you to create a batting order for a softball team and assign a walk-up song to each player using local audio files.

## Features

*   **Batting Order Management:** Add, remove, and reorder players in the batting list.
*   **Local File Selection:** Select audio files from your device's storage.
*   **Clip playback:** Set start and end times in whole seconds, pause/resume, stop, or move to the next batter. A completed clip selects the next batter without autoplaying.
*   **Change segment:** Selecting a song automatically transcribes word-level timestamps and opens the segment picker; you can also reopen **Change segment** anytime from a player's three-dot menu and preview the selected clip.
*   **Material 3 UI:** Expressive rounded cards, large playback controls, light/dark themes, and compact controls for small screens or enlarged text.
*   **Data persistence:** Players, order, song references, clip times, selection, and word timestamps are saved locally. Existing lineups are preserved when upgrading.

## Getting Started

To get started with Walkup Song, you'll need to have Android Studio installed.

1.  **Clone the repository.**
2.  **Open the project in Android Studio.**
3.  Use **JDK 17**, Android SDK platform **34**, and build tools **34.0.0**. Android Studio creates your machine-specific `local.properties`; command-line builds can use `ANDROID_HOME` instead.
4.  Build and run on Android **5.0 (API 21)** or later.

Command-line verification:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

On Windows use `gradlew.bat`. The freshly built APK is `app/build/outputs/apk/debug/app-debug.apk`; the old APK at the repository root is not updated by Gradle.

Debug signing keys are machine-specific. To upgrade an installed copy without uninstalling and losing its local data, build with the same signing key as that copy.

The Robolectric suite exercises Android APIs 21, 28, and 34, including clip boundaries, cancellation, persistence, and word-level song transcription. It simulates the media player and HTTP transport; actual decoding, Bluetooth routing, and provider-specific file access still require a physical-device check.

## How to Use

1.  **Add players:** Tap **Add player**, enter a name and jersey number, and save.
2.  **Select a player:** Tap on a player in the list to select them.
3.  **Choose music & change segment:** Tap **Choose song** to open Android's document picker. Selecting a song automatically transcribes its words and opens the **Change segment** dialog, where you can tap a start word and end word, adjust start and end seconds, or tap **Preview clip**.
4.  **Reopen segment selection:** Open a player's three-dot menu and tap **Change segment** to adjust the clip or re-transcribe.
5.  **Control playback:** Play/pause toggles playback. Stop resets the clip without advancing. Next selects the next batter and wraps around. Selecting another player, leaving the app, losing audio focus, or unplugging headphones stops playback; this app does not play in the background.
6.  **Manage the lineup:** Each player's three-dot menu includes edit, move up/down, and remove. Removal requires confirmation.

Keep imported files available on the device. Moving, deleting, or revoking access to a file may require choosing it again. Clear the lineup through **Settings → Clear Batting Order** only when you want to delete all players.

## Dependencies

*   [AndroidX AppCompat](https://developer.android.com/jetpack/androidx/releases/appcompat)
*   [Material Components for Android](https://github.com/material-components/material-components-android)
*   [Gson](https://github.com/google/gson)

## License

This project is licensed under the GPL 3.0 License - see the [LICENSE.md](LICENSE.md) file for details.
