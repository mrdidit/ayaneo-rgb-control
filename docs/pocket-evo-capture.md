# Pocket EVO passive UART capture

This procedure observes writes made by the already-running AYANEO GameWindow
process. It never opens `/dev/ttyHS4`, replays a command, or changes a packet.
Frida attachment is still process instrumentation: GameWindow may restart or
crash, so keep the stock RGB UI available and verify controller input afterward.

## Requirements

- a rooted Pocket EVO connected through ADB;
- matching host/device Frida versions;
- Python 3.10 or newer with the `frida` package;
- `tools/evo_uart_capture_agent.js` and `tools/capture_evo_uart.py`;
- the stock GameWindow process already running.

On the inspected rooted image, Magisk root is available as
`/debug_ramdisk/su`. The explicit path remains useful in recovery environments,
although the current boot also exposes `su` through `/product/bin/su`.

Use the official Frida Android ARM64 server matching the host package. Push it
to a temporary path, make it executable, and start it as root. Do not configure
it to start at boot.

## Pre-flight checks

```sh
adb shell getprop ro.product.device
adb shell pidof com.ayaneo.gamewindow
adb shell /debug_ramdisk/su -c 'lsof /dev/ttyHS4'
```

The device value must be exactly `PocketEVO`. Resolve the current GameWindow
PID each time; it changes after a restart. GameWindow may close the descriptor
while RGB is off, so an empty `lsof` result is not itself a failure; the capture
agent also observes the descriptor after GameWindow opens it later.

Discover the controller input node rather than assuming its event number:

```sh
adb shell getevent -pl
```

On the inspected unit, `AYANEO Controller` was `/dev/input/event6`, with:

- left stick: `ABS_X`, `ABS_Y`;
- right stick: `ABS_Z`, `ABS_RZ`.

## Capture

Start the UART capture:

```sh
python3 tools/capture_evo_uart.py \
  --out captures/evo-reactive.jsonl
```

In a second terminal, record controller events without grabbing the device:

```sh
adb shell getevent -lt /dev/input/event6 \
  > captures/evo-reactive-input.txt
```

Use the capture runner's marker input whenever the experiment changes phase.
Keep synchronized video showing both rings if possible.

## Bounded sequence

Use 60% brightness and hold each cardinal position for about two seconds.

1. Start both captures while the LEDs are off or in a recorded known state.
2. Select Static in stock GameWindow and mark `STATIC`.
3. Select Reactive with idle `0000FF` and highlight `FF0000`; mark
   `A_REACTIVE_IDLE`.
4. Without touching either stick, wait three seconds.
5. Mark and move the left stick: centre, up, right, down, left, centre.
6. Mark and repeat for the right stick.
7. Change idle only to `00FF00`; keep highlight `FF0000`; mark `B_IDLE_ONLY`
   and repeat both stick sequences.
8. Keep idle `00FF00`; change highlight only to `0000FF`; mark
   `C_HIGHLIGHT_ONLY` and repeat both sequences.
9. Disable the effect, mark `OFF`, wait three seconds, then stop both captures.
10. Verify both sticks and controller buttons still work.

The original red/blue sequence is the minimum useful run. The idle-only and
highlight-only repeats distinguish the two colour fields and expose cached or
restarted effects.

## Decode

```sh
python3 tools/evo_trace_decode.py decode captures/evo-reactive.jsonl
python3 tools/evo_trace_decode.py decode \
  --json captures/evo-reactive.jsonl > captures/evo-reactive-report.json
```

Compare two captures without replaying either one:

```sh
python3 tools/evo_trace_decode.py compare capture-a.jsonl capture-b.jsonl
```

Correlate UART frames with the device-monotonic timestamps from `getevent -lt`:

```sh
python3 tools/evo_trace_decode.py correlate \
  captures/evo-reactive.jsonl captures/evo-reactive-input.txt
```

The decoder retains all successful raw writes, not just known 27-byte Static
and 11-byte controller-follow frames. A direction interval with zero target
writes is meaningful only when the session is complete, the process did not
restart, and the capture has no sequence or truncation gaps.

For JSONL captures, the decoder also requires the ordered runner lifecycle
`host_started`, `process_selected`, `agent_started`, `host_stopping`, then an
`application-requested` detach. Missing terminal records, a crash, or an
unexpected detach marks overall coverage and the affected marker interval
incomplete. Plain hex input intentionally has no lifecycle requirement.

## Validated Pocket EVO result

The 2026-08-28 physical-device run completed with 293 successful hooked
GameWindow writes, 293 valid frames, and no gaps, errors, bad checksums, noise
bytes, or incomplete data. The first eight cardinal windows and both clean
left-stick repeats were UART-silent. A second colour state's movement windows
carried only shared-level ramps at byte 2; both RGB triplets remained fixed. A
third colour state's two stick sequences were UART-silent again.

No captured movement frame contained an explicit ring, direction, quadrant,
axis, or target value. This is strongly consistent with controller-local
spatial selection; it does not rule out an undocumented command or an
unobserved lower-level path outside the hooked GameWindow libc calls.

## Interpretation boundary

If each physical direction is visible in `getevent` but no new UART write is
captured in complete corresponding intervals, the defensible conclusion is
that direction tracking is probably MCU-managed. It does not prove that no
undocumented direct quadrant command exists.

If UART writes do change, diff one movement at a time. Do not assign spatial
names until every position on both rings is verified by physical observation.
Do not brute-force values that stock traffic did not establish.

Later offline firmware analysis and bounded physical testing recovered the
direct register-list path documented in
[Pocket EVO per-quadrant findings](pocket-evo-per-quadrant-findings.md). That
result does not invalidate this passive trace: the trace remains the observed
behavior of GameWindow's selected controller-follow path, while the recovered
command path was established separately without replaying or modifying the
captured session.
