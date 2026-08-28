from __future__ import annotations

import json
from pathlib import Path
import re
import sys
import unittest


TOOLS_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS_DIR))
import evo_trace_decode as decoder  # noqa: E402


def static_frame(
    red: int = 0xFF,
    green: int = 0x00,
    blue: int = 0x00,
    level_86: int = 0x7F,
    level_87: int | None = None,
    reg_8b_value: int = 0x0F,
) -> bytes:
    if level_87 is None:
        level_87 = level_86
    packet = bytearray(
        [
            0xF7,
            0x00,
            0x1C,
            0x20,
            0x01,
            0x80,
            0x00,
            0x81,
            0x00,
            0x8B,
            reg_8b_value,
            0x88,
            red,
            0x89,
            green,
            0x8A,
            blue,
            0x86,
            level_86,
            0x87,
            level_87,
            0x58,
            0x02,
            0x45,
            0x00,
            0x00,
            0xED,
        ]
    )
    packet[25] = sum(packet[1:25]) & 0xFF
    return bytes(packet)


def follow_frame(
    level: int = 0x00,
    idle: tuple[int, int, int] = (0x00, 0x19, 0xFF),
    highlight: tuple[int, int, int] = (0xFA, 0x00, 0xFF),
) -> bytes:
    packet = bytearray(
        [
            0xF7,
            0x55,
            level,
            *idle,
            *highlight,
            0x00,
            0xED,
        ]
    )
    packet[9] = sum(packet[1:9]) & 0xFF
    return bytes(packet)


def write_record(
    data: bytes,
    *,
    seq: int = 1,
    record_seq: int | None = None,
    session_id: str = "s1",
    stream_id: str = "uart:/dev/ttyHS4",
    writer_id: str = "100:7",
    fd_path: str = "/dev/ttyHS4",
    op: str = "write",
    requested: int | None = None,
    captured: int | None = None,
    result: int | None = None,
    truncated: bool = False,
    iovecs: list[dict[str, object]] | None = None,
) -> dict[str, object]:
    record: dict[str, object] = {
        "schema": decoder.CAPTURE_SCHEMA,
        "kind": "write",
        "seq": seq,
        "session_id": session_id,
        "stream_id": stream_id,
        "writer_id": writer_id,
        "ts_unix_ms": 1_700_000_000_000 + seq,
        "ts_mono_ns": str(10_000_000_000 + seq),
        "pid": 100,
        "tid": 101,
        "fd": 7,
        "fd_path": fd_path,
        "op": op,
        "requested": len(data) if requested is None else requested,
        "captured": len(data) if captured is None else captured,
        "truncated": truncated,
        "data_hex": data.hex(),
        "result": len(data) if result is None else result,
        "errno": None,
    }
    if record_seq is not None:
        record["record_seq"] = record_seq
    if iovecs is not None:
        record["iovecs"] = iovecs
    return record


def marker_record(label: str, seq: int, record_seq: int, session_id: str = "s1") -> dict[str, object]:
    return {
        "schema": decoder.CAPTURE_SCHEMA,
        "kind": "marker",
        "session_id": session_id,
        "seq": seq,
        "record_seq": record_seq,
        "label": label,
        "ts_mono_ns": str(20_000_000_000 + seq),
    }


def lifecycle_record(
    phase: str,
    *,
    session_id: str = "s1",
    reason: str | None = None,
    crash: str | None = None,
) -> dict[str, object]:
    record: dict[str, object] = {
        "schema": decoder.CAPTURE_SCHEMA,
        "kind": "session",
        "session_id": session_id,
        "phase": phase,
    }
    if reason is not None:
        record["reason"] = reason
    if crash is not None:
        record["crash"] = crash
    return record


def clean_lifecycle_jsonl(
    *records: dict[str, object], session_id: str = "s1"
) -> str:
    return jsonl(
        lifecycle_record("host_started", session_id=session_id),
        lifecycle_record("process_selected", session_id=session_id),
        lifecycle_record("agent_started", session_id=session_id),
        *records,
        lifecycle_record("host_stopping", session_id=session_id),
        lifecycle_record(
            "detached",
            session_id=session_id,
            reason="application-requested",
        ),
    )


def jsonl(*records: dict[str, object]) -> str:
    return "\n".join(json.dumps(record, separators=(",", ":")) for record in records)


