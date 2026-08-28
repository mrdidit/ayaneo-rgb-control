# Pocket EVO per-quadrant RGB findings

Scope: AYANEO Pocket EVO (`Build.DEVICE=PocketEVO`) with the AR07 controller
and local version marker `23`. Pocket S2/S2 Pro, Pocket FIT Elite, and unknown device
profiles were not probed, written, or changed during this work. Do not replay
these addresses or frames on another model.

## Status

Independent left/right ring control and four independently retained RGB zones
per ring are now recovered and physically validated. The same bounded tests
also established per-zone brightness and a common spatial mapping on both
rings:

- selector `0x21` addresses the physical left ring;
- selector `0x20` addresses the physical right ring;
- selector `0x1C` broadcasts to both rings;
- zone 0 is 270 degrees/left;
- zone 1 is 0 degrees/top;
- zone 2 is 90 degrees/right; and
- zone 3 is 180 degrees/bottom.

Angles use 0 degrees at the top and increase clockwise. Distinct colours were
retained on the two rings and in individual zones. The low-level protocol is
therefore no longer speculative. App version 0.5.1 exposes Pocket-EVO-only
per-stick and per-zone controls through an explicit, serialized
GameWindow-to-direct-UART handoff. Live preview remains disabled for these
multi-frame modes.

No controller firmware was flashed or updated. No bootloader-entry or DFU
command was sent. The official firmware payload was downloaded to the host and
examined offline, then only bounded lighting-register frames derived from that
firmware were tested.

## Active software and evidence artifacts

These device/software values and host evidence artifacts were recorded on
2026-08-28:

| Item | Validated value |
| --- | --- |
| Product device / model | `PocketEVO` / `Pocket EVO` |
| Android / ABI | Android 13 / `arm64-v8a` |
| RGB UART | `/dev/ttyHS4`, 115200 baud |
| Active GameWindow | `com.ayaneo.gamewindow` 1.5.66, version code 186 |
| Active GameWindow APK SHA-256 | `20fdc4eff9baa84f6c62081a44b71dc573e0aa8e99dab22ca770a3bbfc260b77` |
| Active AYANEO Settings | `com.ayaneo.settings` 1.1.94, version code 129 |
| Active Settings APK SHA-256 | `3921fb2e37fe6a6183c20ef52416c9ae61816e9e9e2fa13da60871920d545f0b` |
| Local controller version file | `/sdcard/.aya/aya_firmware_local_version.conf`, ASCII `23` |
| Official AR07 payload | `dfu_update_b.txt`, 69,616 bytes |
| Official payload SHA-256 | `b7412fc8e9b628fdcb1cdf0ae9e0c4b1395fab16d91ce788ed531eea97f4b047` |

The active packages are updated `/data/app` installations. The earlier
GameWindow 1.5.9/code 128 system APK remains useful as a historical baseline,
but it is not the version used for the final firmware and hardware validation.

AYANEO Settings identifies Pocket EVO as AR07 and selects the B firmware route.
The version metadata returned `23`, matching the local version file. The
local file is mutable app-maintained metadata, not cryptographic proof of the
running MCU image; the app therefore also pins the physically tested device and
GameWindow build, and the protocol conclusions rely on the bounded hardware
observations in this report.

The official global URLs used read-only were:

```text
https://ayaneo-1305909189.cos.accelerate.myqcloud.com/android/AR07/ControllerFirmwareUpdate/aya_android_handler_firmware_version_b.txt
https://ayaneo-1305909189.cos.accelerate.myqcloud.com/android/AR07/ControllerFirmwareUpdate/dfu_update_b.txt
```

Despite its `.txt` suffix, the second file is a raw Cortex-M firmware image.
It was fetched to the host for static analysis only. The Settings updater,
UART bootloader transition, and USB DFU transport were not invoked.

## Firmware analysis and recovered command path

Static analysis used firmware load base `0x08008000`. The relevant v23
functions are:

| Firmware address | Role |
| --- | --- |
| `0x0800EA6C` | UART ingress validation and fixed-frame dispatch |
| `0x08008AB8` | `F7` command handler and raw I2C register-list proxy |
| `0x0800B844` | I2C register writer reached by the proxy |
| `0x08009480` | controller-follow zone selection for both rings |
| `0x08015478` | internal `(zone, RGB, brightness)` LED setter |

