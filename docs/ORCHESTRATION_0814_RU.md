# AgentBridge 0.8.14 — Gemini-led orchestration

Status: phase 1 — implementation contract, NOT YET connected to Android.

## Roles
Gemini plans and may propose delegation. Android TaskDispatcher owns IDs, state, routing, and authorization. OpenCode executes technical work with native OpenCode tools plus AgentBridge Android MCP, using the existing gateway on loopback 4097 and OpenCode on loopback 4096.

## Modes
GEMINI_LEADS: ordinary chat through Gemini; bounded technical delegation proposals can become OpenCode tasks.
OPENCODE_ONLY: direct user prompt to OpenCode, including when Gemini returns 429.
GEMINI_ONLY: Gemini handles requests without OpenCode.
No silent automatic fallback to a write-capable executor on quota errors.

## Structured proposal
{"kind":"delegate_to_opencode","goal":"Inspect logs for slowdown","constraints":["read-only","no root"],"expected_result":"Evidence and recommendations"}
Proposal is untrusted model output, NOT an authorization. Android validates kind, length, supported fields and capability policy, generates UUID, and constructs bounded prompt. Never accept Gemini-supplied task IDs, executable code, URLs, or secrets as routing fields.

## Existing gateway contract
POST /tasks with body {"id":"<android-generated-uuid>","prompt":"<bounded-task>"}. GET /tasks/{id}. POST /tasks/{id}/cancel. Existing TaskManager deduplicates by task ID and persists state before sending prompt_async.

## Lifecycle
PROPOSED -> QUEUED -> DISPATCHING -> RUNNING -> DONE / FAILED / CANCELLED / UNKNOWN.
Persist UUID and DISPATCHING before network call. Retry only with SAME UUID; ambiguous timeout enters UNKNOWN and requires reconciliation, never fresh execution. Poll from lifecycle-independent background worker; foreground UI only observes state. Cancellation is confirmed only if gateway confirms it. After app restart, restore IDs and query gateway. Gateway 404 after ambiguous dispatch does not prove nothing executed.

## Safety
Separate pairing token stays local and never reaches Gemini. No root by default. Risky actions need enforceable capabilities and user authorization at Android/MCP layer, not natural-language claims from Gemini. Bound result size and sanitize logs before Gemini synthesis. Preserve one final result per task.

## Acceptance tests before shipping
1. Simple Gemini chat does not dispatch.
2. Valid Gemini proposal makes exactly one OpenCode task with Android-issued UUID.
3. Duplicate send, network timeout, restart do not duplicate sessions.
4. Completed OpenCode result is correctly attributed and optionally summarized once by Gemini.
5. Gemini 429 leaves direct OpenCode available without silent escalation.
6. Active screen/background/restart retain progress.
7. Unconfirmed abort is not reported as cancelled.
8. Invalid model action is rejected with no tool execution.
9. 0.8.13 gateway API remains compatible.

## Android implementation sequence
Implement typed Gemini proposal parser + persisted TaskDispatcher; connect Gemini provider; reuse existing OpenCode client; add one-send UI mode switch, task journal and lifecycle-independent worker; run tests/build/device check. Until those are merged and installed, 0.8.14 orchestration is NOT operational.