class HexParsingTests(unittest.TestCase):
    def test_spaced_compact_prefixed_and_comments(self) -> None:
        frame = static_frame()
        spaced = " ".join(f"{value:02X}" for value in frame[:9])
        prefixed = ", ".join(f"0x{value:02x}" for value in frame[9:18])
        compact = frame[18:].hex()
        result = decoder.decode_text(
            f"# capture\n{spaced}\n{prefixed} # middle\n{compact}\n",
            input_format="hex",
        )
        self.assertEqual([candidate.data for candidate in result.frames], [frame])
        self.assertEqual(result.summary()["target_write_events"], 3)

    def test_odd_or_non_byte_tokens_are_rejected(self) -> None:
        result = decoder.decode_text("F7 000 1C\nABC\n", input_format="hex")
        self.assertEqual(result.summary()["errors"], 2)
        self.assertEqual(result.summary()["frames_27_byte"], 0)

    def test_multiple_frames_in_one_line(self) -> None:
        red = static_frame(red=0xFF, blue=0x00)
        blue = static_frame(red=0x00, blue=0xFF)
        result = decoder.decode_text((red + blue).hex(), input_format="hex")
        self.assertEqual([frame.data for frame in result.frames], [red, blue])


class LayoutAndChecksumTests(unittest.TestCase):
    def test_exact_known_offsets_and_checksum(self) -> None:
        frame_data = static_frame()
        frame = decoder.decode_text(frame_data.hex(), input_format="hex").frames[0]
        self.assertEqual(len(frame.data), 27)
        self.assertEqual(frame.data[0], 0xF7)
        self.assertEqual(frame.data[10], 0x0F)
        self.assertEqual(frame.data[12], 0xFF)
        self.assertEqual(frame.data[14], 0x00)
        self.assertEqual(frame.data[16], 0x00)
        self.assertEqual(frame.data[18], 0x7F)
        self.assertEqual(frame.data[20], 0x7F)
        self.assertEqual(frame.data[22], 0x02)
        self.assertEqual(frame.data[25], 0x1C)
        self.assertEqual(frame.data[26], 0xED)
        self.assertTrue(frame.checksum_valid)
        self.assertEqual(frame.classification, "matches_current_evo_static_template")
        self.assertEqual(frame.field_labels()[10], "reg_8b_value")
        self.assertEqual(frame.field_labels()[18], "reg_86_value")
        self.assertEqual(frame.field_labels()[20], "reg_87_value")

    def test_checksum_corruption_is_reported_but_frame_is_retained(self) -> None:
        corrupted = bytearray(static_frame())
        corrupted[12] ^= 0x01
        result = decoder.decode_text(corrupted.hex(), input_format="hex")
        self.assertEqual(len(result.frames), 1)
        self.assertFalse(result.frames[0].checksum_valid)
        self.assertEqual(result.summary()["checksum_invalid_frames"], 1)
        self.assertIn("checksum_mismatch", {issue.code for issue in result.issues})

    def test_embedded_prefix_and_suffix_values_do_not_split_frame(self) -> None:
        frame = static_frame(red=0xED, green=0xF7, blue=0x12, level_86=0x34)
        self.assertEqual(frame[25], 0x7D)
        result = decoder.decode_text(frame.hex(), input_format="hex")
        self.assertEqual([candidate.data for candidate in result.frames], [frame])

    def test_bad_suffix_resynchronizes_to_next_frame(self) -> None:
        bad = bytearray(static_frame())
        bad[26] = 0x00
        good = static_frame(blue=0xFF, red=0x00)
        result = decoder.decode_text((bytes(bad) + good).hex(), input_format="hex")
        self.assertEqual([frame.data for frame in result.frames], [good])
        self.assertEqual(result.bad_suffix_candidates, 1)
        self.assertGreaterEqual(result.noise_bytes, 27)

    def test_changed_register_id_uses_positional_names(self) -> None:
        changed = bytearray(static_frame())
        changed[9] = 0x8C
        changed[25] = sum(changed[1:25]) & 0xFF
        frame = decoder.decode_text(changed.hex(), input_format="hex").frames[0]
        self.assertFalse(frame.known_register_layout)
        self.assertEqual(frame.classification, "unknown_27_byte_frame")
        self.assertEqual(frame.field_labels()[10], "pair_3_register_value")


