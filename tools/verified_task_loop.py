"""Safe, provider-independent task completion decisions.

This module does not dispatch Android actions. Callers must enforce permissions,
cancellation, and task revision at the actual tool-execution boundary.
"""
from dataclasses import dataclass
from enum import Enum
from typing import Optional


class ActionClass(str, Enum):
    READ_ONLY = "read_only"
    REVERSIBLE = "reversible"
    IRREVERSIBLE = "irreversible"


class Outcome(str, Enum):
    NOT_STARTED = "not_started"
    SUCCEEDED = "succeeded"
    FAILED = "failed"
    UNKNOWN = "unknown"


class Decision(str, Enum):
    VERIFY = "verify"
    RETRY = "retry"
    BLOCK = "block"
    DONE = "done"
    FAILED = "failed"


@dataclass(frozen=True)
class StepResult:
    task_id: str
    task_revision: int
    idempotency_key: str
    action_class: ActionClass
    outcome: Outcome
    approval_granted: bool = False
    postcondition_checked: bool = False
    postcondition_passed: bool = False
    evidence_ref: Optional[str] = None
    cancelled: bool = False
    current_revision: Optional[int] = None

    def __post_init__(self) -> None:
        if not self.task_id or not self.idempotency_key:
            raise ValueError("task_id and idempotency_key are required")
        if self.task_revision < 0:
            raise ValueError("task_revision must be non-negative")
        if self.postcondition_passed and not self.postcondition_checked:
            raise ValueError("passed postcondition requires completed verification")


def decide(result: StepResult) -> Decision:
    """Make a conservative, pure decision; never execute a tool or log secrets."""
    if result.cancelled:
        return Decision.BLOCK
    if (result.current_revision is not None
            and result.task_revision != result.current_revision):
        return Decision.BLOCK
    if (result.action_class != ActionClass.READ_ONLY
            and not result.approval_granted):
        return Decision.BLOCK
    if result.postcondition_checked:
        if result.postcondition_passed:
            return Decision.DONE if result.evidence_ref else Decision.BLOCK
        if result.outcome in (Outcome.SUCCEEDED, Outcome.UNKNOWN):
            return Decision.BLOCK
        return Decision.FAILED if result.outcome == Outcome.FAILED else Decision.BLOCK
    if result.outcome in (Outcome.SUCCEEDED, Outcome.UNKNOWN):
        return Decision.VERIFY
    if result.outcome == Outcome.FAILED:
        return Decision.FAILED
    if result.action_class == ActionClass.READ_ONLY:
        return Decision.RETRY
    return Decision.BLOCK
