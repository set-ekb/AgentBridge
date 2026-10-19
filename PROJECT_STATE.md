# AgentBridge 0.8.0 — REVIEW

Implemented on 0.7.0: persisted shared dialogue, intent routing, task revision/action gate, local pause/resume/stop, question identity and reply deduplication, shared quota/retry queue, terminal UI, widget/compact chat/notification.

Automated checks passed: 91 JVM tests; Debug APK; lint 0 errors / 70 warnings; personal signing certificate; 16-KB ZIP alignment. See VALIDATION.json and validation/.

Pending: install over 0.7.0 on the owner’s phone; verify actual Android database migration/Keystore, compact UI/widget, keyboard/font scaling, provider requests, voice, foreground switching and pause/revision races. Follow README_0.8.0_RU.md. Do not mark DONE before device acceptance. No Google Drive writes performed during this iteration.

Requirements snapshot: ARCHITECTURE_0.8_INPUT_RU.md. Preserve package and Keystore aliases. Existing README_0.7.0_RU.md and older files are historical, not acceptance for 0.8.
