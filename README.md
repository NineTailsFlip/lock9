# Lock

The lock screen of [NineTailsFlip](https://github.com/NineTailsFlip)'s LineageOS build for keypad
flip phones (ALT AT-M140, thor2), in the look of the Nine apps: the time, date, next alarm,
battery and carrier at the top; the music and the notifications under them; the PIN typed
straight on the keypad. The SIM PIN and PUK, the flashlight, the secure camera and the
emergency call are there too.

[`docs/FEATURES.md`](docs/FEATURES.md) lists every feature of the stock lock screen and how Lock
does it.

## Keys

| Key | Does |
|---|---|
| Digits | Type the PIN (with Android's auto-confirm on, it unlocks on the last digit) |
| OK | Unlock; on a notification, open it once unlocked; on the music, play/pause |
| Up / Down | Choose a notification |
| Left | Dismiss a notification; on the music, the previous track |
| Right | On the music, the next track |
| Back | Delete a digit; with nothing typed, turn the screen off |
| `*` | Flashlight, camera, emergency call, screen off |
| Call | Emergency call |

## How it's safe

The system keyguard stays underneath as the security boundary: Lock is shown over it. It checks
the PIN through `LockSettingsService`, as the stock bouncer does (same throttling after wrong
tries, same unlocking of encrypted storage after a restart), then asks SystemUI to finish the
keyguard with a broadcast SystemUI only takes from a platform-signed app
([android_vendor_thor2](https://github.com/kloploid/android_vendor_thor2), `frameworks_base`
patch `0006`). If Lock isn't running, crashes, or the broadcast goes unanswered, the stock lock
screen and Android's own PIN prompt are what's left: the phone is never left unlocked.

## In the image

The ROM build re-signs it with the platform key and installs it as a privileged, persistent,
direct-boot-aware app; `ro.config.thor2_lockscreen` names it for the patch that keeps Home from
leaving the lock screen (`0007`). It isn't updated on its own.

## Building

```sh
git clone --recursive https://github.com/NineTailsFlip/lock9
./gradlew assembleRelease
```

## License

GPL-3.0, like the rest of the Nine suite.
