#!/usr/bin/env python3
"""Offline decoder/comparator for AYANEO Pocket EVO UART captures.

The tool is intentionally read-only: it reads capture files and writes reports to
stdout.  It never opens a serial device, invokes adb, or constructs replay
commands. It recognizes the observed 11-byte controller-follow family and the
27-byte register family without assigning spatial semantics to either.
"""

from __future__ import annotations

import argparse
import bisect
from collections import Counter
from dataclasses import dataclass, field
import json
from pathlib import Path
import re
import sys
from typing import Any, Iterable, Optional


CAPTURE_SCHEMA = "ayaneo-uart-capture/v1"
REPORT_SCHEMA = "ayaneo-evo-trace-report/v1"
COMPARE_SCHEMA = "ayaneo-evo-trace-comparison/v1"
CORRELATION_SCHEMA = "ayaneo-evo-input-correlation/v1"
DEFAULT_UART_PATH = "/dev/ttyHS4"
FOLLOW_FRAME_LENGTH = 11
REGISTER_FRAME_LENGTH = 27
# Retained as the public name for the original register-frame size.
FRAME_LENGTH = REGISTER_FRAME_LENGTH

REGISTER_ID_OFFSETS = tuple(range(3, 24, 2))
REGISTER_VALUE_OFFSETS = tuple(range(4, 25, 2))
KNOWN_REGISTER_IDS = (
    0x20,
    0x80,
    0x81,
    0x8B,
    0x88,
    0x89,
    0x8A,
    0x86,
    0x87,
    0x58,
    0x45,
)

# These names describe only fields established by the current direct-Static
# constructor.  In particular, the 0x8B, 0x86, and 0x87 values deliberately do
# not receive spatial, side, or channel semantics.
KNOWN_FIELD_LABELS = {
    0: "frame_prefix",
    1: "header_1",
    2: "header_2",
    3: "reg_20_id",
    4: "reg_20_value",
    5: "reg_80_id",
    6: "reg_80_value",
    7: "reg_81_id",
    8: "reg_81_value",
    9: "reg_8b_id",
    10: "reg_8b_value",
    11: "reg_88_id",
    12: "red_value",
    13: "reg_89_id",
    14: "green_value",
    15: "reg_8a_id",
    16: "blue_value",
    17: "reg_86_id",
    18: "reg_86_value",
    19: "reg_87_id",
    20: "reg_87_value",
    21: "reg_58_id",
    22: "protocol_selector",
    23: "reg_45_id",
    24: "reg_45_value",
    25: "checksum",
    26: "frame_suffix",
}

CURRENT_STATIC_FIXED_VALUES = {
    0: 0xF7,
    1: 0x00,
    2: 0x1C,
    4: 0x01,
    6: 0x00,
    8: 0x00,
    10: 0x0F,
    22: 0x02,
    24: 0x00,
    26: 0xED,
}

FOLLOW_FIELD_LABELS = {
    0: "frame_prefix",
    1: "message_type_55",
    2: "level_value",
    3: "idle_background_red_value",
    4: "idle_background_green_value",
    5: "idle_background_blue_value",
    6: "highlight_active_red_value",
    7: "highlight_active_green_value",
    8: "highlight_active_blue_value",
    9: "checksum",
    10: "frame_suffix",
}

_COMPACT_HEX_RE = re.compile(r"(?:[0-9A-Fa-f]{2})+")
_HEX_TOKEN_RE = re.compile(r"(?:0[xX])?([0-9A-Fa-f]{2})")
_DECIMAL_RE = re.compile(r"-?[0-9]+")
_GETEVENT_RE = re.compile(
    r"^\s*\[\s*(?P<seconds>[0-9]+)\.(?P<fraction>[0-9]+)\]\s+"
    r"(?:(?P<device>\S+):\s+)?"
    r"(?P<event_type>\S+)\s+(?P<code>\S+)\s+(?P<value>\S+)"
)


def hex_bytes(data: bytes) -> str:
    return data.hex()


def display_hex(data: bytes) -> str:
    return " ".join(f"{value:02X}" for value in data)


def byte_hex(value: int) -> str:
    return f"{value:02X}"


@dataclass
class Issue:
    severity: str
    code: str
    message: str
    line: Optional[int] = None
    seq: Optional[int] = None
    session_id: Optional[str] = None

    def to_dict(self) -> dict[str, Any]:
        result: dict[str, Any] = {
            "severity": self.severity,
            "code": self.code,
            "message": self.message,
        }
        if self.line is not None:
            result["line"] = self.line
        if self.seq is not None:
            result["seq"] = self.seq
        if self.session_id is not None:
            result["session_id"] = self.session_id
        return result


@dataclass
class GapEvent:
    order: int
    session_id: str
    reason: str

    def to_dict(self) -> dict[str, Any]:
        return {
            "order": self.order,
            "session_id": self.session_id,
            "reason": self.reason,
        }


@dataclass
class LifecycleEvent:
    phase: str
    order: int
    line: int
    reason: Any = None
    crash: Any = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "phase": self.phase,
            "order": self.order,
            "line": self.line,
            "reason": self.reason,
            "crash": self.crash,
        }


@dataclass
class LifecycleStatus:
    session_id: str
    events: list[LifecycleEvent] = field(default_factory=list)
    complete: bool = False
    reasons: list[str] = field(default_factory=list)
    session_wide_reasons: list[str] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return {
            "session_id": self.session_id,
            "complete": self.complete,
            "reasons": self.reasons,
            "session_wide_reasons": self.session_wide_reasons,
            "events": [event.to_dict() for event in self.events],
        }


@dataclass
class Marker:
    order: int
    line: int
    session_id: str
    label: str
    seq: Optional[int] = None
    ts_unix_ms: Any = None
    ts_mono_ns: Any = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "order": self.order,
            "line": self.line,
            "session_id": self.session_id,
            "label": self.label,
            "seq": self.seq,
            "ts_unix_ms": self.ts_unix_ms,
            "ts_mono_ns": self.ts_mono_ns,
        }


@dataclass
class GeteventEvent:
    line: int
    timestamp_ns: int
    device: Optional[str]
    event_type: str
    code: str
    value: str
    raw: str

    def to_dict(self) -> dict[str, Any]:
        return {
            "line": self.line,
            "timestamp_ns": str(self.timestamp_ns),
            "device": self.device,
            "event_type": self.event_type,
            "code": self.code,
            "value": self.value,
            "raw": self.raw,
        }


@dataclass
class RawWrite:
    index: int
    order: int
    line: int
    session_id: str
    stream_id: str
    writer_id: str
    marker: Optional[str]
    seq: Optional[int]
    ts_unix_ms: Any
    ts_mono_ns: Any
    pid: Optional[int]
    tid: Optional[int]
    fd: Optional[int]
    fd_path: str
    op: str
    requested: int
    captured: int
    truncated: bool
    data: bytes
    result: int
    errno: Any
    committed: Optional[bytes]
    break_before: bool = False
    break_after: bool = False

    @property
    def successful(self) -> bool:
        return self.result > 0

    @property
    def capture_complete(self) -> bool:
        return self.result <= 0 or self.committed is not None

    def to_dict(self) -> dict[str, Any]:
        return {
            "index": self.index,
            "order": self.order,
            "line": self.line,
            "session_id": self.session_id,
            "stream_id": self.stream_id,
            "writer_id": self.writer_id,
            "marker": self.marker,
            "seq": self.seq,
            "ts_unix_ms": self.ts_unix_ms,
            "ts_mono_ns": self.ts_mono_ns,
            "pid": self.pid,
            "tid": self.tid,
            "fd": self.fd,
            "fd_path": self.fd_path,
            "op": self.op,
            "requested": self.requested,
            "captured": self.captured,
            "truncated": self.truncated,
            "data_hex": hex_bytes(self.data),
            "result": self.result,
            "errno": self.errno,
            "committed_hex": (
                hex_bytes(self.committed) if self.committed is not None else None
            ),
            "capture_complete": self.capture_complete,
            "break_before": self.break_before,
            "break_after": self.break_after,
        }


