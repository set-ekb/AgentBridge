# AgentBridge Hooks 0.1 / Vector 2.2

Optional modern libxposed API 102 module. It observes Activity onResume/onPause
in applications explicitly enabled by the user in Vector; it never modifies
arguments or return values, hooks system_server or reads UI text.

## Installation and scope
1. Install AgentBridge 0.8.2 (main APK) first.
2. Install AgentBridge Hooks APK separately.
3. Enable *AgentBridge Hooks* in Vector. Select only chosen app(s) in scope.
   Default suggested scope: Android Settings (com.android.settings).
4. Restart the chosen app if required.
5. In AgentBridge use tools get_app_events / wait_for_app_event to see read-only
   lifecycle hints labelled source=vector_hint.

The hook sends explicit broadcasts to AgentBridge with only package name,
Activity class and resumed/paused state. The receiver is exported because the
hook executes in another app UID. These broadcasts are **untrusted hints**;
other apps can forge them. AgentBridge never authorizes automation or proves
a task succeeded on that basis: Accessibility verification is always required.

This module is not required for normal AgentBridge use, and should not be
enabled for banking, authentication or other sensitive apps. Remove its
scope/disable it in Vector to restore original app behavior.

References: https://github.com/JingMatrix/Vector and
https://github.com/libxposed/example
