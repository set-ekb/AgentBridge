# Verified Task Loop & Skill Memory — AgentBridge 0.8.x

Status: PROPOSED / NOT IMPLEMENTED. Date: 2026-10-28. Author: ChatGPT.
Scope: incremental stabilization of current Android/Termux/OpenCode infrastructure; no provider lock-in. Applies to bridge supervisor, task queue and memory. Does not replace existing persistence, approvals, or event loop.

## Problem and constraints
Current docs record repeated read-only `list_apps` calls, UI-active stalls, task pause/recovery ambiguity and MCP readiness questions. A success message from LLM is not evidence of a completed Android action. Existing states, API backoff, and cancellation protections must remain. No new privilege grant, raw unbounded tool exposure or destructive automatic retries.

## Execution contract

```text
INCOMING → INTAKE → READY → EXECUTING → VERIFYING → COMMITTED → DONE
                       ↘ BLOCKED     ↘ PAUSED     ↘ UNKNOWN
                                     ↘ CANCELLED  ↘ FAILED
```

Existing persisted states are the authority. Above names are conceptual phases; map into the existing task state machine rather than introducing a parallel DB or changing enum values without a migration. `DONE` requires a verifier success against observed postconditions and matching task revision; `UNKNOWN` means an action may have run but result cannot be established, and must not trigger blind replay.

Each unit of work carries `task_id`, `task_revision`, `attempt_id`, `step_id`, `idempotency_key`, `action_class` (`READ_ONLY`, `REVERSIBLE`, `IRREVERSIBLE`), a bounded `acceptance` description and `evidence_refs`. Secrets and user content must not be logged.

### Intake
Read active project constraints and task context first. Ask only for missing safety-critical input; known repeated tasks take a fast path. Form explicit preconditions, expected result, and deterministic verification before executing. Avoid multiple-model debate unless it adds clear information.

### Tool exposure and policy
Route only relevant tools to the planner, preserving native OpenCode tools and a separate policy-controlled Android MCP channel. A planner never decides its own permission scope. Existing action gate, cancellation, screen-capture restrictions and user confirmation are enforced **at tool execution time**; interview or high-level consent does not bypass them.

### Execute / verify
1. Persist attempt and intent before tool invocation. Record an opaque invocation ID; avoid storing sensitive arguments.
2. Check task revision, cancellation and permission immediately before Android action.
3. Execute once; postcondition verifier independently reads observable state or a safe exported artifact. Do not use LLM assertions as verification.
4. Persist evidence and only then advance to DONE. On ambiguous timeout/callback loss use UNKNOWN and inspect state before any retry.
5. For read-only requests, repeated calls should be coalesced where valid. Retry only after admission/backoff; never allow retries to duplicate side effects.
6. Open UI must not block supervisor polling/heartbeats. UI is a view of persisted state, not the owner of a running task.

### Event log
Append-only, sanitized JSONL events: `task_received`, `task_started`, `step_intent`, `step_result`, `verification_started`, `verification_passed`, `verification_failed`, `task_blocked`, `task_unknown`, `task_done`. Every event includes event_version, monotonic sequence (per stream), wall-clock time, opaque task/attempt/step IDs, category, and evidence reference where present. Redact API keys, bearer headers, URLs, prompts, UI text, cookies, phone numbers, screenshots. Export only deliberate safe summaries to shared Drive memory, not all local events.

### Skill registry
A learned skill is a reviewed, **verified** reusable procedure, not untrusted model text. Store:
- `skill_id`, `version`, `title`, `input_schema`, `preconditions`, `required_tools`, `required_permissions`.
- `action_class`, `steps`, `expected_postconditions`, `verifier`, `rollback` (if possible), `evidence_refs`, `verified_on`, `compatible_versions`.
- `status` one of `candidate`, `verified`, `deprecated`; a failed run adds an error lesson but never silently upgrades a candidate.
Store project knowledge canonically in existing 00_AI_Context/Android_Agent and keep local operation state in AgentBridge. Discard or expire device-specific details after use.

## Delivery plan
A. **Baseline and mapping:** inspect current Kotlin state machine, DB migrations, OpenCode connector, gateway and actual test suite; record baseline commit and no-change device behavior.
B. **Persisted execution contract:** map attempt/step/revision/idempotency into existing entities, with migration tests if schema changes.
C. **Verifier:** deterministic read-only checks and failure categories; guard against stale revisions, missing MCP and fake DONE.
D. **Skill registry:** provider-agnostic schema, validated importer and retrieval; never auto-execute a retrieved instruction without current policy.
E. **Recovery:** lease/heartbeat and process-death scenarios, ambiguous-side-effect handling; no duplicate callbacks.
F. **Device acceptance:** visible Bridge window, background continuation, MCP availability, cancellation, stale task resume, log export, explicit confirmation.

## Acceptance tests (must all pass before claim of implementation)
1. Ten repeated read-only tasks: no unintended duplicate side effects; bounded provider/tool invocations.
2. Simulated crash after step intent but before result: UNKNOWN until postcondition check; no blind replay.
3. Active UI during ongoing task: no blocking of supervisor heartbeat, tool routing or completion.
4. Missing MCP/Termux/Gateway: BLOCKED or PAUSED with actionable reason, never DONE.
5. Cancel between planning and tool dispatch: no Android action executed after cancellation gate.
6. Dangerous/irreversible command without current approval: denied and logged without sensitive content.
7. Failed verification does not commit DONE or register a verified skill.
8. JSONL export deduplicates events, maintains event IDs, and contains no secrets or user content.
9. Old DB/task records survive upgrades; rollback/migration path documented.
10. Android unit tests, debug assemble and lint: `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`; separate phone and real-provider checks before release.

## Implementation guardrails
Do not modify GitHub Actions, signature, package name or Keystore. Do not trigger CI by default. Do not run destructive device commands. Do not install unsigned/debug APK over signed existing installation. Do not claim tests passed until executed; maintain REVIEW status until hardware acceptance.

## Hand-off to OpenCode
Read `AGENTS.md`, `PROJECT_STATE.md`, `docs/STABILIZATION_0811.md`, `docs/OPENCODE_START_RU.md`, `docs/OPENCODE_MCP_RU.md` and this proposal. First identify existing components and exact integration points. Implement in small reviewable commits, preserve current state semantics, add regression tests, and produce file/line evidence with executed test output. Begin with B and C; do not rewrite the agent system or launch a build unless authorized in the working environment.