@dataclass
class Capture:
    name: str
    input_format: str
    target_path: str
    record_count: int = 0
    foreign_write_events: int = 0
    writes: list[RawWrite] = field(default_factory=list)
    markers: list[Marker] = field(default_factory=list)
    issues: list[Issue] = field(default_factory=list)
    gaps: list[GapEvent] = field(default_factory=list)
    lifecycles: dict[str, LifecycleStatus] = field(default_factory=dict)


@dataclass
class Frame:
    index: int
    data: bytes
    session_id: str
    stream_id: str
    source_write_indices: list[int]
    first_order: int
    last_order: int
    first_seq: Optional[int]
    last_seq: Optional[int]
    first_ts_mono_ns: Any
    last_ts_mono_ns: Any
    marker: Optional[str]

    @property
    def frame_type(self) -> str:
        if (
            len(self.data) == FOLLOW_FRAME_LENGTH
            and self.data[0] == 0xF7
            and self.data[1] == 0x55
            and self.data[10] == 0xED
        ):
            return "controller_follow_11"
        if len(self.data) == REGISTER_FRAME_LENGTH:
            return "register_27"
        return f"unknown_{len(self.data)}"

    @property
    def is_follow_frame(self) -> bool:
        return self.frame_type == "controller_follow_11"

    @property
    def checksum_offset(self) -> int:
        if self.is_follow_frame:
            return 9
        if len(self.data) == REGISTER_FRAME_LENGTH:
            return 25
        raise ValueError(f"no checksum rule for {len(self.data)}-byte frame")

    @property
    def checksum_input_slice(self) -> slice:
        if self.is_follow_frame:
            return slice(1, 9)
        if len(self.data) == REGISTER_FRAME_LENGTH:
            return slice(1, 25)
        raise ValueError(f"no checksum rule for {len(self.data)}-byte frame")

    @property
    def checksum_rule(self) -> str:
        if self.is_follow_frame:
            return "sum(bytes[1:9]) & 0xff"
        return "sum(bytes[1:25]) & 0xff"

    @property
    def expected_checksum(self) -> int:
        return sum(self.data[self.checksum_input_slice]) & 0xFF

    @property
    def observed_checksum(self) -> int:
        return self.data[self.checksum_offset]

    @property
    def checksum_valid(self) -> bool:
        return self.expected_checksum == self.observed_checksum

    @property
    def known_register_layout(self) -> bool:
        return len(self.data) == REGISTER_FRAME_LENGTH and tuple(
            self.data[offset] for offset in REGISTER_ID_OFFSETS
        ) == KNOWN_REGISTER_IDS

    @property
    def classification(self) -> str:
        if self.is_follow_frame:
            return "controller_follow_11_byte_frame"
        if not self.known_register_layout:
            return "unknown_27_byte_frame"
        fixed_match = all(
            self.data[offset] == value
            for offset, value in CURRENT_STATIC_FIXED_VALUES.items()
        )
        if fixed_match and self.data[18] == self.data[20]:
            return "matches_current_evo_static_template"
        return "evo_register_layout_variant"

    def field_labels(self) -> dict[int, str]:
        if self.is_follow_frame:
            return dict(FOLLOW_FIELD_LABELS)
        if self.known_register_layout:
            return dict(KNOWN_FIELD_LABELS)
        return {
            offset: generic_field_label(offset)
            for offset in range(len(self.data))
        }

    def to_dict(self) -> dict[str, Any]:
        labels = self.field_labels()
        pairs = []
        if len(self.data) == REGISTER_FRAME_LENGTH:
            for pair_index, (id_offset, value_offset) in enumerate(
                zip(REGISTER_ID_OFFSETS, REGISTER_VALUE_OFFSETS)
            ):
                pairs.append(
                    {
                        "pair_index": pair_index,
                        "id_offset": id_offset,
                        "value_offset": value_offset,
                        "register_id": self.data[id_offset],
                        "value": self.data[value_offset],
                    }
                )
        return {
            "index": self.index,
            "frame_type": self.frame_type,
            "length": len(self.data),
            "session_id": self.session_id,
            "stream_id": self.stream_id,
            "source_write_indices": self.source_write_indices,
            "first_order": self.first_order,
            "last_order": self.last_order,
            "first_seq": self.first_seq,
            "last_seq": self.last_seq,
            "first_ts_mono_ns": self.first_ts_mono_ns,
            "last_ts_mono_ns": self.last_ts_mono_ns,
            "marker": self.marker,
            "data_hex": hex_bytes(self.data),
            "classification": self.classification,
            "known_register_layout": self.known_register_layout,
            "checksum": {
                "rule": self.checksum_rule,
                "observed": self.observed_checksum,
                "expected": self.expected_checksum,
                "valid": self.checksum_valid,
            },
            "field_labels": {
                str(offset): labels[offset] for offset in range(len(self.data))
            },
            "fields": {
                labels[offset]: self.data[offset] for offset in range(len(self.data))
            },
            "register_pairs": pairs,
        }


@dataclass
class DecodeResult:
    capture: Capture
    frames: list[Frame]
    issues: list[Issue]
    noise_bytes: int
    incomplete_bytes: int
    bad_suffix_candidates: int
    bad_follow_suffix_candidates: int
    bad_register_suffix_candidates: int

    def summary(self) -> dict[str, Any]:
        writes = self.capture.writes
        complete_successes = [
            write for write in writes if write.result > 0 and write.committed is not None
        ]
        committed_bytes = sum(len(write.committed or b"") for write in writes)
        parse_errors = sum(issue.severity == "error" for issue in self.issues)
        lifecycle_required = self.capture.input_format == "jsonl"
        lifecycle_complete = (
            not lifecycle_required
            or (
                bool(self.capture.lifecycles)
                and all(
                    status.complete for status in self.capture.lifecycles.values()
                )
            )
        )
        return {
            "records": self.capture.record_count,
            "target_write_events": len(writes),
            "foreign_write_events": self.capture.foreign_write_events,
            "successful_write_events": sum(write.result > 0 for write in writes),
            "complete_successful_writes": len(complete_successes),
            "failed_write_events": sum(write.result < 0 for write in writes),
            "zero_write_events": sum(write.result == 0 for write in writes),
            "partial_write_events": sum(
                0 <= write.result < write.requested for write in writes
            ),
            "committed_bytes_captured": committed_bytes,
            "unique_complete_writes": len(
                {write.committed for write in complete_successes}
            ),
            "capture_gaps": len(self.capture.gaps),
            "lifecycle_required": lifecycle_required,
            "lifecycle_complete": lifecycle_complete,
            "lifecycle_sessions": len(self.capture.lifecycles),
            "coverage_complete": (
                len(self.capture.gaps) == 0
                and parse_errors == 0
                and lifecycle_complete
            ),
            "frames_total": len(self.frames),
            "frames_11_byte_follow": sum(
                frame.is_follow_frame for frame in self.frames
            ),
            "frames_27_byte": sum(
                len(frame.data) == REGISTER_FRAME_LENGTH for frame in self.frames
            ),
            "checksum_invalid_frames": sum(
                not frame.checksum_valid for frame in self.frames
            ),
            "noise_bytes": self.noise_bytes,
            "incomplete_bytes": self.incomplete_bytes,
            "bad_suffix_candidates": self.bad_suffix_candidates,
            "bad_follow_suffix_candidates": self.bad_follow_suffix_candidates,
            "bad_register_suffix_candidates": self.bad_register_suffix_candidates,
            "errors": parse_errors,
            "warnings": sum(issue.severity == "warning" for issue in self.issues),
        }


@dataclass
class _StreamBuffer:
    data: bytearray = field(default_factory=bytearray)
    origins: list[int] = field(default_factory=list)


def generic_field_label(offset: int) -> str:
    if offset == 0:
        return "frame_prefix"
    if offset in (1, 2):
        return f"header_{offset}"
    if 3 <= offset <= 24:
        pair_index = (offset - 3) // 2
        suffix = "register_id" if offset % 2 == 1 else "register_value"
        return f"pair_{pair_index}_{suffix}"
    if offset == 25:
        return "checksum"
    if offset == 26:
        return "frame_suffix"
    raise ValueError(f"offset outside 27-byte frame: {offset}")