class ControllerFollowFrameTests(unittest.TestCase):
    def test_live_follow_vector_layout_and_checksum(self) -> None:
        expected = bytes.fromhex("f755000019fffa00ff66ed")
        self.assertEqual(follow_frame(), expected)
        result = decoder.decode_text(expected.hex(), input_format="hex")
        self.assertEqual(result.summary()["frames_11_byte_follow"], 1)
        self.assertEqual(result.summary()["frames_27_byte"], 0)
        self.assertEqual(result.bad_suffix_candidates, 0)
        self.assertEqual(result.noise_bytes, 0)
        frame = result.frames[0]
        self.assertEqual(frame.frame_type, "controller_follow_11")
        self.assertEqual(frame.classification, "controller_follow_11_byte_frame")
        self.assertEqual(frame.checksum_rule, "sum(bytes[1:9]) & 0xff")
        self.assertTrue(frame.checksum_valid)
        self.assertEqual(frame.field_labels()[2], "level_value")
        self.assertEqual(frame.field_labels()[3], "idle_background_red_value")
        self.assertEqual(frame.field_labels()[8], "highlight_active_blue_value")

    def test_split_and_mixed_frame_families_reassemble_in_order(self) -> None:
        follow = follow_frame(level=0x80)
        register = static_frame()
        records = (
            write_record(follow[:4], seq=1, record_seq=1),
            write_record(follow[4:] + register[:7], seq=2, record_seq=2),
            write_record(register[7:], seq=3, record_seq=3),
        )
        result = decoder.decode_text(jsonl(*records), input_format="jsonl")
        self.assertEqual([frame.data for frame in result.frames], [follow, register])
        self.assertEqual(result.summary()["frames_11_byte_follow"], 1)
        self.assertEqual(result.summary()["frames_27_byte"], 1)

    def test_many_follow_frames_are_not_false_27_byte_suffix_warnings(self) -> None:
        frames = [follow_frame(level=value) for value in range(0, 64)]
        result = decoder.decode_text(b"".join(frames).hex(), input_format="hex")
        self.assertEqual(len(result.frames), 64)
        self.assertEqual(result.bad_suffix_candidates, 0)
        self.assertEqual(result.noise_bytes, 0)
        self.assertNotIn(
            "bad_register_frame_suffix", {issue.code for issue in result.issues}
        )

    def test_follow_checksum_corruption_is_retained_and_reported(self) -> None:
        corrupted = bytearray(follow_frame())
        corrupted[9] ^= 0x01
        result = decoder.decode_text(corrupted.hex(), input_format="hex")
        self.assertEqual(len(result.frames), 1)
        self.assertFalse(result.frames[0].checksum_valid)
        self.assertIn("checksum_mismatch", {issue.code for issue in result.issues})

    def test_bad_follow_suffix_resynchronizes(self) -> None:
        bad = bytearray(follow_frame())
        bad[10] = 0x00
        good = follow_frame(level=0x99)
        result = decoder.decode_text((bytes(bad) + good).hex(), input_format="hex")
        self.assertEqual([frame.data for frame in result.frames], [good])
        self.assertEqual(result.bad_follow_suffix_candidates, 1)
        self.assertEqual(result.bad_register_suffix_candidates, 0)

    def test_follow_diff_labels_payload_and_derived_checksum(self) -> None:
        before = decoder.decode_text(follow_frame().hex(), input_format="hex").frames[0]
        after = decoder.decode_text(
            follow_frame(level=0x80, highlight=(0xF0, 0x00, 0xFF)).hex(),
            input_format="hex",
        ).frames[0]
        diff = decoder.diff_frame_bytes(before, after)
        self.assertEqual(diff["payload_changed_offsets"], [2, 6])
        self.assertEqual(diff["derived_changed_offsets"], [9])
        self.assertEqual(
            [change["label"] for change in diff["changes"]],
            ["level_value", "highlight_active_red_value", "checksum"],
        )

    def test_follow_offset_summary_lists_unique_values(self) -> None:
        first = follow_frame(level=0x10, idle=(0x00, 0x00, 0xFF))
        second = follow_frame(level=0x20, idle=(0x40, 0x00, 0xFF))
        result = decoder.decode_text((first + second).hex(), input_format="hex")
        summary = decoder.frame_offset_summaries(result.frames)[0]
        self.assertEqual(summary["frame_type"], "controller_follow_11")
        self.assertEqual(summary["changing_payload_offsets"], [2, 3])
        by_offset = {item["offset"]: item for item in summary["offsets"]}
        self.assertEqual(by_offset[2]["unique_values"], [0x10, 0x20])
        self.assertEqual(by_offset[3]["unique_values"], [0x00, 0x40])

    def test_follow_marker_interval_includes_values_and_unique_frames(self) -> None:
        marker = marker_record("movement", seq=1, record_seq=1)
        first = write_record(follow_frame(level=0x10), seq=2, record_seq=2)
        second = write_record(follow_frame(level=0x20), seq=3, record_seq=3)
        result = decoder.decode_text(jsonl(marker, first, second), input_format="jsonl")
        interval = decoder.marker_intervals(result)[0]
        self.assertEqual(interval["frames_11_byte_follow"], 2)
        self.assertEqual(interval["frames_27_byte"], 0)
        self.assertEqual(interval["unique_frames"], 2)
        self.assertEqual(
            interval["frame_offset_summaries"][0]["changing_payload_offsets"],
            [2],
        )

    def test_compare_follow_frames_uses_follow_offsets(self) -> None:
        first = decoder.decode_text(follow_frame(level=0x10).hex(), input_format="hex")
        second = decoder.decode_text(follow_frame(level=0x20).hex(), input_format="hex")
        comparison = decoder.compare_results(first, second)
        differences = comparison["offset_value_set_differences"]
        self.assertEqual(
            [(item["offset"], item["label"]) for item in differences],
            [(2, "level_value"), (9, "checksum")],
        )


