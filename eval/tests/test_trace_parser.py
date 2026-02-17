from __future__ import annotations

import json
from pathlib import Path
import tempfile
import unittest

from eval.aw_bridge.trace_parser import parse_trace


class TraceParserTest(unittest.TestCase):
    def test_extracts_complete_task_answer_and_summary(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            trace_dir = Path(tmp)
            args_file = trace_dir / "artifacts" / "tool_call_args" / "1_args.json"
            args_file.parent.mkdir(parents=True, exist_ok=True)
            args_file.write_text(
                json.dumps({"status": "success", "answer": "john@example.com"}),
                encoding="utf-8",
            )

            summary_file = trace_dir / "artifacts" / "run_summary" / "run_summary.json"
            summary_file.parent.mkdir(parents=True, exist_ok=True)
            summary_file.write_text(
                json.dumps(
                    {
                        "stop_reason": "GoalAchieved",
                        "turns_executed": 4,
                        "tool_calls": 6,
                        "tool_failures": 1,
                    }
                ),
                encoding="utf-8",
            )

            trace_rows = [
                {
                    "type": "tool_call",
                    "data": {"name": "complete_task"},
                    "artifacts": [
                        {
                            "kind": "tool_call_args",
                            "path": "artifacts/tool_call_args/1_args.json",
                        }
                    ],
                },
                {
                    "type": "session_stopped",
                    "artifacts": [
                        {
                            "kind": "run_summary",
                            "path": "artifacts/run_summary/run_summary.json",
                        }
                    ],
                },
            ]
            trace_jsonl = trace_dir / "trace.jsonl"
            trace_jsonl.write_text(
                "\n".join(json.dumps(row) for row in trace_rows),
                encoding="utf-8",
            )

            result = parse_trace(trace_dir)
            self.assertEqual(result.answer, "john@example.com")
            self.assertEqual(result.answer_status, "success")
            self.assertEqual(result.completion_reason, "GoalAchieved")
            self.assertEqual(result.turns_executed, 4)
            self.assertEqual(result.tool_calls, 6)
            self.assertEqual(result.tool_failures, 1)
            self.assertEqual(result.run_summary_path, str(summary_file))


if __name__ == "__main__":
    unittest.main()