def parse_compact_hex(value: Any, *, field_name: str = "data_hex") -> bytes:
    if not isinstance(value, str):
        raise ValueError(f"{field_name} must be a string")
    if value == "":
        return b""
    if not _COMPACT_HEX_RE.fullmatch(value):
        raise ValueError(f"{field_name} must contain an even number of hexadecimal digits")
    return bytes.fromhex(value)


def parse_hex_line(value: str) -> bytes:
    text = value.split("#", 1)[0].strip()
    if not text:
        return b""
    if _COMPACT_HEX_RE.fullmatch(text):
        return bytes.fromhex(text)

    if ",," in text or text.startswith(",") or text.endswith(","):
        raise ValueError("empty hexadecimal byte token")
    tokens = [token for token in re.split(r"[\s,]+", text) if token]
    if not tokens:
        return b""
    output = bytearray()
    for token in tokens:
        match = _HEX_TOKEN_RE.fullmatch(token)
        if match is None:
            raise ValueError(f"invalid hexadecimal byte token {token!r}")
        output.append(int(match.group(1), 16))
    return bytes(output)


def _integer(value: Any, field_name: str, *, optional: bool = False) -> Optional[int]:
    if value is None and optional:
        return None
    if isinstance(value, bool):
        raise ValueError(f"{field_name} must be an integer")
    if isinstance(value, int):
        return value
    if isinstance(value, str) and _DECIMAL_RE.fullmatch(value):
        return int(value)
    raise ValueError(f"{field_name} must be an integer")


def _record_payload(record: Any) -> dict[str, Any]:
    if not isinstance(record, dict):
        raise ValueError("JSONL record must be an object")
    if record.get("type") == "send" and isinstance(record.get("payload"), dict):
        return record["payload"]
    return record


def detect_input_format(text: str) -> str:
    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        return "jsonl" if line.startswith("{") else "hex"
    return "hex"


def parse_getevent_text(text: str) -> tuple[list[GeteventEvent], int]:
    """Parse timestamped getevent output without interpreting event codes."""

    events: list[GeteventEvent] = []
    ignored_lines = 0
    for line_number, raw_line in enumerate(text.splitlines(), start=1):
        if not raw_line.strip():
            continue
        match = _GETEVENT_RE.match(raw_line)
        if match is None:
            ignored_lines += 1
            continue
        fraction = match.group("fraction")
        timestamp_ns = (
            int(match.group("seconds")) * 1_000_000_000
            + int((fraction[:9]).ljust(9, "0"))
        )
        events.append(
            GeteventEvent(
                line=line_number,
                timestamp_ns=timestamp_ns,
                device=match.group("device"),
                event_type=match.group("event_type"),
                code=match.group("code"),
                value=match.group("value"),
                raw=raw_line.rstrip("\r\n"),
            )
        )
    return events, ignored_lines


def parse_capture_text(
    text: str,
    *,
    name: str = "<memory>",
    input_format: str = "auto",
    target_path: str = DEFAULT_UART_PATH,
) -> Capture:
    selected_format = detect_input_format(text) if input_format == "auto" else input_format
    if selected_format not in {"jsonl", "hex"}:
        raise ValueError(f"unsupported input format {selected_format!r}")
    capture = Capture(name=name, input_format=selected_format, target_path=target_path)
    if selected_format == "hex":
        _parse_hex_capture(text, capture)
    else:
        _parse_jsonl_capture(text, capture)
    return capture


def _parse_hex_capture(text: str, capture: Capture) -> None:
    session_id = "hex-session"
    stream_id = f"uart:{capture.target_path}"
    order = 0
    pending_break = False
    for line_number, raw_line in enumerate(text.splitlines(), start=1):
        stripped = raw_line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        order += 1
        capture.record_count += 1
        try:
            data = parse_hex_line(raw_line)
        except ValueError as exc:
            capture.issues.append(
                Issue("error", "invalid_hex", str(exc), line=line_number, session_id=session_id)
            )
            capture.gaps.append(GapEvent(order, session_id, "invalid_hex"))
            pending_break = True
            continue
        if not data:
            continue
        write = RawWrite(
            index=len(capture.writes),
            order=order,
            line=line_number,
            session_id=session_id,
            stream_id=stream_id,
            writer_id="hex-input",
            marker=None,
            seq=order,
            ts_unix_ms=None,
            ts_mono_ns=None,
            pid=None,
            tid=None,
            fd=None,
            fd_path=capture.target_path,
            op="hex-line",
            requested=len(data),
            captured=len(data),
            truncated=False,
            data=data,
            result=len(data),
            errno=None,
            committed=data,
            break_before=pending_break,
        )
        pending_break = False
        capture.writes.append(write)


_RUNNER_LIFECYCLE_PHASES = (
    "host_started",
    "process_selected",
    "agent_started",
    "host_stopping",
    "detached",
)


def _validate_jsonl_lifecycles(
    capture: Capture,
    *,
    last_order_by_session: dict[str, int],
    final_order: int,
) -> None:
    if not capture.lifecycles:
        capture.lifecycles["session-0"] = LifecycleStatus("session-0")
        last_order_by_session.setdefault("session-0", final_order)

    def invalidate(
        status: LifecycleStatus,
        *,
        code: str,
        message: str,
        order: int,
        line: Optional[int] = None,
        session_wide: bool = False,
    ) -> None:
        reason = f"{code}: {message}"
        if reason in status.reasons:
            return
        status.reasons.append(reason)
        if session_wide:
            status.session_wide_reasons.append(reason)
        capture.issues.append(
            Issue(
                "warning",
                code,
                message,
                line=line,
                session_id=status.session_id,
            )
        )
        capture.gaps.append(GapEvent(order, status.session_id, reason))

    for session_id, status in capture.lifecycles.items():
        by_phase: dict[str, list[LifecycleEvent]] = {}
        for event in status.events:
            by_phase.setdefault(event.phase, []).append(event)
        first_order = min(
            (event.order for event in status.events),
            default=last_order_by_session.get(session_id, final_order),
        )
        eof_order = final_order + 1

        for phase in _RUNNER_LIFECYCLE_PHASES:
            events = by_phase.get(phase, [])
            if not events:
                terminal = phase in {"host_stopping", "detached"}
                invalidate(
                    status,
                    code="lifecycle_missing_phase",
                    message=f"session is missing runner phase {phase!r}",
                    order=eof_order if terminal else first_order,
                    session_wide=not terminal,
                )
            elif len(events) > 1:
                invalidate(
                    status,
                    code="lifecycle_duplicate_phase",
                    message=f"session contains {len(events)} {phase!r} records",
                    order=events[1].order,
                    line=events[1].line,
                    session_wide=True,
                )

        if all(by_phase.get(phase) for phase in _RUNNER_LIFECYCLE_PHASES):
            ordered_events = [by_phase[phase][0] for phase in _RUNNER_LIFECYCLE_PHASES]
            if any(
                before.order >= after.order
                for before, after in zip(ordered_events, ordered_events[1:])
            ):
                invalidate(
                    status,
                    code="lifecycle_phase_order",
                    message=(
                        "runner phases are not ordered host_started, process_selected, "
                        "agent_started, host_stopping, detached"
                    ),
                    order=ordered_events[-1].order,
                    line=ordered_events[-1].line,
                    session_wide=True,
                )

        detach_events = by_phase.get("detached", [])
        stop_events = by_phase.get("host_stopping", [])
        if detach_events:
            detach = detach_events[0]
            prior_stop = any(stop.order < detach.order for stop in stop_events)
            if not prior_stop:
                invalidate(
                    status,
                    code="lifecycle_detach_without_host_stopping",
                    message="detach occurred without a preceding host_stopping record",
                    order=detach.order,
                    line=detach.line,
                )
            if detach.reason != "application-requested" or detach.crash not in {
                None,
                "",
            }:
                details = f"reason={detach.reason!r}"
                if detach.crash not in {None, ""}:
                    details += f", crash={detach.crash!r}"
                invalidate(
                    status,
                    code="lifecycle_unexpected_detach",
                    message=f"capture detached unexpectedly ({details})",
                    order=detach.order,
                    line=detach.line,
                )
            last_order = last_order_by_session.get(session_id, detach.order)
            if last_order > detach.order:
                invalidate(
                    status,
                    code="lifecycle_records_after_detach",
                    message="session contains records after its detach record",
                    order=detach.order,
                    line=detach.line,
                    session_wide=True,
                )

        status.complete = not status.reasons