class GeteventCorrelationTests(unittest.TestCase):
    def test_getevent_parser_preserves_symbolic_and_numeric_codes(self) -> None:
        text = "\n".join(
            (
                "[   10.000000001] /dev/input/event6: EV_ABS ABS_X 0000007f",
                "[   10.500000] /dev/input/event6: 0000 0000 00000000",
                "device setup line without a timestamp",
            )
        )
        events, ignored = decoder.parse_getevent_text(text)
        self.assertEqual(len(events), 2)
        self.assertEqual(ignored, 1)
        self.assertEqual(events[0].timestamp_ns, 10_000_000_001)
        self.assertEqual(events[0].event_type, "EV_ABS")
        self.assertEqual(events[0].code, "ABS_X")
        self.assertEqual(events[1].timestamp_ns, 10_500_000_000)
        self.assertEqual(events[1].event_type, "0000")

    def test_correlation_reports_nearest_and_next_frame_without_axis_inference(self) -> None:
        first = write_record(follow_frame(level=0x10), seq=1, record_seq=1)
        second = write_record(follow_frame(level=0x20), seq=2, record_seq=2)
        result = decoder.decode_text(
            clean_lifecycle_jsonl(first, second), input_format="jsonl"
        )
        getevent = (
            "[ 10.000000002] /dev/input/event6: EV_ABS ABS_X 00000080\n"
        )
        correlation = decoder.correlate_getevent(
            result, getevent, max_delta_ms=1.0
        )
        item = correlation["correlations"][0]
        self.assertEqual(item["nearest_frame"]["frame_index"], 1)
        self.assertEqual(item["nearest_frame"]["delta_ns"], 0)
        self.assertEqual(item["next_frame"]["frame_index"], 1)
        self.assertEqual(
            item["nearest_frame"]["fields"]["level_value"], 0x20
        )
        self.assertNotIn("stick", json.dumps(correlation).lower())

    def test_correlation_marks_large_delta_outside_window(self) -> None:
        event = write_record(follow_frame(), seq=1, record_seq=1)
        result = decoder.decode_text(jsonl(event), input_format="jsonl")
        correlation = decoder.correlate_getevent(
            result,
            "[ 20.000000000] /dev/input/event6: EV_ABS ABS_X 0\n",
            max_delta_ms=10,
        )
        nearest = correlation["correlations"][0]["nearest_frame"]
        self.assertFalse(nearest["within_window"])
        self.assertEqual(
            correlation["summary"]["events_with_nearest_frame_in_window"], 0
        )


