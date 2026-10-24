# Portable task handoff v1 (experimental)

This is an **opt-in, standalone prototype**, not an Android in-app database migration.
AgentBridge already has durable task state and shared memory. This tool addresses only
explicit interchange of a **small, provider-neutral checkpoint** with Codex, Claude,
Gemini, OpenCode, and other command-line agents. It does **not** start or resume actions.

## Example
```sh
python3 tools/handoff/session_handoff.py save --file /tmp/bridge-handoff.json \
  --task-id adb-diagnostics --goal 'Diagnose device offline' \
  --next-step 'Recheck ADB status on the current network' \
  --state PAUSED --verified 'Observed ADB offline'

python3 tools/handoff/session_handoff.py show --file /tmp/bridge-handoff.json
python3 -m unittest discover -s tools/handoff -p 'test_*.py'
```

Never store credentials, personal data, screenshots, or raw tool logs here.
A heuristic blocks obvious secret-related words but **is not a complete DLP system**.
Do not publish actual checkpoint files. Store locally with proper access controls.
A receiving agent must re-observe device/UI state before executing any queued action.
Do not automatically replay clicks after interrupted or rate-limited requests.

## Integration boundary
Android remains authoritative for task lifecycle and existing persistent memory.
A later, separately tested Android adapter may export/import the v1 shape,
with authenticated access, explicit user control, redaction, and migration tests.
No duplicate database is introduced here. No CI workflow changes.