def _parse_jsonl_capture(text: str, capture: Capture) -> None:
    current_session = "session-0"
    current_marker: dict[str, Optional[str]] = {current_session: None}
    last_seq: dict[str, int] = {}
    last_record_seq: Optional[int] = None
    pending_break: set[str] = set()
    last_order_by_session: dict[str, int] = {}
    order = 0

    def add_gap(session_id: str, reason: str) -> None:
        capture.gaps.append(GapEvent(order, session_id, reason))
        pending_break.add(session_id)

    for line_number, raw_line in enumerate(text.splitlines(), start=1):
        stripped = raw_line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        order += 1
        capture.record_count += 1
        try:
            record = _record_payload(json.loads(stripped))
        except (json.JSONDecodeError, ValueError) as exc:
            capture.issues.append(
                Issue("error", "invalid_jsonl", str(exc), line=line_number, session_id=current_session)
            )
            add_gap(current_session, "invalid_jsonl")
            continue

        schema = record.get("schema")
        if schema != CAPTURE_SCHEMA:
            capture.issues.append(
                Issue(
                    "error",
                    "unsupported_schema",
                    f"expected {CAPTURE_SCHEMA!r}, found {schema!r}",
                    line=line_number,
                    session_id=current_session,
                )
            )
            add_gap(current_session, "unsupported_schema")
            continue

        kind = str(record.get("kind", ""))
        if kind == "session":
            session_value = record.get("session_id") or record.get("id")
            if session_value is not None:
                current_session = str(session_value)
                current_marker.setdefault(current_session, None)

        session_id = str(record.get("session_id") or current_session)
        status = capture.lifecycles.setdefault(
            session_id, LifecycleStatus(session_id=session_id)
        )
        last_order_by_session[session_id] = order
        if kind == "session":
            phase = str(record.get("phase") or "")
            if phase:
                status.events.append(
                    LifecycleEvent(
                        phase=phase,
                        order=order,
                        line=line_number,
                        reason=record.get("reason"),
                        crash=record.get("crash"),
                    )
                )
        try:
            record_seq = _integer(record.get("record_seq"), "record_seq", optional=True)
        except ValueError as exc:
            record_seq = None
            capture.issues.append(
                Issue(
                    "error",
                    "invalid_record_seq",
                    str(exc),
                    line=line_number,
                    session_id=session_id,
                )
            )
            add_gap(session_id, "invalid_record_seq")
        if record_seq is not None:
            if last_record_seq is not None and record_seq != last_record_seq + 1:
                code = (
                    "record_sequence_reordered"
                    if record_seq <= last_record_seq
                    else "record_sequence_gap"
                )
                capture.issues.append(
                    Issue(
                        "warning",
                        code,
                        f"record_seq changed from {last_record_seq} to {record_seq}",
                        line=line_number,
                        session_id=session_id,
                    )
                )
                add_gap(session_id, code)
            last_record_seq = record_seq

        seq: Optional[int]
        try:
            seq = _integer(record.get("seq"), "seq", optional=True)
        except ValueError as exc:
            seq = None
            capture.issues.append(
                Issue("error", "invalid_seq", str(exc), line=line_number, session_id=session_id)
            )
            add_gap(session_id, "invalid_seq")

        if seq is not None:
            previous = last_seq.get(session_id)
            if previous is not None and seq != previous + 1:
                code = "sequence_reordered" if seq <= previous else "sequence_gap"
                capture.issues.append(
                    Issue(
                        "warning",
                        code,
                        f"sequence changed from {previous} to {seq}",
                        line=line_number,
                        seq=seq,
                        session_id=session_id,
                    )
                )
                add_gap(session_id, code)
            last_seq[session_id] = seq

        if kind == "error":
            description = record.get("description") or record.get("message") or "capture error"
            capture.issues.append(
                Issue(
                    "error",
                    "capture_error_record",
                    str(description),
                    line=line_number,
                    seq=seq,
                    session_id=session_id,
                )
            )
            add_gap(session_id, "capture_error_record")
            continue

        if kind == "session":
            action = str(record.get("action") or record.get("event") or "")
            if action.lower() in {"start", "started", "begin"}:
                current_marker[session_id] = None
            continue

        if kind == "marker":
            label = record.get("label") or record.get("name") or record.get("message")
            if label is None:
                label = f"marker-{len(capture.markers) + 1}"
            marker = Marker(
                order=order,
                line=line_number,
                session_id=session_id,
                label=str(label),
                seq=seq,
                ts_unix_ms=record.get("ts_unix_ms"),
                ts_mono_ns=record.get("ts_mono_ns"),
            )
            capture.markers.append(marker)
            current_marker[session_id] = marker.label
            continue

        if kind != "write":
            continue

        fd_path = str(record.get("fd_path") or "")
        if fd_path != capture.target_path:
            capture.foreign_write_events += 1
            continue

        prior_break = session_id in pending_break
        pending_break.discard(session_id)
        try:
            op = str(record.get("op"))
            if op not in {"write", "writev"}:
                raise ValueError("op must be 'write' or 'writev'")
            requested = _integer(record.get("requested"), "requested")
            captured = _integer(record.get("captured"), "captured")
            result = _integer(record.get("result"), "result")
            assert requested is not None and captured is not None and result is not None
            if requested < 0 or captured < 0:
                raise ValueError("requested and captured must be non-negative")
            data = parse_compact_hex(record.get("data_hex"))
            if captured != len(data):
                raise ValueError(
                    f"captured={captured} but data_hex contains {len(data)} bytes"
                )
            if captured > requested:
                raise ValueError("captured cannot exceed requested")
            if result > requested:
                raise ValueError("result cannot exceed requested")
            iovecs = record.get("iovecs")
            if op == "writev" and iovecs is not None:
                if not isinstance(iovecs, list):
                    raise ValueError("iovecs must be an array")
                iovec_data = bytearray()
                for index, iovec in enumerate(iovecs):
                    if not isinstance(iovec, dict):
                        raise ValueError(f"iovecs[{index}] must be an object")
                    iovec_data.extend(
                        parse_compact_hex(
                            iovec.get("data_hex"), field_name=f"iovecs[{index}].data_hex"
                        )
                    )
                if bytes(iovec_data) != data:
                    raise ValueError("concatenated iovecs do not match data_hex")
            pid = _integer(record.get("pid"), "pid", optional=True)
            tid = _integer(record.get("tid"), "tid", optional=True)
            fd = _integer(record.get("fd"), "fd", optional=True)
        except (ValueError, AssertionError) as exc:
            capture.issues.append(
                Issue(
                    "error",
                    "invalid_write_record",
                    str(exc),
                    line=line_number,
                    seq=seq,
                    session_id=session_id,
                )
            )
            add_gap(session_id, "invalid_write_record")
            continue

        capture_complete = result <= 0 or result <= len(data)
        committed = data[:result] if result > 0 and capture_complete else (b"" if result <= 0 else None)
        break_for_record = prior_break or not capture_complete
        if not capture_complete:
            capture.issues.append(
                Issue(
                    "warning",
                    "uncaptured_committed_bytes",
                    f"write returned {result}, but only {len(data)} bytes were captured",
                    line=line_number,
                    seq=seq,
                    session_id=session_id,
                )
            )
            add_gap(session_id, "uncaptured_committed_bytes")

        pid_text = str(pid) if pid is not None else "unknown-pid"
        fd_text = str(fd) if fd is not None else "unknown-fd"
        stream_id = str(record.get("stream_id") or f"uart:{fd_path}")
        writer_id = str(record.get("writer_id") or f"{pid_text}:{fd_text}")
        write = RawWrite(
            index=len(capture.writes),
            order=order,
            line=line_number,
            session_id=session_id,
            stream_id=stream_id,
            writer_id=writer_id,
            marker=current_marker.get(session_id),
            seq=seq,
            ts_unix_ms=record.get("ts_unix_ms"),
            ts_mono_ns=record.get("ts_mono_ns"),
            pid=pid,
            tid=tid,
            fd=fd,
            fd_path=fd_path,
            op=op,
            requested=requested,
            captured=captured,
            truncated=bool(record.get("truncated", False)),
            data=data,
            result=result,
            errno=record.get("errno"),
            committed=committed,
            break_before=break_for_record,
            break_after=not capture_complete,
        )
        capture.writes.append(write)

    _validate_jsonl_lifecycles(
        capture,
        last_order_by_session=last_order_by_session,
        final_order=order,
    )