class JsonlAndReassemblyTests(unittest.TestCase):
    def test_split_writes_reassemble_across_writer_reopen_on_logical_stream(self) -> None:
        frame = static_frame()
        first = write_record(frame[:10], seq=1, record_seq=1, writer_id="100:7")
        second = write_record(frame[10:], seq=2, record_seq=2, writer_id="100:9")
        result = decoder.decode_text(
            clean_lifecycle_jsonl(first, second), input_format="jsonl"
        )
        self.assertEqual([candidate.data for candidate in result.frames], [frame])
        self.assertEqual(result.frames[0].source_write_indices, [0, 1])

    def test_different_sessions_do_not_cross_reassemble(self) -> None:
        frame = static_frame()
        first = write_record(frame[:10], session_id="s1")
        second = write_record(frame[10:], session_id="s2")
        result = decoder.decode_text(jsonl(first, second), input_format="jsonl")
        self.assertEqual(result.frames, [])
        self.assertEqual(result.incomplete_bytes, 10)
        self.assertGreater(result.noise_bytes, 0)

    def test_partial_syscall_uses_only_returned_prefix(self) -> None:
        frame = static_frame()
        first = write_record(
            frame[:12], seq=1, record_seq=1, requested=12, captured=12, result=10
        )
        second = write_record(frame[10:], seq=2, record_seq=2)
        result = decoder.decode_text(
            clean_lifecycle_jsonl(first, second), input_format="jsonl"
        )
        self.assertEqual(result.capture.writes[0].committed, frame[:10])
        self.assertEqual([candidate.data for candidate in result.frames], [frame])
        self.assertTrue(result.summary()["coverage_complete"])

    def test_failed_and_zero_writes_contribute_no_bytes(self) -> None:
        frame = static_frame()
        failed = write_record(frame, seq=1, record_seq=1, result=-1)
        failed["errno"] = 5
        zero = write_record(frame, seq=2, record_seq=2, result=0)
        good = write_record(frame, seq=3, record_seq=3)
        result = decoder.decode_text(jsonl(failed, zero, good), input_format="jsonl")
        self.assertEqual([candidate.data for candidate in result.frames], [frame])
        self.assertEqual(result.summary()["failed_write_events"], 1)
        self.assertEqual(result.summary()["zero_write_events"], 1)

    def test_writev_iovecs_are_validated_not_duplicated(self) -> None:
        frame = static_frame()
        event = write_record(
            frame,
            op="writev",
            iovecs=[
                {"index": 0, "requested": 8, "captured": 8, "data_hex": frame[:8].hex()},
                {
                    "index": 1,
                    "requested": len(frame) - 8,
                    "captured": len(frame) - 8,
                    "data_hex": frame[8:].hex(),
                },
            ],
        )
        result = decoder.decode_text(jsonl(event), input_format="jsonl")
        self.assertEqual(result.summary()["committed_bytes_captured"], 27)
        self.assertEqual([candidate.data for candidate in result.frames], [frame])

    def test_uncaptured_committed_bytes_break_reassembly(self) -> None:
        frame = static_frame()
        first = write_record(frame[:10], seq=1, record_seq=1)
        gap = write_record(
            frame[10:15],
            seq=2,
            record_seq=2,
            requested=17,
            captured=5,
            result=17,
            truncated=True,
        )
        after = write_record(frame[15:], seq=3, record_seq=3)
        result = decoder.decode_text(jsonl(first, gap, after), input_format="jsonl")
        self.assertEqual(result.frames, [])
        self.assertFalse(result.summary()["coverage_complete"])
        self.assertIn(
            "uncaptured_committed_bytes", {issue.code for issue in result.issues}
        )

    def test_global_record_sequence_gap_breaks_fragment_join(self) -> None:
        frame = static_frame()
        first = write_record(frame[:10], seq=1, record_seq=1)
        second = write_record(frame[10:], seq=2, record_seq=3)
        result = decoder.decode_text(jsonl(first, second), input_format="jsonl")
        self.assertEqual(result.frames, [])
        self.assertFalse(result.summary()["coverage_complete"])
        self.assertIn("record_sequence_gap", {issue.code for issue in result.issues})

    def test_foreign_uart_path_is_ignored(self) -> None:
        foreign = write_record(static_frame(), fd_path="/dev/ttyHS5")
        result = decoder.decode_text(jsonl(foreign), input_format="jsonl")
        self.assertEqual(result.capture.writes, [])
        self.assertEqual(result.capture.foreign_write_events, 1)

    def test_malformed_captured_length_is_an_error_and_gap(self) -> None:
        event = write_record(static_frame())
        event["captured"] = 26
        result = decoder.decode_text(jsonl(event), input_format="jsonl")
        self.assertEqual(result.summary()["errors"], 1)
        self.assertFalse(result.summary()["coverage_complete"])
        self.assertEqual(result.frames, [])


