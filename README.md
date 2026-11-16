<div align="center">

# Reels Killer

**A moment's pause before the next video.**

An Android app that notices when you are scrolling Reels, Shorts or TikTok<br>
and calmly asks whether you really want to stay.

[![Build](https://github.com/MichaelZama/reels-killer/actions/workflows/build.yml/badge.svg)](https://github.com/MichaelZama/reels-killer/actions/workflows/build.yml)
[![Latest release](https://img.shields.io/github/v/release/MichaelZama/reels-killer?label=release)](https://github.com/MichaelZama/reels-killer/releases/latest)
![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
[![License GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)

**[⬇️ Download the latest version](https://github.com/MichaelZama/reels-killer/releases/latest)**

**English** · [Italiano](README.it.md)

</div>

---

## How it works

When you open short videos, a pause screen appears over the player. The rest of the app keeps working: messages, regular videos, search.

- **Exit** takes you out of short videos.
- **Continue** lets you watch for a few seconds. If you are still there, the screen comes back.

It is not a ban. Scrolling just gets a little less comfortable, enough to make you ask yourself whether you really feel like it.

## Features

| | |
|---|---|
| 🎯 **Targeted** | Only steps in on Instagram Reels, YouTube Shorts, TikTok and Facebook Reels. A reel someone sends you in a chat plays in full: the block kicks in at the first swipe. |
| ⏳ **Growing wait** | With each Continue in a row, the wait before you can continue gets longer: 2, 5, 10, 15, 20, 30 seconds. |
| 🪜 **Levels** | Each level (first block, after one Continue, after two…) has its own message and, if you like, its own image, GIF or video with sound. A level without media uses the previous one's. |
| 🔇 **Audio** | While paused, the video's audio is muted and restored afterwards. |
| 🔒 **Private** | No internet permission: no data leaves your phone. It does not read your messages or save what is on screen. |
| 🌙 **Calm** | Dark theme, no alarming colours, "Exit" always easier than "Continue". |
| 🌍 **Languages** | English and Italian, with an in-app switch. |

## Installation

1. Download the APK from the **[latest release](https://github.com/MichaelZama/reels-killer/releases/latest)** and open it. If asked, allow your browser to install apps.
2. Open Reels Killer and follow the three setup steps.
3. Turn on the Reels Killer accessibility service when the app asks you to.

<details>
<summary><b>The accessibility switch is greyed out ("Restricted setting")</b></summary>

<br>

Since Android 13 the system protects accessibility for apps installed outside the Play Store.

1. Tap the greyed-out Reels Killer entry in accessibility settings and dismiss the warning.
2. Open *Settings › Apps › Reels Killer*, tap **⋮** at the top right and choose **Allow restricted settings**.
3. Go back to accessibility settings and turn the service on.

</details>

<details>
<summary><b>Google Play Protect blocks the installation</b></summary>

<br>

Play Protect blocks downloaded apps that ask for accessibility. In the Play Store tap your profile › **Play Protect** › ⚙️, temporarily turn off **Scan apps with Play Protect**, install the APK, then turn it back on.

</details>

<details>
<summary><b>Xiaomi, Redmi, POCO, Samsung, Oppo, Vivo, Huawei</b></summary>

<br>

These phones close background apps aggressively. In the app info:

- enable **Autostart**;
- set battery to **No restrictions**;
- avoid **Force stop**, which disconnects the accessibility service.

If the service stops, the app's main screen says "Service stopped by the system" and offers a button to fix it.

</details>

> [!NOTE]
> On Android 17, *Advanced Protection* can prevent apps that are not accessibility tools from using this permission. If it is on, Reels Killer cannot work.

## Updates

Download the new APK from the [releases](https://github.com/MichaelZama/reels-killer/releases), then in Reels Killer open the **Update** section and tap **Install update from APK file**.

Installed this way, the update keeps your settings and permissions and does not ask you to allow restricted settings again.

## Permissions

| Permission | What it is for |
|---|---|
| Accessibility | Knowing which screen is open and showing the pause over the player. |
| Battery optimisation exemption | Keeping the system from stopping the service. |
| Notifications | The silent "Reels Killer is on" notification. |
| Install apps | Only for the "Install update" button, with a file you pick. |

Internet is not one of them.

## If blocking does not kick in

Apps change their interface often, and detection relies on rules in [`rules.json`](app/src/main/assets/rules.json).

1. In Reels Killer open **Diagnostics** and tap **Export tree in 10 s**.
2. Within 10 seconds open the screen that is not recognised and wait.
3. Go back to the app and tap **Share last tree**. The file only contains the structure of the screen: texts are replaced by their length.

That file is what is needed to update the rules. [Open an issue](https://github.com/MichaelZama/reels-killer/issues) and attach it.

## Building from source

Kotlin and Jetpack Compose, `minSdk` 26. Builds run on GitHub Actions: every push to `main` produces an APK among the artifacts, and every `v*` tag publishes a release.

Locally you need JDK 17 and the Android SDK:

```bash
./gradlew testDebugUnitTest assembleDebug
```

To sign releases, CI reads four repository secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`. Without them the APK is signed with a debug key.

## License

[GPL-3.0](LICENSE)
