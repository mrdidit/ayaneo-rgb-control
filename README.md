# AYANEO RGB Control

An experimental Android controller for the analogue-stick RGB lighting on the
AYANEO Pocket S2 / S2 Pro and Pocket EVO.

The app provides:

- a honeycomb colour picker that consumes drag gestures without scrolling the page;
- optional corrected colour output for mixed-colour imbalance;
- built-in and eight persistent custom-colour presets;
- model-aware Static, Single Breath, RGB Breath, Rainbow, and Reactive modes;
- persistent colour, brightness, mode, and LED on/off settings;
- direct UART output for Static mode;
- AYANEO GameWindow integration for the stock animated modes.
- copyable diagnostics for identifying and researching unknown devices.

## Hardware support

The app selects a verified profile from Android's device identity:

| Device | Stock profile | RGB UART | Protocol selector |
| --- | --- | --- | --- |
| Pocket S2 / S2 Pro | AR10 | `/dev/ttyHS5` | `0x08` |
| Pocket EVO | AR07 | `/dev/ttyHS4` | `0x02` |

Direct Static control uses the selected UART at 115200 baud with a 27-byte
register packet captured from AYANEO GameWindow. Unknown devices enter safe
mode: configuration integration remains available, but direct UART output is
disabled until a verified profile is added.

The **Copy diagnostics** button creates a plain-text report containing the
Android hardware identity, selected safety profile, GameWindow version, recent
apply/IPC results, candidate UART metadata, and any UART descriptor currently
owned by GameWindow. The root probe is read-only and never sends a UART packet.
The report intentionally excludes the device serial number. It can identify a
likely UART and protocol family, but actual packet capture still requires a
separate controlled trace.

Unknown devices also receive an in-app **Help add this device** guide. It keeps
direct UART disabled and asks the tester to:

1. open the stock RGB screen on Static so GameWindow exposes its active UART;
2. copy the read-only diagnostics;
3. test fixed `#00FF00` green and `#0000FF` blue in the stock app;
4. copy a prepared observation template; and
5. send both items to the developer before any controlled packet trace.

The guide explicitly warns users not to experiment with UART nodes manually.

## Why colour correction is needed

The RGB numbers accepted by the controller do not always produce the colour
they normally represent on a screen. On the tested S2 and EVO units, pure red,
green, and blue were recognisable, but mixed colours containing red were
strongly skewed because the green and blue components visually overpowered
red. For example, requested orange could appear green, while cream or white
could appear cyan or teal.

This is a device-output calibration problem, not an error in the hex picker.
The exact cause has not been proven; possible contributors include unequal LED
channel brightness, diffuser/optical mixing, and calibration or colour-space
handling in the controller firmware.

The optional **Colour correction** switch applies the empirically tested
workaround when red is present:

- red is left unchanged;
- green is reduced to 20% of the requested value;
- blue is reduced to 35% of the requested value;
- pure green and pure blue are left unchanged.

This correction is deliberately simple, not a calibrated colour-management
profile. Results may vary between devices and LED batches, highly saturated
mixed colours may still differ from their on-screen preview, and animated
stock effects may perform additional processing. The switch can be disabled
to send the uncorrected RGB values.

## Requirements

- AYANEO Pocket S2 / S2 Pro or Pocket EVO
- rooted Android installation with Magisk
- AYANEO GameWindow installed for animated modes
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

The source for the device-aware Magisk module is in
[`magisk-module`](magisk-module). Package it from the project directory:

```sh
zip -j ayaneo-rgb-uart-magisk.zip \
  magisk-module/module.prop \
  magisk-module/service.sh \
  magisk-module/sepolicy.rule \
  magisk-module/customize.sh
```

Install the ZIP through Magisk and reboot. The module detects the model before
changing anything:

- Pocket S2 / S2 Pro: waits for `/dev/ttyHS5`;
- Pocket EVO: waits for `/dev/ttyHS4`;
- unknown devices: exits without touching any UART;
- labels that node `ayaneo_rgb_device`;
- restores ownership to `system:system` and mode `664`;
- grants access only to AYANEO's `system_app` domain and Magisk's root domain.

## Current mode routing

| Mode | S2 / S2 Pro | EVO | Backend |
| --- | --- | --- | --- |
| Static | Yes | Yes | Direct model-specific UART packet |
| Single Breath | Yes | Yes | AYANEO GameWindow service |
| RGB Breath | No | Yes | AYANEO GameWindow service |
| Rainbow | Yes | Yes | AYANEO GameWindow service |
| Reactive | No | Yes | AYANEO GameWindow service |

A true spatial rainbow that spins around each LED ring has not yet been
implemented.

## Future development

The current experimental build retains root and direct UART access because the
Android devices are still being used to verify controller packets, model
differences, and lighting behaviour. Removing that path now would make further
hardware investigation more difficult.

The planned normal Android backend is rootless:

1. request Android's **All files access** permission once;
2. write AYANEO's `.aya` configuration through shared storage;
3. ask GameWindow to apply the settings through its existing service;
4. keep direct UART access behind an optional research/developer setting;
5. remove the Magisk requirement from normal releases after the rootless path
   has passed the same mode, persistence, restart, and LED-off tests on every
   supported device.

Until then, the verified rooted implementation remains the stable test path.
Future device profiles will continue to default to safe mode: no direct UART
write is allowed until the device node and protocol selector have been
captured and tested on real hardware.

## Safety

This is device-specific experimental software. The UART implementation sends
lighting commands only; it does not flash controller firmware. Even so, review
the Magisk module and source before installing, and keep a working recovery
path for rooted-device changes.
