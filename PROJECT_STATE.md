# AgentBridge 0.8.12 — REVIEW

Build source: `8a91afd6c4db358dc1828d7759b9015b7da3236f`, version `0.8.12-local`, versionCode 42.
Manual signed build: https://github.com/set-ekb/AgentBridge/actions/runs/39839017625

Adds the explicit OpenCode/connector startup button and a fixed Termux launcher.
Setup and behavior: [docs/OPENCODE_START_RU.md](docs/OPENCODE_START_RU.md).
Android unit tests, debug/release compilation, pinned certificate and APK signature
checks passed. All 15 Python OpenCode tests passed (7 new startup regressions).
Main and GitHub Releases were not changed. Full current lint was not rerun.

The owner's latest 0.8.11 phone export confirms request IDs, a timeout resulting
in PAUSED, and two cancelled requests with no later tool actions in those attempts.
It also shows eight successful list_apps calls in one task. Repetition and the
reported active-window symptom remain unresolved, not fixed by this launcher.
Actual Termux startup, Android permissions, lifecycle and MCP connection must be
verified on the phone. A healthy gateway is not proof of Android MCP readiness.

Previous stabilization evidence: [docs/STABILIZATION_0811.md](docs/STABILIZATION_0811.md).

## Historical checkpoint: 0.8.0

Implemented on 0.7.0: persisted shared dialogue, intent routing, task revision/action gate, local pause/resume/stop, question identity and reply deduplication, shared quota/retry queue, terminal UI, widget/compact chat/notification.

Automated checks passed: 91 JVM tests; Debug APK; lint 0 errors / 70 warnings; personal signing certificate; 16-KB ZIP alignment. Original validation belongs to the 0.8.0 Git history; current VALIDATION.json describes the stabilization patch.

Pending: install over 0.7.0 on the owner’s phone; verify actual Android database migration/Keystore, compact UI/widget, keyboard/font scaling, provider requests, voice, foreground switching and pause/revision races. Follow README_0.8.0_RU.md. Do not mark DONE before device acceptance. No Google Drive writes performed during this iteration.

Requirements snapshot: ARCHITECTURE_0.8_INPUT_RU.md. Preserve package and Keystore aliases. Existing README_0.7.0_RU.md and older files are historical, not acceptance for 0.8.
