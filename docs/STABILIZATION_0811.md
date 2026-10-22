# Stabilization of 0.8.11 — first pass

Baseline: `211a6e4` (main), versionCode 41. Scope: provider failures and diagnostic accuracy.

Evidence: owner-provided `AgentBridge-log-2026-10-22_18-41-43.jsonl`,
2134 lines including the export header. Six task starts: one complete,
two failed, one paused, two cancelled. The old journal reports each second
of a 429/503 countdown as `api_error`; those entries cannot measure failed
HTTP attempts (64 quota countdown events and 42 service countdown events).
`IllegalStateException` alone does not identify the reason
for either failed task. `open_url` errors and rejected UI tools require
separate investigation; do not attribute them to the API without evidence.

Acceptance for this patch:

- A real HTTP attempt has one start and one terminal diagnostic event,
  with provider, opaque request ID and (for task execution) task attempt/step.
  Process death or journal rotation can still leave an incomplete trace;
  missing terminal events do not prove failure or success.
- Countdown updates, local quota admission and owner cancellation are not
  counted as HTTP failures. Retried requests keep the same payload/history.
- Exhausted temporary service failures preserve a resumable PAUSED task;
  HTTP 429 preserves WAIT_QUOTA; invalid credentials remain an error.
- Task failures use bounded, explicit technical categories; never persist
  exception messages, prompts, URLs, headers or response bodies.
- Existing journal files remain readable; legacy counts are explicitly
  described as event counts, not verified HTTP attempts.
- Run `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug` with JDK 17,
  Gradle 8.9 and SDK 35; exercise retry, cancellation and failure policy tests.
- Do not modify workflows or start CI. Do not distribute an APK signed with
  a temporary debug key as an update to the owner's installed application.

## Result — 2026-10-22

Local checks passed with JDK 17, Gradle 8.9 and SDK/build-tools 35:
`./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`.

- 135 JVM tests in 32 suites: 0 failures, 0 errors; 15 regression tests added.
- Debug APK assembled. No installable update or release signature is distributed.
- Lint: 0 errors, 78 warnings. Warnings remain review items, not acceptance.
- No new dependencies, permissions, DB schema, workflow or OpenCode changes.
- No CI builds started. Device/live-API acceptance is not claimed.

Implemented: transport-boundary traces for Gemini and other HTTP providers,
opaque request IDs, per-task correlation, distinct cancellation events,
legacy event-count warnings, shared daily cooldown for quotaMetric-only errors,
and resumable states for known transient service/network/accessibility failures.
A generic I/O exception is not assumed to be a network error.
A transport invocation does not prove the server received or processed a request.

The export's old ambiguous exceptions cannot be diagnosed retroactively.
The new categories improve evidence for subsequent attempts. Do not claim that
this patch fixes the two old open_url failures or the six UI-tool rejections.

## Next device acceptance

1. Build/sign through the existing owner-controlled release path; verify update
   signature before installation. Do not install the temporary local debug APK
   over the owner's application or uninstall it to bypass a signature mismatch.
2. Run a read-only request. Check that every new request_id has a start and a
   response/error/cancellation where the journal covers the complete attempt.
   Countdown ticks must not create api_error entries. Old entries remain legacy.
3. On a naturally occurring 503 or network outage, check PAUSED and manual resume
   from a fresh screen. On 429, check WAIT_QUOTA and shared cooldown. Do not
   deliberately exhaust paid/daily quota to perform this check.
4. Stop while a request/wait is active; no later response may run Android tools.
   Physical HttpURLConnection cancellation latency is not proven by JVM fakes.
5. Reproduce open_url on a harmless destination and inspect the current UI;
   investigate launch/foreground problems separately from provider failures.

Status: REVIEW. Local checks pass; phone/live-provider validation remains open.
Machine-readable current results: `VALIDATION.json`.
