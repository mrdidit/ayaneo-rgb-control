#!/usr/bin/env python3
"""Passively capture Pocket EVO GameWindow writes to /dev/ttyHS4 as JSONL."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import select
import signal
import sys
import threading
import time
from typing import Any
import uuid

try:
    import frida
except ImportError:  # Allows --help and static/unit checks without Frida installed.
    frida = None  # type: ignore[assignment]


SCHEMA = "ayaneo-uart-capture/v1"
GAMEWINDOW_PROCESS = "com.ayaneo.gamewindow"
TARGET_PATH = "/dev/ttyHS4"
DEFAULT_AGENT = Path(__file__).with_name("evo_uart_capture_agent.js")


class CaptureError(RuntimeError):
    """An expected capture setup/runtime error."""


class JsonlWriter:
    """Thread-safe, line-flushed JSONL output that never overwrites a capture."""

    def __init__(self, output_path: Path) -> None:
        output_path.parent.mkdir(parents=True, exist_ok=True)
        self.path = output_path
        self._file = output_path.open("x", encoding="utf-8", buffering=1)
        self._lock = threading.Lock()
        self._record_seq = 0
        self._closed = False

    def write(self, record: dict[str, Any]) -> None:
        with self._lock:
            if self._closed:
                return
            self._record_seq += 1
            enriched = dict(record)
            enriched.setdefault("schema", SCHEMA)
            enriched["record_seq"] = self._record_seq
            enriched["host_rx_unix_ns"] = str(time.time_ns())
            self._file.write(
                json.dumps(enriched, separators=(",", ":"), sort_keys=True) + "\n"
            )
            self._file.flush()

    def close(self) -> None:
        with self._lock:
            if self._closed:
                return
            self._file.flush()
            try:
                os.fsync(self._file.fileno())
            except OSError:
                pass
            self._file.close()
            self._closed = True

    def __enter__(self) -> "JsonlWriter":
        return self

    def __exit__(self, *_: object) -> None:
        self.close()


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Attach to the already-running AYANEO GameWindow process and passively "
            "capture libc write/writev traffic resolving exactly to /dev/ttyHS4. "
            "This tool never spawns the app or opens the UART."
        )
    )
    parser.add_argument(
        "--out",
        required=True,
        type=Path,
        help="New JSONL output path (an existing file is never overwritten)",
    )
    parser.add_argument(
        "--pid",
        type=int,
        help=(
            "Attach to this current PID after verifying its process name is exactly "
            f"{GAMEWINDOW_PROCESS}; otherwise the unique current process is selected"
        ),
    )
    parser.add_argument(
        "--agent",
        type=Path,
        default=DEFAULT_AGENT,
        help=argparse.SUPPRESS,
    )
    parser.add_argument(
        "--usb-timeout",
        type=float,
        default=10.0,
        help="Seconds to wait for a Frida USB device (default: 10)",
    )
    parser.add_argument(
        "--no-stdin",
        action="store_true",
        help="Disable interactive markers and wait until SIGINT/SIGTERM or detach",
    )
    return parser.parse_args(argv)


def host_record(
    kind: str,
    session_id: str,
    **fields: Any,
) -> dict[str, Any]:
    return {
        "schema": SCHEMA,
        "kind": kind,
        "session_id": session_id,
        "ts_unix_ms": time.time_ns() // 1_000_000,
        "process": GAMEWINDOW_PROCESS,
        **fields,
    }


def select_process(device: Any, requested_pid: int | None) -> Any:
    processes = list(device.enumerate_processes())
    if requested_pid is not None:
        matches = [process for process in processes if process.pid == requested_pid]
        if not matches:
            raise CaptureError(f"PID {requested_pid} is not currently running")
        process = matches[0]
        if process.name != GAMEWINDOW_PROCESS:
            raise CaptureError(
                f"Refusing PID {requested_pid}: process name is {process.name!r}, "
                f"not {GAMEWINDOW_PROCESS!r}"
            )
        return process

    matches = [process for process in processes if process.name == GAMEWINDOW_PROCESS]
    if not matches:
        raise CaptureError(
            f"{GAMEWINDOW_PROCESS} is not running; open GameWindow normally and retry "
            "(the capture tool will not spawn it)"
        )
    if len(matches) > 1:
        pids = ", ".join(str(process.pid) for process in matches)
        raise CaptureError(
            f"Multiple exact {GAMEWINDOW_PROCESS} processes are running ({pids}); "
            "select one explicitly with --pid"
        )
    return matches[0]


def load_agent_source(agent_path: Path, session_id: str) -> str:
    try:
        source = agent_path.read_text(encoding="utf-8")
    except OSError as error:
        raise CaptureError(f"Could not read Frida agent {agent_path}: {error}") from error
    config = {
        "session_id": session_id,
        "process_name": GAMEWINDOW_PROCESS,
        "target_path": TARGET_PATH,
    }
    return (
        "globalThis.__AYANEO_CAPTURE_CONFIG__ = Object.freeze("
        + json.dumps(config, separators=(",", ":"))
        + ");\n"
        + source
    )


def enrich_binary_payload(record: dict[str, Any], data: bytes | None) -> dict[str, Any]:
    enriched = dict(record)
    raw = b"" if data is None else bytes(data)
    if enriched.get("kind") != "write":
        if raw:
            enriched["unexpected_binary_hex"] = raw.hex()
        return enriched

    enriched["data_hex"] = raw.hex()
    declared = enriched.get("captured")
    if isinstance(declared, int) and declared != len(raw):
        enriched["host_capture_error"] = (
            f"agent declared {declared} captured bytes but supplied {len(raw)}"
        )

    vectors = enriched.get("iovecs")
    if isinstance(vectors, list):
        copied_vectors: list[Any] = []
        for vector in vectors:
            if not isinstance(vector, dict):
                copied_vectors.append(vector)
                continue
            copied = dict(vector)
            offset = copied.get("offset")
            captured = copied.get("captured")
            if isinstance(offset, int) and isinstance(captured, int):
                if offset < 0 or captured < 0 or offset + captured > len(raw):
                    copied["data_hex"] = ""
                    prior = enriched.get("host_capture_error")
                    problem = (
                        f"iov[{copied.get('index', '?')}] slice {offset}:{offset + captured} "
                        f"is outside {len(raw)} captured bytes"
                    )
                    enriched["host_capture_error"] = (
                        f"{prior}; {problem}" if prior else problem
                    )
                else:
                    copied["data_hex"] = raw[offset : offset + captured].hex()
            copied_vectors.append(copied)
        enriched["iovecs"] = copied_vectors
    return enriched


def run_capture(args: argparse.Namespace) -> int:
    if frida is None:
        raise CaptureError(
            "The Python 'frida' package is not installed; install matching host tools "
            "and frida-server versions first"
        )
    if args.pid is not None and args.pid <= 0:
        raise CaptureError("--pid must be a positive integer")
    if args.usb_timeout <= 0:
        raise CaptureError("--usb-timeout must be greater than zero")

    session_id = str(uuid.uuid4())
    stopped = threading.Event()
    detached = threading.Event()
    fatal = threading.Event()
    shutdown_requested = threading.Event()
    session: Any | None = None
    script: Any | None = None
    selected_process: Any | None = None

    with JsonlWriter(args.out) as writer:
        writer.write(
            host_record(
                "session",
                session_id,
                phase="host_started",
                target_path=TARGET_PATH,
                stream_id=f"uart:{TARGET_PATH}",
                requested_pid=args.pid,
                frida_version=getattr(frida, "__version__", "unknown"),
            )
        )

        def on_detached(reason: str, crash: Any = None) -> None:
            fields: dict[str, Any] = {
                "phase": "detached",
                "reason": str(reason),
                "pid": getattr(selected_process, "pid", None),
                "target_path": TARGET_PATH,
                "stream_id": f"uart:{TARGET_PATH}",
            }
            if crash is not None:
                fields["crash"] = str(crash)
            writer.write(host_record("session", session_id, **fields))
            if not shutdown_requested.is_set():
                fatal.set()
            detached.set()
            stopped.set()

        def on_message(message: dict[str, Any], data: bytes | None) -> None:
            message_type = message.get("type")
            if message_type == "send" and isinstance(message.get("payload"), dict):
                writer.write(enrich_binary_payload(message["payload"], data))
                return

            if message_type == "error":
                writer.write(
                    host_record(
                        "error",
                        session_id,
                        phase="agent_runtime",
                        description=message.get("description", "Frida agent error"),
                        stack=message.get("stack"),
                        file_name=message.get("fileName"),
                        line_number=message.get("lineNumber"),
                        column_number=message.get("columnNumber"),
                    )
                )
                fatal.set()
                stopped.set()
                return

            writer.write(
                host_record(
                    "error",
                    session_id,
                    phase="agent_message",
                    description="Unexpected Frida message",
                    message=message,
                    unexpected_binary_hex=(bytes(data).hex() if data else None),
                )
            )

        previous_handlers: dict[int, Any] = {}

        def request_stop(_signum: int, _frame: Any) -> None:
            stopped.set()

        try:
            for signum in (signal.SIGINT, signal.SIGTERM):
                previous_handlers[signum] = signal.getsignal(signum)
                signal.signal(signum, request_stop)

            try:
                device = frida.get_usb_device(timeout=args.usb_timeout)
            except Exception as error:
                raise CaptureError(f"Could not connect to a Frida USB device: {error}") from error

            selected_process = select_process(device, args.pid)
            writer.write(
                host_record(
                    "session",
                    session_id,
                    phase="process_selected",
                    pid=selected_process.pid,
                    process_name=selected_process.name,
                    target_path=TARGET_PATH,
                    stream_id=f"uart:{TARGET_PATH}",
                    attach_mode="existing-process-only",
                )
            )

            try:
                session = device.attach(selected_process.pid)
            except Exception as error:
                raise CaptureError(
                    f"Could not attach to existing {GAMEWINDOW_PROCESS} PID "
                    f"{selected_process.pid}: {error}"
                ) from error
            session.on("detached", on_detached)

            agent_source = load_agent_source(args.agent, session_id)
            try:
                script = session.create_script(agent_source)
                script.on("message", on_message)
                script.load()
                status = script.exports_sync.status()
            except Exception as error:
                raise CaptureError(f"Could not load/verify the Frida agent: {error}") from error

            if status.get("target_path") != TARGET_PATH:
                raise CaptureError(
                    f"Agent reported unexpected target {status.get('target_path')!r}"
                )
            print(
                f"Capturing {GAMEWINDOW_PROCESS} PID {selected_process.pid} "
                f"writes to {TARGET_PATH} -> {args.out}",
                file=sys.stderr,
                flush=True,
            )

            if args.no_stdin:
                while not stopped.wait(0.5):
                    pass
            else:
                print(
                    "Type a marker label and press Enter. Commands: :quit, :status, :help",
                    file=sys.stderr,
                    flush=True,
                )
                while not stopped.is_set():
                    try:
                        readable, _, _ = select.select([sys.stdin], [], [], 0.5)
                        if not readable:
                            continue
                        line = sys.stdin.readline()
                    except KeyboardInterrupt:
                        stopped.set()
                        break
                    if line == "":
                        stopped.set()
                        break
                    command = line.strip()
                    if not command:
                        continue
                    if command in {":quit", ":q"}:
                        stopped.set()
                        break
                    if command == ":help":
                        print(
                            "Enter any non-command line to timestamp it in the target; "
                            "use :quit to stop.",
                            file=sys.stderr,
                            flush=True,
                        )
                        continue
                    if command == ":status":
                        try:
                            print(
                                json.dumps(script.exports_sync.status(), sort_keys=True),
                                file=sys.stderr,
                                flush=True,
                            )
                        except Exception as error:
                            print(f"Status failed: {error}", file=sys.stderr, flush=True)
                        continue
                    try:
                        marker_seq = script.exports_sync.mark(command)
                        print(
                            f"Marker {marker_seq}: {command}",
                            file=sys.stderr,
                            flush=True,
                        )
                    except Exception as error:
                        writer.write(
                            host_record(
                                "error",
                                session_id,
                                phase="marker",
                                description=str(error),
                                label=command,
                            )
                        )
                        if detached.is_set():
                            fatal.set()
                            stopped.set()

        except (CaptureError, FileExistsError) as error:
            writer.write(
                host_record(
                    "error",
                    session_id,
                    phase="host_runtime",
                    description=str(error),
                    pid=getattr(selected_process, "pid", None),
                )
            )
            raise
        except Exception as error:
            raise CaptureError(f"Capture failed: {error}") from error
        finally:
            for signum, previous in previous_handlers.items():
                signal.signal(signum, previous)

            if script is not None:
                try:
                    script.unload()
                except Exception:
                    pass
            writer.write(
                host_record(
                    "session",
                    session_id,
                    phase="host_stopping",
                    pid=getattr(selected_process, "pid", None),
                    target_path=TARGET_PATH,
                    stream_id=f"uart:{TARGET_PATH}",
                )
            )
            if session is not None and not detached.is_set():
                shutdown_requested.set()
                try:
                    session.detach()
                except Exception:
                    pass
                detached.wait(1.0)

        return 1 if fatal.is_set() else 0


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        return run_capture(args)
    except FileExistsError:
        print(f"Capture output already exists: {args.out}", file=sys.stderr)
        return 2
    except CaptureError as error:
        print(f"Capture error: {error}", file=sys.stderr)
        return 1
    except OSError as error:
        print(f"Capture I/O error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
