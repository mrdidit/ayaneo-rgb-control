# AYANEO RGB Control

An experimental Android controller for the analogue-stick RGB lighting on the
AYANEO Pocket S2 Pro.

The app provides:

- a honeycomb colour picker that consumes drag gestures without scrolling the page;
- corrected colour output for the Pocket S2's mixed-colour imbalance;
- built-in and eight persistent custom-colour presets;
- Static, Breath, and Rainbow Breath modes;
- persistent colour, brightness, mode, and LED on/off settings;
- direct UART output for Static mode;
- AYANEO GameWindow integration for the stock animated modes.

## Hardware support

This project currently targets the **AYANEO Pocket S2 Pro**, identified by the
stock software as the AR14 device family. Do not assume that its UART protocol,
device node, or colour correction applies to another AYANEO model.

Direct Static control uses `/dev/ttyHS5` at 115200 baud with the 27-byte
register packet captured from AYANEO GameWindow. The included Magisk module
assigns a dedicated SELinux label to that exact device node at boot.

## Requirements

- AYANEO Pocket S2 Pro
- rooted Android installation with Magisk
- AYANEO GameWindow installed for Breath and Rainbow Breath
- Android SDK and JDK 21 to build

Root is required because Android does not normally allow third-party apps to
write the AYANEO configuration files or controller UART.

## Build

Set `sdk.dir` in a local, untracked `local.properties`, then run:

```sh
./gradlew :app:assembleDebug
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## UART access module

The source for the narrowly scoped Magisk module is in
[`magisk-module`](magisk-module). Package it from the project directory:

```sh
zip -j ayaneo-rgb-uart-magisk.zip \
  magisk-module/module.prop \
  magisk-module/service.sh \
  magisk-module/sepolicy.rule \
  magisk-module/customize.sh
```

Install the ZIP through Magisk and reboot. The module:

- waits only for `/dev/ttyHS5`;
- labels that node `ayaneo_rgb_device`;
- restores ownership to `system:system` and mode `664`;
- grants access only to AYANEO's `system_app` domain and Magisk's root domain.

## Current mode routing

| Mode | Backend |
| --- | --- |
| Static | Direct `/dev/ttyHS5` UART packet |
| Breath | AYANEO GameWindow service |
| Rainbow Breath | AYANEO GameWindow service |

A true spatial rainbow that spins around each LED ring has not yet been
implemented.

## Safety

This is device-specific experimental software. The UART implementation sends
lighting commands only; it does not flash controller firmware. Even so, review
the Magisk module and source before installing, and keep a working recovery
path for rooted-device changes.
