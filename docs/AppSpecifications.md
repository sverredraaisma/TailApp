# Tail app
the tail app is a companion android application for the Tail firmware project. all the
documentation for the tail firmware can be found at "C:\Users\Sverr\CLionProjects\TailFirmware\docs"
The app works to configure, control and assist the tail. It does this with at least the following features:

## Features
- screen with nearby bluetooth devices
  - should prioritize any device with the name "Tail controller"
- user can click on a device to connect to it
- device screen shows general information about the device and the current configuration
  - shows the status of subsystems (servo's, bluetooth, leds, IMU's, I2C)
  - shows the currently selected movement profile
  - shows the current position of all the servo's
  - shows the currently selected lighting effect layers and combination methods
  - shows the total amount of Leds being driven
- device screen has a button in the top that toggles the FFT stream on or off to the device
- device screen has the following subscreens
  - led config
    - user can see and change current led matrix config
    - user can see the currently selected layers and
      - make changes to the values of parameters
      - change the combination method
      - remove the layer
      - add an extra layer
    - for the image effect all required functionality to upload an image must be available
  - motion config
    - see currently selected motion pattern and 
      - change the selected profile
      - change the parameters of the profile
    - see and change current settings for each servo
    - calibrate zero
  - Audio config (settings for how the FFT stream gets constructed)
    - settings for the amount of bins in the FFT
    - volume normalization speed settings (the app tries to normalize the gain of the mic for the FFT)
    - frequency range start and end for bins
  - BeatLight (the beat/drop/genre-reactive lighting feature)
    - analyses the phone's microphone and drives the device's LEDs in time with the music
    - streams the beat to the device in the FF05 trailer, so the device's own effects and motion can react too
  - Effect composer (reached from BeatLight's "Edit")
    - a layered, folder-nested effect graph of reactive effects; edits rebuild an immutable tree
    - installs a composition onto the device's own layer stack so a look survives the phone leaving, reporting per-layer what could not come along
  - Keyframe editor (motion config)
    - pose a timed sequence on the tail visualiser, scrub it, upload it to a device slot; plays as PATTERN_KEYFRAME
  - Behavior config (motion config)
    - edit the device's mood state machine and its triggers, force a state to preview, and read back the live state and why it last changed
  - Manual drive pad (motion config)
    - steer the tail directly (FF0B); it suspends the running pattern while held and hands back on release
  - Firmware update (device screen)
    - pick an image and stream it (FF0E) with progress; the rollback contract (stay connected ~10 s) is surfaced
  - Diagnostics (device screen)
    - heap, uptime, stall and overrun counts, sensor and driver health, decoded from FF0C
  - Device screen also carries the battery card (level + the low-battery policy in force), device rename, bond management, and the Device Information strings

To make these features refer to the documentation of the firmware.

The firmware is at **protocol v6**. The app targets that exact version; a device on
an older protocol is surfaced as an unsupported-version banner rather than parsed
best-effort.

The app must know the persisted enum names and properties (effect ids, blend
modes, pattern ids and their parameter ranges) because saved device config stores
ids, not names, and the app rebuilds its displays from them. As of firmware SYS-9
the device *also* publishes per-parameter descriptors (name/min/max/default/unit)
on FF0D, so an app can render usable controls for a pattern or effect it was not
built to know — the built-in tables stay the fast path, the descriptors the
forward-compatible fallback.