For opcode `0x00`, the command handler treats the next byte as an I2C target
and the remaining payload as register/value pairs. The internal follow routine
sets target `0x21` or `0x20` and calls the four-zone setter separately for zone
indices 0 through 3. This supplied the bounded command candidates; physical
tests then established which target and zone correspond to each visible ring
position.

## Fixed 27-byte register frame

The normal UART ingress requires this fixed layout:

```text
F7 00 TT (REG VALUE) x 11 CC ED
```

| Offset | Meaning |
| --- | --- |
| 0 | `F7` start byte |
| 1 | `00`, raw register-list opcode |
| 2 | I2C target `TT`: `21` left, `20` right, or `1C` both |
| 3..24 | exactly eleven register/value slots |
| 25 | checksum `CC` |
| 26 | `ED` end byte |

Shorter raw frames are not accepted by the normal ingress. The inner register
loop stops at the first zero register, so unused slots must be padded with
`00 00` while retaining the full 27-byte frame.

GameWindow calculates `CC` as the low eight bits of the sum of offsets 1 through
24. The v23 ingress implementation omits offset 24 from its own sum; all known
compatible frames keep offset 24 at zero, so the two calculations agree. Keep
that final data byte zero.

The `TT` target byte is separate from register `0x58 = 0x02`, which is the
existing Pocket EVO LED-controller selector. They should not be conflated.

## Ring and zone register map

The physically mapped register banks are identical for both target addresses:

| Zone index | Physical position | Red | Green | Blue | Brightness |
| --- | --- | --- | --- | --- | --- |
| 0 | 270 degrees / left | `0x46` | `0x47` | `0x48` | `0x21` |
| 1 | 0 degrees / top | `0x49` | `0x4A` | `0x4B` | `0x22` |
| 2 | 90 degrees / right | `0x4C` | `0x4D` | `0x4E` | `0x23` |
| 3 | 180 degrees / bottom | `0x4F` | `0x50` | `0x51` | `0x24` |

The firmware setter selects the initialized AR07 brightness branch as
`0x21 + zoneIndex`. During physical validation, reducing the right ring's zone
3 register `0x24` from `0xFF` to `0x20` dimmed only the bottom segment. This
confirms the active branch used by the complete `0x21..0x24` mapping. An earlier
unobserved `0x30` experiment is not part of the mapping.

A validated per-zone packet uses the setter's register order, followed by zero
padding:

```text
F7 00 TT
20 01
58 02
8B 40
RZ RR
GZ GG
BZ BB
LZ LL
45 00
00 00
00 00
00 00
CC ED
```

Here `RZ/GZ/BZ/LZ` come from one row of the table, `RR/GG/BB` are the colour,
and `LL` is that zone's brightness. Register `0x45 = 0x00` commits the setter's
sequence. Selector-specific writes demonstrated independent left/right colours;
zone-bank writes demonstrated independently retained colours within each ring.

## Physical validation results

The bounded validation was performed with GameWindow stopped and the test
writer closed after each sequence. It demonstrated:

1. target `0x21` changes only the left ring and target `0x20` changes only the
   right ring;
2. target-specific whole-ring frames can retain different colours on the two
   rings at the same time;
3. each of the four RGB banks changes only its mapped physical segment;
4. the index-to-direction mapping is the same on both rings;
5. different zone colours remain visible together rather than collapsing to a
   shared colour; and
6. a zone-specific brightness write dims that segment without dimming the rest
   of its ring.

One isolated frame was visibly missed during testing. Three spaced, identical
frames applied reliably and the same three-repeat rule recovered both rings.
The ADB invocations were separated, but no minimum inter-frame delay was
measured; do not document or depend on an invented millisecond value.

## Supervised app validation

On 2026-08-28, AYANEO RGB Control 0.5.0/code 16 completed an end-to-end
supervised run on the validated Pocket EVO. **Per stick** retained left red at
80% brightness and right blue at 100% simultaneously. **Quadrants** then placed
all eight configured colours at the documented physical positions on both
rings. This separately validates the UI target indexing, saved apply snapshots,
frame builders, and direct ownership transaction; the earlier tests in this
document validated the raw protocol and physical register mapping.

## Clearing retained/stale zone state

Per-zone register writes intentionally leave unmentioned banks unchanged. A
same-colour controller-follow initializer was required to clear colours retained
from earlier tests before applying a new zone layout:

