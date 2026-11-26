# The stock lock screen, and how Lock does each part

Every feature of the lock screen of LineageOS 23.2 (Android 16 SystemUI's keyguard and bouncer,
plus Lineage's additions), and where it stands in Lock.

- ✅ — in Lock
- ⚙ — still the system's, by design (see "How it works")
- — — not on thor2 (no touchscreen gestures, fingerprint, face or multiple users)
- ⏳ — not yet

## How it works

The system keyguard stays, underneath, as the security boundary: Lock is an activity shown over
it (`showWhenLocked`), started whenever the phone locks. Lock checks the PIN itself, through
`LockSettingsService` (`LockPatternUtils.checkCredential`) exactly as the stock bouncer does -
the same throttling, the same unlocking of the phone's encrypted storage on the first unlock
after a restart - then asks SystemUI to finish the keyguard (`org.thor2.keyguard.action.UNLOCK`,
a broadcast SystemUI only takes from an app holding the signature-only
`ACCESS_KEYGUARD_SECURE_STORAGE`: android_vendor_thor2's `frameworks_base` patch `0006`).

If Lock isn't there, crashes or is stopped, the phone shows the stock keyguard: nothing is ever
left unlocked. If SystemUI doesn't take the broadcast (a build without the patch), Lock falls
back to Android's own PIN prompt.

## Showing

| Stock | Lock |
|---|---|
| Shown whenever the phone locks (screen off after the lock delay, power key, restart) | ✅ — started as the keyguard locks (`KeyguardManager` locked-state listener), so it's there when the screen comes on |
| Before the first unlock after a restart (Direct Boot) | ✅ — Lock is direct-boot aware and keeps its own settings in device-protected storage |
| Home does nothing on the lock screen | ✅ — `frameworks_base` patch `0007` |
| Calls, alarms, the camera and the emergency dialer show over it | ✅ — they're shown-when-locked activities above Lock |
| Home over one of them (the camera) returns to the lock screen, then home once unlocked | ✅ — patch `0009` starts Lock, which goes home after the unlock |
| An app over the lock screen asks for the unlock (the camera's gallery) | ✅ — patch `0009`: Lock takes the PIN ("unlock to continue") in place of the bouncer, and the app carries on once unlocked |
| Uncovered when what was over it goes | ✅ — patch `0009` brings Lock back rather than the stock keyguard |
| Wallpaper | — Nine screens are black |
| Lock screen rotation, AOD / ambient display, double-tap to wake, lift to wake | — |

## What it shows

| Stock | Lock |
|---|---|
| Clock (12/24-hour as set), date | ✅ |
| Next alarm | ✅ |
| Battery level, charging, "full in …" | ✅ |
| Carrier, "No SIM", "Emergency calls only", airplane mode | ✅ |
| Owner info / lock screen message | ✅ |
| Notifications, newest first | ✅ |
| "Notifications on lock screen" off: none shown | ✅ |
| Sensitive contents hidden when the setting says so, or when the app marks a notification private | ✅ — app and "contents hidden" |
| Secret notifications (`VISIBILITY_SECRET`) left out | ✅ |
| Silent notifications hidden on the lock screen (setting) | ✅ |
| Before the first unlock: no notification contents | ✅ — count only |
| Media controls (title, artist, play/pause, previous/next) | ✅ |
| Lineage: media metadata/album art on the lock screen | — (text only) |
| Status bar icons (signal, Wi-Fi, …) | ⏳ — battery and carrier are shown as text |
| Weather / "At a glance" smartspace | — (none in this build) |

## Unlocking

| Stock | Lock |
|---|---|
| No lock ("None"/swipe): one key unlocks | ✅ — OK |
| PIN, typed on the keypad | ✅ — digits type straight away, no field to focus |
| Auto-confirm ("quick unlock"): unlock as soon as the PIN is complete, no OK needed | ✅ — when Android's own auto-confirm is on |
| Wrong PIN: "Wrong PIN", attempt counting | ✅ |
| Too many attempts: "Try again in N seconds" countdown (throttling) | ✅ — from `LockSettingsService` |
| Failed attempts counted for a device admin's wipe policy | ✅ — reported as the bouncer does |
| Pattern / password unlock | ⏳ — PIN only (Settings sets PINs); with another lock type Lock hands over to Android's prompt |
| Fingerprint, face, Smart Lock / trust agents | — / ⚙ (trust agents keep working: if the phone is trusted, OK unlocks) |
| Lockdown (power menu): only the PIN unlocks | ✅ — same `checkCredential` path |
| Unlocking opens the notification chosen first | ✅ |
| Lineage: scramble the PIN pad | — (hardware keypad) |

## SIM

| Stock | Lock |
|---|---|
| SIM PIN on the lock screen | ✅ |
| SIM PUK and a new PIN | ✅ |
| Attempts left | ✅ |

## Shortcuts

| Stock | Lock |
|---|---|
| Emergency call | ✅ — `*` menu, and the Call key |
| Flashlight | ✅ — `*` menu |
| Camera (secure, no access to the gallery) | ✅ — `*` menu |
| Emergency information | ⏳ |
| Turn the screen off | ✅ — Back (with nothing typed) |
| Quick settings from the lock screen | — (the shade, in Settings, opens only unlocked; the flashlight is in `*`) |
| Lineage: lock screen targets/shortcuts, visualizer, blur | — |
