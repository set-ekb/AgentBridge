# AgentBridge — developer notes

Build requirements: JDK 17, Android SDK 35, Gradle wrapper 8.9.
Verify with: `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`.

- Never commit API tokens, Android Keystore keys, signing configuration, local.properties, screenshots with private data, or signed artifacts.
- Preserve package name and Android Keystore aliases when updating; ensure database migrations preserve memory and task records.
- Make no changes to GitHub Actions without explicit maintainer approval. Do not start CI builds by default.
- Provider-agnostic memory, task state, and safety checks belong to AgentBridge, not to a single AI API adapter.
- Maintain the separation between app-level dialogue, providers, voice, the UI executor and risk checks.
- Validation status 0.8.0: local automated tests passed, real-device test pending.
