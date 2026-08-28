# Changelog

All notable user-facing, protocol, safety, research, and validation changes are
recorded here.

## [0.5.1] - 2026-08-28

### Pocket EVO per-stick and quadrant control

- Added Pocket-EVO-only **Per stick** Static control with independently retained
  colour and brightness for the left and right stick rings.
- Added Pocket-EVO-only **Quadrants** control with independently retained colour
  and brightness for all four physical segments on both rings.
- Added the validated ring selectors: left `0x21`, right `0x20`, and broadcast
  `0x1C`.
- Added the validated clockwise physical mapping: index 0 is 270°/left, index 1
  is 0°/top, index 2 is 90°/right, and index 3 is 180°/bottom.
- Added pure builders for broadcast Static, same-colour controller-follow
  initialization, and target-specific per-zone register frames.
- Apply actions now use retained immutable snapshots, so changing UI state during
  a transaction cannot alter the frame sequence already in progress.
- Live preview is disabled for the advanced multi-frame modes; each full layout
  is sent only through its explicit Apply button.
- Ordinary Pocket EVO Static and animated effects remain with AYANEO GameWindow.
  Direct UART ownership is reserved for **Per stick** and **Quadrants**.

### UART ownership, root, and recovery safety

- Gated advanced Pocket EVO control to the physically tested device profile,
  GameWindow 1.5.66/code 186, and local controller marker `23`.
- Added a preflight requiring the active `ayaneo_rgb_uart` Magisk module, the
  Pocket EVO UART character device, and its expected
  `u:object_r:ayaneo_rgb_device:s0` SELinux label.
- Persisted ownership states (`stock`, `stopping`, `direct`, and `restoring`) so
  an interrupted process can recover instead of silently creating two UART
  owners.
- Serialized controller transactions across activity recreation and protected
  ownership-changing work from coroutine cancellation.
- Replaced the long-lived interactive root shell with isolated one-shot root
  commands and concurrent output draining.
- Added bounded root-command execution and actionable timeout/failure messages.
- Replaced the unbounded per-file-descriptor `/proc` scan with a four-second
  Toybox `lsof -t` check. Unexpected output or a scanner error fails closed.
- Require two consecutive clear GameWindow PID/UART-owner observations before
  the first direct frame is written.
- Added a verified **Return RGB control to AYANEO** handoff using a fresh service
  and binder, with process, UART, and controller-input checks before the recovery
  marker is cleared.
- Added startup recovery for interrupted handoffs and explicit error reporting
  for Apply, LED changes, startup recovery, and stock handoff.
- Prevented normal Pocket EVO Static from racing GameWindow through the advanced
  direct transaction path.

### Interface and usability

- Added retained selectors and colour indicators for both stick rings and all
  eight quadrant targets.
- Moved brightness control near the top of the editor so per-stick and quadrant
  tuning no longer requires repeated scrolling past presets and custom colours.
- On wide layouts, the selected-colour field now occupies three quarters of its
  row and the compact **Set theme** action occupies the remaining quarter.
- On narrow layouts, colour and theme controls fall back to full-width stacked
  rows rather than clipping.
- Made the live-preview/Apply controls responsive: portrait layouts place the
  full-width Apply button below the preview row, while wide layouts retain a
  single compact row.
- Prevented initial composition or activity recreation from writing hardware
  merely because live preview was saved as enabled.
- Added clearer direct-control, recovery, timeout, and stock-handoff status text.

### Protocol tests, research tools, and documentation

- Added seven JVM golden-vector tests covering ring selectors, physical zone
  indices, checksums, zero-sentinel padding, channel validation, and shell-safe
  unsigned-byte encoding.
- Added a passive Frida UART capture agent and attach-only host runner that do not
  open, replay, or write to the UART.
- Added an offline trace decoder with frame validation, reassembly, comparisons,
  marker intervals, controller-event correlation, lifecycle completeness checks,
  and machine-readable reports.
- Added 41 Python tests for capture parsing, frame families, checksums,
  reassembly, lifecycle coverage, marker coverage, and input correlation.
- Added the full passive-capture procedure and the Pocket EVO protocol/findings
  record, including evidence boundaries and recovery guidance.
- Corrected the mode-routing documentation: S2 Static is direct AR10, Pocket EVO
  Static uses GameWindow, and FIT Elite Static is direct KR02.

### Physical and automated validation

- A supervised 0.5.0/code 16 precursor build retained left red at 80% brightness
  and right blue at 100% brightness simultaneously on the connected Pocket EVO.
- The same supervised run placed all eight chosen quadrant colours at the
  documented physical locations on both rings.
- The physically validated direct ownership transaction completed with
  GameWindow stopped and no lingering UART owner after the writes.
- The 0.5.1/code 17 release keeps that validated protocol and ownership path and
  adds the responsive UI refinements described above.
- Android unit tests, lint, debug assembly, all 41 Python tests, and diff
  whitespace checks pass for this release.

### Requirements and boundaries

- Advanced per-stick/quadrant control requires root, the AYANEO RGB UART Magisk
  module, the exact validated Pocket EVO/GameWindow/controller markers, and an
  available `/dev/ttyHS4`.
- These selectors, registers, and frames are Pocket EVO/AR07-specific and must
  not be replayed on Pocket S2, Pocket FIT Elite, or an unknown model.
- No controller firmware was flashed, updated, erased, or placed into a
  bootloader during this work.
- A true spatial rainbow animation around each ring is not implemented.

## [0.4.0] - previous public release

- Added the Pocket FIT Elite device profile and KR02 RGB protocol support.
- Added FIT Elite Static, Single Breath, Rainbow, and LED-off control.
- Added model-aware Magisk UART setup for Pocket FIT Elite while retaining the
  existing Pocket S2 and Pocket EVO profiles.
