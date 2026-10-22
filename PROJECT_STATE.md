# AgentBridge 0.8.11 — REVIEW

Current code baseline: `211a6e4`, version `0.8.11-local`, versionCode 41.
The owner-provided diagnostic export dated 2026-10-22 also reports 0.8.11.
Presence in a phone log confirms execution of that version, not acceptance of
all scenarios or confirmation of its signing/migration history.

Locally verified: first stabilization pass for actual provider-attempt diagnostics,
resumable API/network failures, and consistent daily-quota cooldown.
Scope, evidence and acceptance: [docs/STABILIZATION_0811.md](docs/STABILIZATION_0811.md).
The optional OpenCode integration remains part of the baseline; this pass does
not change its execution or permissions. No workflow changes or CI runs were performed
in this pass. Device and real-provider validation remain separate acceptance steps.

Current patch checks: 135 JVM tests passed; Debug APK assembled; lint 0 errors / 78 warnings.
See VALIDATION.json for scope and unverified scenarios. No signed update is published.

The following evidence belongs to the historical 0.8.0 iteration and must not be
presented as tests or device acceptance of 0.8.11 or this patch.

## Historical checkpoint: 0.8.0

Implemented on 0.7.0: persisted shared dialogue, intent routing, task revision/action gate, local pause/resume/stop, question identity and reply deduplication, shared quota/retry queue, terminal UI, widget/compact chat/notification.

Automated checks passed: 91 JVM tests; Debug APK; lint 0 errors / 70 warnings; personal signing certificate; 16-KB ZIP alignment. Original validation belongs to the 0.8.0 Git history; current VALIDATION.json describes the stabilization patch.

Pending: install over 0.7.0 on the owner’s phone; verify actual Android database migration/Keystore, compact UI/widget, keyboard/font scaling, provider requests, voice, foreground switching and pause/revision races. Follow README_0.8.0_RU.md. Do not mark DONE before device acceptance. No Google Drive writes performed during this iteration.

Requirements snapshot: ARCHITECTURE_0.8_INPUT_RU.md. Preserve package and Keystore aliases. Existing README_0.7.0_RU.md and older files are historical, not acceptance for 0.8.