def decode_capture(capture: Capture) -> DecodeResult:
    issues = list(capture.issues)
    frames: list[Frame] = []
    buffers: dict[tuple[str, str], _StreamBuffer] = {}
    writes_by_index = {write.index: write for write in capture.writes}
    noise_bytes = 0
    incomplete_bytes = 0
    bad_suffix_candidates = 0
    bad_follow_suffix_candidates = 0
    bad_register_suffix_candidates = 0

    def flush_session(session_id: str, reason: str) -> None:
        nonlocal incomplete_bytes
        for key in [key for key in buffers if key[0] == session_id]:
            pending = buffers.pop(key)
            if pending.data:
                incomplete_bytes += len(pending.data)
                issues.append(
                    Issue(
                        "warning",
                        "incomplete_frame_reset",
                        f"discarded {len(pending.data)} pending bytes before {reason}",
                        session_id=session_id,
                    )
                )

    def discard_noise(buffer: _StreamBuffer, count: int) -> None:
        nonlocal noise_bytes
        if count <= 0:
            return
        noise_bytes += count
        del buffer.data[:count]
        del buffer.origins[:count]

    for write in capture.writes:
        if write.break_before:
            flush_session(write.session_id, "capture gap")
        if write.committed:
            key = (write.session_id, write.stream_id)
            buffer = buffers.setdefault(key, _StreamBuffer())
            buffer.data.extend(write.committed)
            buffer.origins.extend([write.index] * len(write.committed))

            while buffer.data:
                try:
                    prefix_offset = buffer.data.index(0xF7)
                except ValueError:
                    discard_noise(buffer, len(buffer.data))
                    break
                if prefix_offset:
                    discard_noise(buffer, prefix_offset)
                if len(buffer.data) < 2:
                    break
                is_follow_candidate = buffer.data[1] == 0x55
                candidate_length = (
                    FOLLOW_FRAME_LENGTH
                    if is_follow_candidate
                    else REGISTER_FRAME_LENGTH
                )
                if len(buffer.data) < candidate_length:
                    break
                if buffer.data[candidate_length - 1] != 0xED:
                    bad_suffix_candidates += 1
                    if is_follow_candidate:
                        bad_follow_suffix_candidates += 1
                    else:
                        bad_register_suffix_candidates += 1
                    origin = writes_by_index[buffer.origins[0]]
                    issues.append(
                        Issue(
                            "warning",
                            (
                                "bad_follow_frame_suffix"
                                if is_follow_candidate
                                else "bad_register_frame_suffix"
                            ),
                            (
                                f"{candidate_length}-byte candidate beginning with F7 "
                                "did not end with ED"
                            ),
                            line=origin.line,
                            seq=origin.seq,
                            session_id=origin.session_id,
                        )
                    )
                    discard_noise(buffer, 1)
                    continue

                frame_data = bytes(buffer.data[:candidate_length])
                origin_indices = list(
                    dict.fromkeys(buffer.origins[:candidate_length])
                )
                origins = [writes_by_index[index] for index in origin_indices]
                markers = list(dict.fromkeys(origin.marker for origin in origins))
                if len(markers) > 1:
                    issues.append(
                        Issue(
                            "warning",
                            "frame_crossed_marker",
                            "one reassembled frame crossed a marker boundary",
                            line=origins[0].line,
                            seq=origins[0].seq,
                            session_id=origins[0].session_id,
                        )
                    )
                frame = Frame(
                    index=len(frames),
                    data=frame_data,
                    session_id=write.session_id,
                    stream_id=write.stream_id,
                    source_write_indices=origin_indices,
                    first_order=origins[0].order,
                    last_order=origins[-1].order,
                    first_seq=origins[0].seq,
                    last_seq=origins[-1].seq,
                    first_ts_mono_ns=origins[0].ts_mono_ns,
                    last_ts_mono_ns=origins[-1].ts_mono_ns,
                    marker=markers[0] if len(markers) == 1 else None,
                )
                frames.append(frame)
                if not frame.checksum_valid:
                    issues.append(
                        Issue(
                            "warning",
                            "checksum_mismatch",
                            (
                                f"frame {frame.index} checksum is "
                                f"{frame.observed_checksum:02X}; expected "
                                f"{frame.expected_checksum:02X}"
                            ),
                            line=origins[0].line,
                            seq=origins[0].seq,
                            session_id=origins[0].session_id,
                        )
                    )
                del buffer.data[:candidate_length]
                del buffer.origins[:candidate_length]
        if write.break_after:
            flush_session(write.session_id, "uncaptured committed bytes")

    for (session_id, _stream_id), buffer in buffers.items():
        if buffer.data:
            incomplete_bytes += len(buffer.data)
            origin = writes_by_index[buffer.origins[0]]
            issues.append(
                Issue(
                    "warning",
                    "incomplete_frame_tail",
                    f"capture ended with {len(buffer.data)} pending bytes",
                    line=origin.line,
                    seq=origin.seq,
                    session_id=session_id,
                )
            )

    return DecodeResult(
        capture=capture,
        frames=frames,
        issues=issues,
        noise_bytes=noise_bytes,
        incomplete_bytes=incomplete_bytes,
        bad_suffix_candidates=bad_suffix_candidates,
        bad_follow_suffix_candidates=bad_follow_suffix_candidates,
        bad_register_suffix_candidates=bad_register_suffix_candidates,
    )


def decode_text(
    text: str,
    *,
    name: str = "<memory>",
    input_format: str = "auto",
    target_path: str = DEFAULT_UART_PATH,
) -> DecodeResult:
    return decode_capture(
        parse_capture_text(
            text,
            name=name,
            input_format=input_format,
            target_path=target_path,
        )
    )


def diff_frame_bytes(before: Frame, after: Frame) -> dict[str, Any]:
    shared_known_layout = before.known_register_layout and after.known_register_layout
    shared_follow_layout = before.is_follow_frame and after.is_follow_frame
    same_frame_type = before.frame_type == after.frame_type
    changes = []
    for offset in range(max(len(before.data), len(after.data))):
        old_value = before.data[offset] if offset < len(before.data) else None
        new_value = after.data[offset] if offset < len(after.data) else None
        if old_value == new_value:
            continue
        if shared_known_layout:
            label = KNOWN_FIELD_LABELS[offset]
        elif shared_follow_layout:
            label = FOLLOW_FIELD_LABELS[offset]
        else:
            label = f"byte_{offset}"
        is_derived = (
            same_frame_type
            and old_value is not None
            and new_value is not None
            and offset == before.checksum_offset
        )
        change: dict[str, Any] = {
            "offset": offset,
            "label": label,
            "before": old_value,
            "after": new_value,
            "category": "derived" if is_derived else "payload",
        }
        if shared_known_layout and offset in REGISTER_VALUE_OFFSETS:
            pair_index = REGISTER_VALUE_OFFSETS.index(offset)
            change["register_id"] = KNOWN_REGISTER_IDS[pair_index]
        changes.append(change)
    return {
        "before_frame": before.index,
        "after_frame": after.index,
        "frame_type_before": before.frame_type,
        "frame_type_after": after.frame_type,
        "layout_comparable": shared_known_layout or shared_follow_layout,
        "payload_changed_offsets": [
            change["offset"] for change in changes if change["category"] == "payload"
        ],
        "derived_changed_offsets": [
            change["offset"] for change in changes if change["category"] == "derived"
        ],
        "changes": changes,
    }