class MarkerDiffAndReportTests(unittest.TestCase):
    def test_capture_error_record_makes_coverage_incomplete(self) -> None:
        record = {
            "schema": decoder.CAPTURE_SCHEMA,
            "kind": "error",
            "session_id": "s1",
            "record_seq": 1,
            "description": "agent detached unexpectedly",
        }
        result = decoder.decode_text(jsonl(record), input_format="jsonl")
        self.assertFalse(result.summary()["coverage_complete"])
        self.assertIn("capture_error_record", {issue.code for issue in result.issues})

    def test_zero_write_marker_interval_can_be_complete(self) -> None:
        first = marker_record("centre", seq=1, record_seq=1)
        second = marker_record("next", seq=2, record_seq=2)
        result = decoder.decode_text(
            clean_lifecycle_jsonl(first, second), input_format="jsonl"
        )
        intervals = decoder.marker_intervals(result)
        self.assertEqual(intervals[0]["write_events"], 0)
        self.assertTrue(intervals[0]["coverage_complete"])

    def test_marker_interval_with_sequence_gap_is_incomplete(self) -> None:
        first = marker_record("centre", seq=1, record_seq=1)
        second = marker_record("next", seq=3, record_seq=3)
        result = decoder.decode_text(jsonl(first, second), input_format="jsonl")
        intervals = decoder.marker_intervals(result)
        self.assertEqual(intervals[0]["write_events"], 0)
        self.assertFalse(intervals[0]["coverage_complete"])


class LifecycleCoverageTests(unittest.TestCase):
    def test_clean_runner_shutdown_is_complete(self) -> None:
        marker = marker_record("clean", seq=1, record_seq=1)
        result = decoder.decode_text(
            clean_lifecycle_jsonl(marker), input_format="jsonl"
        )
        summary = result.summary()
        self.assertTrue(summary["lifecycle_required"])
        self.assertTrue(summary["lifecycle_complete"])
        self.assertTrue(summary["coverage_complete"])
        self.assertTrue(result.capture.lifecycles["s1"].complete)
        self.assertTrue(decoder.marker_intervals(result)[0]["coverage_complete"])
        self.assertEqual(
            [event.phase for event in result.capture.lifecycles["s1"].events],
            [
                "host_started",
                "process_selected",
                "agent_started",
                "host_stopping",
                "detached",
            ],
        )
        self.assertEqual(
            decoder.result_to_dict(result)["lifecycles"][0]["reasons"], []
        )

    def test_missing_terminal_records_only_invalidate_open_marker_window(self) -> None:
        first = marker_record("closed", seq=1, record_seq=1)
        second = marker_record("open-at-eof", seq=2, record_seq=2)
        result = decoder.decode_text(
            jsonl(
                lifecycle_record("host_started"),
                lifecycle_record("process_selected"),
                lifecycle_record("agent_started"),
                first,
                second,
            ),
            input_format="jsonl",
        )
        self.assertFalse(result.summary()["lifecycle_complete"])
        self.assertFalse(result.summary()["coverage_complete"])
        self.assertIn(
            "lifecycle_missing_phase", {issue.code for issue in result.issues}
        )
        intervals = decoder.marker_intervals(result)
        self.assertTrue(intervals[0]["coverage_complete"])
        self.assertFalse(intervals[1]["coverage_complete"])
        self.assertTrue(
            any("host_stopping" in reason for reason in intervals[1]["gap_reasons"])
        )
        self.assertTrue(
            any("detached" in reason for reason in intervals[1]["gap_reasons"])
        )

    def test_process_terminated_crash_detach_is_incomplete(self) -> None:
        marker = marker_record("active", seq=1, record_seq=1)
        result = decoder.decode_text(
            jsonl(
                lifecycle_record("host_started"),
                lifecycle_record("process_selected"),
                lifecycle_record("agent_started"),
                marker,
                lifecycle_record("host_stopping"),
                lifecycle_record(
                    "detached",
                    reason="process-terminated",
                    crash="native crash summary",
                ),
            ),
            input_format="jsonl",
        )
        self.assertFalse(result.summary()["coverage_complete"])
        self.assertIn(
            "lifecycle_unexpected_detach", {issue.code for issue in result.issues}
        )
        interval = decoder.marker_intervals(result)[0]
        self.assertFalse(interval["coverage_complete"])
        self.assertTrue(
            any("process-terminated" in reason for reason in interval["gap_reasons"])
        )
        self.assertTrue(any("crash" in reason for reason in interval["gap_reasons"]))

    def test_detach_without_host_stopping_is_incomplete(self) -> None:
        marker = marker_record("active", seq=1, record_seq=1)
        result = decoder.decode_text(
            jsonl(
                lifecycle_record("host_started"),
                lifecycle_record("process_selected"),
                lifecycle_record("agent_started"),
                marker,
                lifecycle_record("detached", reason="application-requested"),
            ),
            input_format="jsonl",
        )
        codes = {issue.code for issue in result.issues}
        self.assertFalse(result.summary()["coverage_complete"])
        self.assertIn("lifecycle_detach_without_host_stopping", codes)
        self.assertFalse(decoder.marker_intervals(result)[0]["coverage_complete"])

    def test_hex_input_does_not_require_runner_lifecycle(self) -> None:
        result = decoder.decode_text(static_frame().hex(), input_format="hex")
        self.assertFalse(result.summary()["lifecycle_required"])
        self.assertTrue(result.summary()["lifecycle_complete"])
        self.assertTrue(result.summary()["coverage_complete"])


