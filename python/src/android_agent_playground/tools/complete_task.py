from __future__ import annotations

from dataclasses import dataclass

from android_agent_playground.tools.registry import (
    ToolExecutionContext,
    ToolExecutionResult,
    ToolExecutionSuccess,
    ToolInvocation,
    ToolSpec,
    ValidationResult,
)


class CompleteTaskTool(ToolSpec):
    name = "complete_task"
    description = (
        "Call this when you have finished working on the task.\n\n"
        "Parameters:\n"
        "- status: \"success\" if the goal was achieved, \"failure\" if it cannot be completed\n"
        "- answer: The response to return to the user (always required)\n"
        "- reason: If status is \"failure\", explain why (optional but recommended)\n\n"
        "Always provide a helpful answer even when failing - explain what you tried and why it didn't work."
    )

    @property
    def parameter_schema(self) -> dict:
        return {
            "type": "object",
            "properties": {
                "status": {
                    "type": "string",
                    "enum": ["success", "failure"],
                    "description": "Whether the task succeeded or failed",
                },
                "answer": {"type": "string", "description": "The answer or result to return to the user"},
                "reason": {"type": "string", "description": "If failure, explain why the task could not be completed"},
            },
            "required": ["status", "answer"],
            "additionalProperties": False,
        }

    def validate(self, params: dict) -> ValidationResult:
        status = params.get("status")
        if status not in ("success", "failure"):
            return ValidationResult.invalid("status must be 'success' or 'failure'")
        answer = params.get("answer")
        if not isinstance(answer, str) or not answer.strip():
            return ValidationResult.invalid("answer cannot be empty")
        return ValidationResult.ok()

    def create_invocation(self, params: dict) -> ToolInvocation:
        return CompleteTaskInvocation(params=params)


@dataclass(frozen=True)
class CompleteTaskInvocation:
    params: dict
    tool_name: str = "complete_task"

    def get_description(self) -> str:
        status = self.params.get("status", "unknown")
        answer = self.params.get("answer", "")
        preview = (answer[:50] + "...") if len(answer) > 50 else answer
        return f"Complete task ({status}): {preview}"

    def execute(self, context: ToolExecutionContext) -> ToolExecutionResult:
        status = self.params.get("status", "success")
        answer = self.params.get("answer", "Task completed")
        reason = self.params.get("reason")
        is_success = status == "success"

        output_lines = []
        if is_success:
            output_lines.append("Task completed successfully.")
        else:
            output_lines.append("Task failed.")
            if reason:
                output_lines.append(f"Reason: {reason}")
        output_lines.append(f"\nAnswer: {answer}")
        output = "\n".join(output_lines)

        return ToolExecutionSuccess(
            output=output,
            data={
                "completed": True,
                "success": is_success,
                "answer": answer,
                "reason": reason,
            },
        )