def adjacent_diffs(result: DecodeResult) -> list[dict[str, Any]]:
    diffs: list[dict[str, Any]] = []
    previous_by_stream: dict[tuple[str, str], Frame] = {}
    for frame in result.frames:
        key = (frame.session_id, frame.stream_id)
        previous = previous_by_stream.get(key)
        if previous is not None:
            diffs.append(diff_frame_bytes(previous, frame))
        previous_by_stream[key] = frame
    return diffs


def frame_offset_summaries(frames: Iterable[Frame]) -> list[dict[str, Any]]:
    grouped: dict[str, list[Frame]] = {}
    for frame in frames:
        grouped.setdefault(frame.frame_type, []).append(frame)

    summaries: list[dict[str, Any]] = []
    for frame_type in sorted(grouped):
        group = grouped[frame_type]
        representative = group[0]
        labels = representative.field_labels()
        offsets = []
        for offset in range(len(representative.data)):
            counts = Counter(frame.data[offset] for frame in group)
            is_derived = offset == representative.checksum_offset
            offsets.append(
                {
                    "offset": offset,
                    "label": labels[offset],
                    "category": "derived" if is_derived else "payload",
                    "changed": len(counts) > 1,
                    "unique_values": sorted(counts),
                    "value_counts": [
                        {"value": value, "count": counts[value]}
                        for value in sorted(counts)
                    ],
                }
            )
        summaries.append(
            {
                "frame_type": frame_type,
                "length": len(representative.data),
                "frame_count": len(group),
                "unique_frames": len({frame.data for frame in group}),
                "changing_payload_offsets": [
                    item["offset"]
                    for item in offsets
                    if item["changed"] and item["category"] == "payload"
                ],
                "changing_derived_offsets": [
                    item["offset"]
                    for item in offsets
                    if item["changed"] and item["category"] == "derived"
                ],
                "offsets": offsets,
            }
        )
    return summaries


def marker_intervals(result: DecodeResult) -> list[dict[str, Any]]:
    intervals: list[dict[str, Any]] = []
    markers_by_session: dict[str, list[Marker]] = {}
    for marker in result.capture.markers:
        markers_by_session.setdefault(marker.session_id, []).append(marker)

    for session_id, markers in markers_by_session.items():
        markers.sort(key=lambda marker: marker.order)
        for index, marker in enumerate(markers):
            next_order = markers[index + 1].order if index + 1 < len(markers) else None
            writes = [
                write
                for write in result.capture.writes
                if write.session_id == session_id
                and write.order > marker.order
                and (next_order is None or write.order < next_order)
            ]
            frames = [
                frame
                for frame in result.frames
                if frame.session_id == session_id
                and frame.first_order > marker.order
                and (next_order is None or frame.first_order < next_order)
            ]
            gaps = [
                gap
                for gap in result.capture.gaps
                if gap.session_id == session_id
                and gap.order > marker.order
                and (next_order is None or gap.order <= next_order)
            ]
            lifecycle = result.capture.lifecycles.get(session_id)
            lifecycle_reasons = (
                lifecycle.session_wide_reasons if lifecycle is not None else []
            )
            gap_reasons = list(
                dict.fromkeys(
                    [*lifecycle_reasons, *(gap.reason for gap in gaps)]
                )
            )
            changed_offsets: set[int] = set()
            for before, after in zip(frames, frames[1:]):
                if before.frame_type != after.frame_type:
                    continue
                changed_offsets.update(
                    change["offset"]
                    for change in diff_frame_bytes(before, after)["changes"]
                    if change["category"] == "payload"
                )
            complete_payloads = [
                write.committed
                for write in writes
                if write.result > 0 and write.committed is not None
            ]
            offset_summaries = frame_offset_summaries(frames)
            intervals.append(
                {
                    "session_id": session_id,
                    "label": marker.label,
                    "start_order": marker.order,
                    "end_order": next_order,
                    "coverage_complete": not gap_reasons,
                    "gap_reasons": gap_reasons,
                    "write_events": len(writes),
                    "successful_complete_writes": len(complete_payloads),
                    "unique_complete_writes": len(set(complete_payloads)),
                    "frames_total": len(frames),
                    "frames_11_byte_follow": sum(
                        frame.is_follow_frame for frame in frames
                    ),
                    "frames_27_byte": sum(
                        len(frame.data) == REGISTER_FRAME_LENGTH for frame in frames
                    ),
                    "unique_frames": len({frame.data for frame in frames}),
                    "payload_changed_offsets": sorted(changed_offsets),
                    "frame_offset_summaries": offset_summaries,
                }
            )
    return intervals


def result_to_dict(result: DecodeResult) -> dict[str, Any]:
    return {
        "schema": REPORT_SCHEMA,
        "input": {
            "name": result.capture.name,
            "format": result.capture.input_format,
            "target_path": result.capture.target_path,
        },
        "summary": result.summary(),
        "issues": [issue.to_dict() for issue in result.issues],
        "gaps": [gap.to_dict() for gap in result.capture.gaps],
        "lifecycles": [
            lifecycle.to_dict()
            for lifecycle in result.capture.lifecycles.values()
        ],
        "markers": [marker.to_dict() for marker in result.capture.markers],
        "marker_intervals": marker_intervals(result),
        "raw_writes": [write.to_dict() for write in result.capture.writes],
        "frames": [frame.to_dict() for frame in result.frames],
        "frame_offset_summaries": frame_offset_summaries(result.frames),
        "adjacent_diffs": adjacent_diffs(result),
    }


def correlate_getevent(
    result: DecodeResult,
    getevent_text: str,
    *,
    getevent_name: str = "<getevent>",
    max_delta_ms: float = 100.0,
) -> dict[str, Any]:
    """Correlate raw input timestamps with UART frames on the monotonic clock."""

    if max_delta_ms < 0:
        raise ValueError("max_delta_ms must be non-negative")
    events, ignored_lines = parse_getevent_text(getevent_text)
    timed_frames: list[tuple[int, Frame]] = []
    for frame in result.frames:
        try:
            timestamp_ns = int(str(frame.first_ts_mono_ns))
        except (TypeError, ValueError):
            continue
        timed_frames.append((timestamp_ns, frame))
    timed_frames.sort(key=lambda item: (item[0], item[1].index))
    timestamps = [item[0] for item in timed_frames]
    maximum_delta_ns = int(max_delta_ms * 1_000_000)

    def match_dict(event_ns: int, candidate: Optional[tuple[int, Frame]]) -> Any:
        if candidate is None:
            return None
        timestamp_ns, frame = candidate
        delta_ns = timestamp_ns - event_ns
        labels = frame.field_labels()
        return {
            "frame_index": frame.index,
            "frame_type": frame.frame_type,
            "timestamp_ns": str(timestamp_ns),
            "delta_ns": delta_ns,
            "delta_ms": delta_ns / 1_000_000.0,
            "within_window": abs(delta_ns) <= maximum_delta_ns,
            "marker": frame.marker,
            "data_hex": hex_bytes(frame.data),
            "fields": {
                labels[offset]: frame.data[offset]
                for offset in range(len(frame.data))
            },
        }

    correlations = []
    within_window = 0
    for event in events:
        position = bisect.bisect_left(timestamps, event.timestamp_ns)
        previous = timed_frames[position - 1] if position > 0 else None
        following = timed_frames[position] if position < len(timed_frames) else None
        candidates = [candidate for candidate in (previous, following) if candidate]
        nearest = min(
            candidates,
            key=lambda candidate: (
                abs(candidate[0] - event.timestamp_ns),
                candidate[0] < event.timestamp_ns,
            ),
            default=None,
        )
        nearest_dict = match_dict(event.timestamp_ns, nearest)
        if nearest_dict is not None and nearest_dict["within_window"]:
            within_window += 1
        correlations.append(
            {
                "event": event.to_dict(),
                "previous_frame": match_dict(event.timestamp_ns, previous),
                "next_frame": match_dict(event.timestamp_ns, following),
                "nearest_frame": nearest_dict,
            }
        )

    return {
        "schema": CORRELATION_SCHEMA,
        "trace_input": result.capture.name,
        "getevent_input": getevent_name,
        "clock_assumption": (
            "getevent -t timestamps and Frida ts_mono_ns refer to the same device "
            "monotonic clock"
        ),
        "max_delta_ms": max_delta_ms,
        "summary": {
            "parsed_input_events": len(events),
            "ignored_nonblank_lines": ignored_lines,
            "timed_uart_frames": len(timed_frames),
            "events_with_nearest_frame_in_window": within_window,
            "trace_coverage_complete": result.summary()["coverage_complete"],
        },
        "correlations": correlations,
    }