class FrameDiffAndReportTests(unittest.TestCase):
    def test_reg_8b_diff_keeps_neutral_name_and_checksum_is_derived(self) -> None:
        before_result = decoder.decode_text(static_frame().hex(), input_format="hex")
        after_result = decoder.decode_text(
            static_frame(reg_8b_value=0x01).hex(), input_format="hex"
        )
        diff = decoder.diff_frame_bytes(before_result.frames[0], after_result.frames[0])
        self.assertEqual(diff["payload_changed_offsets"], [10])
        self.assertEqual(diff["derived_changed_offsets"], [25])
        self.assertEqual(diff["changes"][0]["label"], "reg_8b_value")
        self.assertEqual(diff["changes"][1]["label"], "checksum")

    def test_equal_checksum_frames_still_report_rgb_offsets(self) -> None:
        red = decoder.decode_text(
            static_frame(red=0xFF, blue=0x00).hex(), input_format="hex"
        ).frames[0]
        blue = decoder.decode_text(
            static_frame(red=0x00, blue=0xFF).hex(), input_format="hex"
        ).frames[0]
        self.assertEqual(red.observed_checksum, blue.observed_checksum)
        diff = decoder.diff_frame_bytes(red, blue)
        self.assertEqual(diff["payload_changed_offsets"], [12, 16])
        self.assertEqual(diff["derived_changed_offsets"], [])

    def test_comparison_retains_non_frame_raw_payloads(self) -> None:
        first = decoder.decode_text("01 02 03", input_format="hex", name="a")
        second = decoder.decode_text("01 02 04", input_format="hex", name="b")
        comparison = decoder.compare_results(first, second)
        self.assertEqual(len(comparison["raw_payload_counts"]), 2)
        self.assertEqual(comparison["frame_payload_counts"], [])

    def test_machine_report_has_only_neutral_field_keys(self) -> None:
        result = decoder.decode_text(static_frame().hex(), input_format="hex")
        report = decoder.result_to_dict(result)
        keys = report["frames"][0]["fields"].keys()
        forbidden = re.compile(r"\b(?:mask|zone|quadrant|ring|stick|left|right)\b", re.I)
        self.assertFalse(any(forbidden.search(key) for key in keys))
        self.assertEqual(report["schema"], decoder.REPORT_SCHEMA)
        # Deterministic and serializable for downstream comparison tools.
        json.dumps(report, sort_keys=True)


if __name__ == "__main__":
    unittest.main()