```text
F7 55 LL RR GG BB RR GG BB CC ED
```

The idle and highlight triplets are deliberately identical. `CC` is the low
eight bits of offsets 1 through 8. Send this exact initializer three spaced
times, then send the desired target/zone frame three spaced times. Using the
same colour in both follow fields prevents stick movement from reintroducing a
second colour while the firmware initializes all banks.

This initializer is part of reliable state setup, not proof that stock
GameWindow exposes direct zone controls. The subsequent selector and zone
register frames provide that control.

## Stock commands and the earlier passive trace

GameWindow 1.5.66 still constructs its whole-device Static packet with target
`0x1C`:

```text
F7 00 1C
20 01
80 00
81 00
8B 0F
88 RR
89 GG
8A BB
86 LL
87 LL
58 02
45 00
CC ED
```

Its controller-follow command remains:

```text
F7 55 LL IR IG IB HR HG HB CC ED
```

The earlier synchronized Frida/input trace correctly showed that stock
GameWindow traffic carries no explicit ring or zone target. Its complete
attached-process trace had 293 successful `/dev/ttyHS4` writes, no capture gaps,
290 valid follow frames, and three valid shutdown frames. That evidence only
described GameWindow's selected command path; it did not rule out the raw I2C
target path later recovered from official firmware.

For reproducibility, the final UART trace SHA-256 is
`3183fa0d60d649be7bcad8f0396c2f0821b31b42c2f734a0f4df094abc2f7ba5`,
and the synchronized input trace SHA-256 is
`131dbb41672e9cb9cd4b67990c07c48babc604609e37d9ec3942d7546466829d`.
The passive capture tools under `tools/` remain attach-only observers and do
not send any of the validation frames.

## GameWindow ownership and recovery

GameWindow normally keeps `/dev/ttyHS4` open and can overwrite a direct Static
layout or interleave bytes with another process. Do not let two writers own the
UART during an experiment.

A reliable bounded workflow is:

1. force-stop `com.ayaneo.gamewindow` and confirm that its `/dev/ttyHS4`
   descriptor is gone;
2. open the UART only in the bounded test process;
3. send the same-colour initializer three spaced times when a clean bank state
   is needed;
4. send each intended register frame three spaced times;
5. for recovery, send the exact known-good broadcast Static-red frame below
   three spaced times;
6. close the experimental writer completely; and
7. restart GameWindow's service and verify its process, UART descriptor, and
   controller input device.

Known-good broadcast recovery frame:

```text
F7 00 1C 20 01 80 00 81 00 8B 0F 88 FF 89 00 8A 00 86 FF 87 FF 58 02 45 00 1C ED
```

After closing the direct writer, the tested service restart was:

```sh
adb shell am startservice -n \
  com.ayaneo.gamewindow/com.ayaneo.gamewindow.utils.aidl.AyaAidlService
```

Confirm that `pidof com.ayaneo.gamewindow` returns a PID, that the restarted
process has exactly one `/dev/ttyHS4` descriptor, and that
`/dev/input/event6` is still present and reporting controller input. Root may be
required to inspect `/proc/<pid>/fd`. This process/PID/fd/input check was part
of the successful recovery; merely seeing the LEDs turn on is not sufficient.

If an experimental state is not visibly applied, do not expand into register
scanning. Reapply the same understood frame as three spaced repeats, recover
with the broadcast frame, close the writer, and restore GameWindow ownership.

## Safety boundary and app integration

- The findings and addresses are AR07 v23/Pocket EVO-specific. Existing S2,
  FIT Elite, and unknown-device behavior must remain unchanged.
- No firmware update, flash, erase, bootloader transition, selector scan, or
  register fuzzing occurred.
- Future controller firmware may change register behavior; retain a version
  gate and known-good recovery path.
- App version 0.5.1 pins the validated GameWindow build and controller marker,
  verifies the active Magisk module and UART SELinux label, stops GameWindow,
  requires two consecutive bounded no-owner checks, applies only the validated
  builders, and provides an explicit verified stock handoff. Interrupted
  ownership state is persisted for recovery. This boundary must remain intact.
- The three-repeat rule is an observation, not a measured transport timing
  specification.
- The Settings firmware URL was used for read-only provenance and offline
  analysis only. The application must not download or invoke controller updates
  as part of RGB control.