def render_correlation_text(correlation: dict[str, Any]) -> str:
    summary = correlation["summary"]
    lines = [
        "AYANEO Pocket EVO input/UART timestamp correlation",
        f"trace: {correlation['trace_input']}",
        f"getevent: {correlation['getevent_input']}",
        f"clock basis: {correlation['clock_assumption']}",
        (
            f"events: {summary['parsed_input_events']} parsed, "
            f"{summary['ignored_nonblank_lines']} ignored; "
            f"timed frames={summary['timed_uart_frames']}, "
            f"nearest within {correlation['max_delta_ms']} ms="
            f"{summary['events_with_nearest_frame_in_window']}"
        ),
    ]
    for item in correlation["correlations"]:
        event = item["event"]
        nearest = item["nearest_frame"]
        if nearest is None:
            match_text = "nearest=none"
        else:
            match_text = (
                f"nearest=frame-{nearest['frame_index']}:{nearest['frame_type']} "
                f"delta_ms={nearest['delta_ms']:.3f} "
                f"within_window={nearest['within_window']}"
            )
        lines.append(
            f"  line {event['line']} ts={event['timestamp_ns']} "
            f"{event['event_type']} {event['code']} {event['value']} {match_text}"
        )
    return "\n".join(lines)


def _format_change(change: dict[str, Any]) -> str:
    category = " [derived]" if change["category"] == "derived" else ""
    before = "--" if change["before"] is None else f"{change['before']:02X}"
    after = "--" if change["after"] is None else f"{change['after']:02X}"
    return (
        f"offset {change['offset']:02d} {change['label']}: "
        f"{before} -> {after}{category}"
    )


def render_text(result: DecodeResult) -> str:
    summary = result.summary()
    lines = [
        "AYANEO Pocket EVO UART trace",
        f"input: {result.capture.name} ({result.capture.input_format})",
        f"target: {result.capture.target_path}",
        (
            "coverage: "
            + ("complete" if summary["coverage_complete"] else "INCOMPLETE")
            + f"; gaps={summary['capture_gaps']} errors={summary['errors']}"
            + (
                "; lifecycle="
                + (
                    "complete"
                    if summary["lifecycle_complete"]
                    else "INCOMPLETE"
                )
                if summary["lifecycle_required"]
                else ""
            )
        ),
        (
            f"writes: {summary['target_write_events']} target, "
            f"{summary['complete_successful_writes']} complete successful, "
            f"{summary['committed_bytes_captured']} committed bytes captured"
        ),
        (
            f"frames: {summary['frames_total']} total, "
            f"{summary['frames_11_byte_follow']} controller-follow-11, "
            f"{summary['frames_27_byte']} register-27, "
            f"{summary['checksum_invalid_frames']} checksum-invalid; "
            f"noise={summary['noise_bytes']} incomplete={summary['incomplete_bytes']}"
        ),
    ]

    if result.issues:
        lines.append("issues:")
        for issue in result.issues:
            location = []
            if issue.line is not None:
                location.append(f"line={issue.line}")
            if issue.seq is not None:
                location.append(f"seq={issue.seq}")
            suffix = f" ({', '.join(location)})" if location else ""
            lines.append(f"  {issue.severity} {issue.code}: {issue.message}{suffix}")

    if result.capture.writes:
        lines.append("raw writes:")
        for write in result.capture.writes:
            status = (
                "capture-gap"
                if write.committed is None
                else f"committed={len(write.committed)}"
            )
            marker = f" marker={write.marker!r}" if write.marker is not None else ""
            lines.append(
                f"  write {write.index} session={write.session_id} stream={write.stream_id} "
                f"seq={write.seq} op={write.op} requested={write.requested} "
                f"result={write.result} {status}{marker}"
            )
            lines.append(f"    {display_hex(write.data)}")

    if result.frames:
        lines.append("decoded frames:")
        for frame in result.frames:
            checksum = (
                "valid"
                if frame.checksum_valid
                else (
                    f"INVALID observed={frame.observed_checksum:02X} "
                    f"expected={frame.expected_checksum:02X}"
                )
            )
            lines.append(
                f"  frame {frame.index} session={frame.session_id} stream={frame.stream_id} "
                f"writes={frame.source_write_indices} {frame.classification} checksum={checksum}"
            )
            lines.append(f"    {display_hex(frame.data)}")
            if frame.known_register_layout:
                lines.append(
                    "    "
                    + " ".join(
                        f"{KNOWN_FIELD_LABELS[offset]}={frame.data[offset]:02X}"
                        for offset in (10, 12, 14, 16, 18, 20, 22)
                    )
                )
            elif frame.is_follow_frame:
                lines.append(
                    "    "
                    + " ".join(
                        f"{FOLLOW_FIELD_LABELS[offset]}={frame.data[offset]:02X}"
                        for offset in range(2, 9)
                    )
                )

        summaries = frame_offset_summaries(result.frames)
        lines.append("frame value summaries:")
        for value_summary in summaries:
            lines.append(
                f"  {value_summary['frame_type']}: frames={value_summary['frame_count']} "
                f"unique={value_summary['unique_frames']} "
                f"changing payload offsets={value_summary['changing_payload_offsets']} "
                f"changing derived offsets={value_summary['changing_derived_offsets']}"
            )
            for offset_summary in value_summary["offsets"]:
                if not offset_summary["changed"]:
                    continue
                values = ",".join(
                    byte_hex(value) for value in offset_summary["unique_values"]
                )
                derived = (
                    " [derived]" if offset_summary["category"] == "derived" else ""
                )
                lines.append(
                    f"    offset {offset_summary['offset']:02d} "
                    f"{offset_summary['label']}: [{values}]{derived}"
                )

    diffs = adjacent_diffs(result)
    if diffs:
        lines.append("adjacent frame diffs (within session/stream):")
        for diff in diffs:
            lines.append(
                f"  frame {diff['before_frame']} -> {diff['after_frame']}: "
                f"payload offsets={diff['payload_changed_offsets']} "
                f"derived offsets={diff['derived_changed_offsets']}"
            )
            for change in diff["changes"]:
                lines.append(f"    {_format_change(change)}")

    intervals = marker_intervals(result)
    if intervals:
        lines.append("marker intervals:")
        for interval in intervals:
            coverage = "complete" if interval["coverage_complete"] else "INCOMPLETE"
            lines.append(
                f"  {interval['session_id']} {interval['label']!r}: coverage={coverage} "
                f"writes={interval['write_events']} complete_successes="
                f"{interval['successful_complete_writes']} frames={interval['frames_total']} "
                f"follow-11={interval['frames_11_byte_follow']} "
                f"register-27={interval['frames_27_byte']} "
                f"payload offsets={interval['payload_changed_offsets']}"
            )
    return "\n".join(lines)


def _complete_write_counter(result: DecodeResult) -> Counter[str]:
    return Counter(
        hex_bytes(write.committed)
        for write in result.capture.writes
        if write.result > 0 and write.committed is not None
    )


