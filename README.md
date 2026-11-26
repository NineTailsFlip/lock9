# Lock

The lock screen of NineTailsFlip's LineageOS for keypad flip phones, in the Nine look.

[![Build](https://github.com/NineTailsFlip/lock9/actions/workflows/build.yml/badge.svg)](https://github.com/NineTailsFlip/lock9/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/NineTailsFlip/lock9)](https://github.com/NineTailsFlip/lock9/releases/latest)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)

Lock is shown whenever the phone on the [thor2 image](https://github.com/kloploid/android_vendor_thor2)
(ALT AT-M140) locks: the time and the notifications at a glance, and the PIN typed straight on the
keypad. Part of [NineTailsFlip](https://github.com/NineTailsFlip).

## Features

- **At the top:** the time, date, next alarm, battery and "full in …" while charging, the
  carrier or "No SIM", the owner info.
- **Notifications**, newest first, following the lock screen settings: none, contents hidden,
  secret and silent ones left out; before the first unlock after a restart, the app only.
- **Music:** what's playing, play/pause, previous and next.
- **PIN** typed straight on the keypad: auto-confirm (when Android's is on), "wrong PIN", and the
  countdown after too many tries.
- **SIM PIN and PUK**, with the tries left.
- **Shortcuts:** flashlight, secure camera, emergency call; Back turns the screen off.
- Calls, alarms and the camera show over it; Home does nothing on it. Whatever would show the
  stock lock screen or its PIN prompt shows Lock instead: Home over the camera, an app over the
  lock screen asking to unlock (Lock says "unlock to continue", then the app carries on).

[`docs/FEATURES.md`](docs/FEATURES.md) maps every feature of the stock lock screen to Lock.

## Installation

Lock is part of the image and isn't installed on its own: the ROM build re-signs it with the
platform key and installs it as a privileged, persistent, direct-boot-aware app. It updates with
the image.

## Usage

| Key | Does |
|---|---|
| Digits | Type the PIN |
| OK | Unlock; on a notification, open it once unlocked; on the music, play/pause |
| Up / Down | Choose a notification |
| Left | Dismiss a notification; on the music, the previous track |
| Right | On the music, the next track |
| Back | Delete a digit; with nothing typed, turn the screen off |
| `*` | Flashlight, camera, emergency call, screen off |
| Call | Emergency call |

## How it works

The system keyguard stays underneath as the security boundary; Lock is an activity shown over it
(`showWhenLocked`), started as the keyguard locks. Lock checks the PIN through
`LockSettingsService`, as the stock bouncer does — the same throttling after wrong tries, the
same unlocking of encrypted storage after a restart — and then asks SystemUI to finish the
keyguard with a broadcast SystemUI only takes from a platform-signed app (android_vendor_thor2's
`frameworks_base` patch `0006`). A second patch (`0007`) keeps Home from leaving it, and a third
(`0009`) starts Lock wherever the stock keyguard or bouncer would come up, with
`org.thor2.keyguard.action.SHOW` - an intent only the system and SystemUI may send, as it needs
the signature-only `ACCESS_KEYGUARD_SECURE_STORAGE`.

If Lock isn't running, crashes, or the broadcast goes unanswered, the stock lock screen and
Android's own PIN prompt are what's left: the phone is never left unlocked.

## Building

Requirements: JDK 17+, Android SDK (platform 34).

```bash
git clone --recursive https://github.com/NineTailsFlip/lock9.git
cd lock9
./gradlew :app:testDebugUnitTest :app:assembleRelease
```

A tag `v<versionName>` builds and publishes a release (NineTailsFlip/.github's `android.yml`).

## Project structure

| Path | What's there |
|---|---|
| `app/src/main/java/io/github/ninetailsflip/lock/` | `LockApp` (watches the keyguard), `LockActivity` (the screen), `Unlocker` (PIN, SIM, unlocking), `Status` (the top lines), `Notifications` (notifications and music) |
| `docs/FEATURES.md` | The stock lock screen's features, and how Lock does each |
| `nine-core/` | Git submodule: [nine-core](https://github.com/NineTailsFlip/nine-core) |

## Limitations

- PIN only: with a pattern or password, Android's own prompt takes over.
- No status bar icons (battery and carrier are shown as text), no emergency information.

## Contributing

Issues and pull requests are welcome; see
[CONTRIBUTING](https://github.com/NineTailsFlip/.github/blob/main/CONTRIBUTING.md). Report
anything that could unlock the phone without its PIN privately, as
[SECURITY](https://github.com/NineTailsFlip/.github/blob/main/SECURITY.md) describes.

## License

GPL-3.0 — see [LICENSE](LICENSE), like the rest of the Nine apps.