def compare_results(first: DecodeResult, second: DecodeResult) -> dict[str, Any]:
    writes_a = _complete_write_counter(first)
    writes_b = _complete_write_counter(second)
    raw_payloads = []
    for payload in sorted(set(writes_a) | set(writes_b)):
        raw_payloads.append(
            {
                "data_hex": payload,
                "count_a": writes_a[payload],
                "count_b": writes_b[payload],
            }
        )

    frames_a = Counter(hex_bytes(frame.data) for frame in first.frames)
    frames_b = Counter(hex_bytes(frame.data) for frame in second.frames)
    frame_payloads = []
    for payload in sorted(set(frames_a) | set(frames_b)):
        frame_payloads.append(
            {
                "data_hex": payload,
                "count_a": frames_a[payload],
                "count_b": frames_b[payload],
            }
        )

    offset_value_sets = []
    frames_a_by_type: dict[str, list[Frame]] = {}
    frames_b_by_type: dict[str, list[Frame]] = {}
    for frame in first.frames:
        frames_a_by_type.setdefault(frame.frame_type, []).append(frame)
    for frame in second.frames:
        frames_b_by_type.setdefault(frame.frame_type, []).append(frame)
    for frame_type in sorted(set(frames_a_by_type) | set(frames_b_by_type)):
        type_frames_a = frames_a_by_type.get(frame_type, [])
        type_frames_b = frames_b_by_type.get(frame_type, [])
        representative = (type_frames_a or type_frames_b)[0]
        labels = representative.field_labels()
        for offset in range(len(representative.data)):
            values_a = sorted({frame.data[offset] for frame in type_frames_a})
            values_b = sorted({frame.data[offset] for frame in type_frames_b})
            if values_a != values_b:
                offset_value_sets.append(
                    {
                        "frame_type": frame_type,
                        "offset": offset,
                        "label": labels[offset],
                        "values_a": values_a,
                        "values_b": values_b,
                        "category": (
                            "derived"
                            if offset == representative.checksum_offset
                            else "payload"
                        ),
                    }
                )

    paired_frame_diffs = [
        diff_frame_bytes(frame_a, frame_b)
        for frame_a, frame_b in zip(first.frames, second.frames)
    ]
    return {
        "schema": COMPARE_SCHEMA,
        "input_a": first.capture.name,
        "input_b": second.capture.name,
        "summary_a": first.summary(),
        "summary_b": second.summary(),
        "raw_payload_counts": raw_payloads,
        "frame_payload_counts": frame_payloads,
        "frame_offset_summaries_a": frame_offset_summaries(first.frames),
        "frame_offset_summaries_b": frame_offset_summaries(second.frames),
        "offset_value_set_differences": offset_value_sets,
        "paired_frame_diffs": paired_frame_diffs,
    }


def render_comparison_text(comparison: dict[str, Any]) -> str:
    summary_a = comparison["summary_a"]
    summary_b = comparison["summary_b"]
    lines = [
        "AYANEO Pocket EVO UART trace comparison",
        f"A: {comparison['input_a']}",
        f"B: {comparison['input_b']}",
        (
            f"writes: A={summary_a['complete_successful_writes']} complete, "
            f"B={summary_b['complete_successful_writes']} complete"
        ),
        (
            f"frames: A={summary_a['frames_total']} "
            f"({summary_a['frames_11_byte_follow']} follow-11, "
            f"{summary_a['frames_27_byte']} register-27), "
            f"B={summary_b['frames_total']} "
            f"({summary_b['frames_11_byte_follow']} follow-11, "
            f"{summary_b['frames_27_byte']} register-27)"
        ),
        (
            f"coverage: A={'complete' if summary_a['coverage_complete'] else 'INCOMPLETE'}, "
            f"B={'complete' if summary_b['coverage_complete'] else 'INCOMPLETE'}"
        ),
        "raw payload counts:",
    ]
    for payload in comparison["raw_payload_counts"]:
        lines.append(
            f"  A={payload['count_a']} B={payload['count_b']} {payload['data_hex']}"
        )
    if comparison["offset_value_set_differences"]:
        lines.append("27-byte offset value-set differences:")
        for difference in comparison["offset_value_set_differences"]:
            suffix = " [derived]" if difference["category"] == "derived" else ""
            values_a = ",".join(byte_hex(value) for value in difference["values_a"])
            values_b = ",".join(byte_hex(value) for value in difference["values_b"])
            lines.append(
                f"  {difference['frame_type']} offset {difference['offset']:02d} "
                f"{difference['label']}: "
                f"A=[{values_a}] B=[{values_b}]{suffix}"
            )
    if comparison["paired_frame_diffs"]:
        lines.append("paired frame diffs:")
        for diff in comparison["paired_frame_diffs"]:
            lines.append(
                f"  frame {diff['before_frame']} / {diff['after_frame']}: "
                f"payload offsets={diff['payload_changed_offsets']} "
                f"derived offsets={diff['derived_changed_offsets']}"
            )
            for change in diff["changes"]:
                lines.append(f"    {_format_change(change)}")
    return "\n".join(lines)


def _read_input(path: str) -> tuple[str, str]:
    if path == "-":
        return sys.stdin.read(), "<stdin>"
    input_path = Path(path)
    return input_path.read_text(encoding="utf-8"), str(input_path)


def _load_result(path: str, input_format: str, target_path: str) -> DecodeResult:
    text, name = _read_input(path)
    return decode_text(
        text,
        name=name,
        input_format=input_format,
        target_path=target_path,
    )


def build_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Offline Pocket EVO UART trace decoder and comparator"
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    decode_parser = subparsers.add_parser("decode", help="decode one JSONL or hex trace")
    decode_parser.add_argument("input", help="capture path, or - for stdin")
    decode_parser.add_argument(
        "--input-format", choices=("auto", "jsonl", "hex"), default="auto"
    )
    decode_parser.add_argument("--target-path", default=DEFAULT_UART_PATH)
    decode_parser.add_argument("--json", action="store_true", dest="json_output")

    compare_parser = subparsers.add_parser("compare", help="compare two traces")
    compare_parser.add_argument("input_a")
    compare_parser.add_argument("input_b")
    compare_parser.add_argument(
        "--input-format", choices=("auto", "jsonl", "hex"), default="auto"
    )
    compare_parser.add_argument("--target-path", default=DEFAULT_UART_PATH)
    compare_parser.add_argument("--json", action="store_true", dest="json_output")

    correlate_parser = subparsers.add_parser(
        "correlate",
        help="align getevent -lt timestamps with decoded UART frames",
    )
    correlate_parser.add_argument("input", help="UART capture path")
    correlate_parser.add_argument("getevent", help="getevent -lt text path")
    correlate_parser.add_argument(
        "--input-format", choices=("auto", "jsonl", "hex"), default="auto"
    )
    correlate_parser.add_argument("--target-path", default=DEFAULT_UART_PATH)
    correlate_parser.add_argument(
        "--window-ms",
        type=float,
        default=100.0,
        help="maximum absolute nearest-frame delta in milliseconds (default: 100)",
    )
    correlate_parser.add_argument("--json", action="store_true", dest="json_output")
    return parser


def main(argv: Optional[Iterable[str]] = None) -> int:
    parser = build_argument_parser()
    args = parser.parse_args(list(argv) if argv is not None else None)
    try:
        if args.command == "decode":
            result = _load_result(args.input, args.input_format, args.target_path)
            if args.json_output:
                print(json.dumps(result_to_dict(result), indent=2, sort_keys=True))
            else:
                print(render_text(result))
            return 2 if result.summary()["errors"] else 0

        if args.command == "correlate":
            result = _load_result(args.input, args.input_format, args.target_path)
            getevent_text, getevent_name = _read_input(args.getevent)
            correlation = correlate_getevent(
                result,
                getevent_text,
                getevent_name=getevent_name,
                max_delta_ms=args.window_ms,
            )
            if args.json_output:
                print(json.dumps(correlation, indent=2, sort_keys=True))
            else:
                print(render_correlation_text(correlation))
            return 2 if result.summary()["errors"] else 0

        first = _load_result(args.input_a, args.input_format, args.target_path)
        second = _load_result(args.input_b, args.input_format, args.target_path)
        comparison = compare_results(first, second)
        if args.json_output:
            print(json.dumps(comparison, indent=2, sort_keys=True))
        else:
            print(render_comparison_text(comparison))
        return 2 if first.summary()["errors"] or second.summary()["errors"] else 0
    except (OSError, ValueError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
