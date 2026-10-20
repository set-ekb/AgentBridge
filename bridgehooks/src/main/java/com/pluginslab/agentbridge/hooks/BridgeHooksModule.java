package com.pluginslab.agentbridge.hooks;

import android.app.Activity;
import android.content.Intent;
import android.util.Log;
import io.github.libxposed.api.XposedModule;

/** Optional Vector 2.2 module: read-only Activity lifecycle events, no UI mutation. */
public final class BridgeHooksModule extends XposedModule {
    private static final String TAG = "AgentBridgeHooks";
    private static final String BRIDGE = "com.pluginslab.agentbridge";
    private static final String RECEIVER = "com.pluginslab.agentbridge.agent.HookEventReceiver";
    private static final String ACTION = "com.pluginslab.agentbridge.action.HOOK_LIFECYCLE";

    @Override
    public void onPackageReady(PackageReadyParam param) {
        final String scopedPackage = param.getPackageName();
        if (scopedPackage == null || scopedPackage.equals(BRIDGE)
                || scopedPackage.equals("android")
                || scopedPackage.equals("com.android.systemui")) return;
        try {
            hook(Activity.class.getDeclaredMethod("onResume")).intercept(chain -> {
                Object result = chain.proceed();
                publish(chain.getThisObject(), scopedPackage, "resumed");
                return result;
            });
            hook(Activity.class.getDeclaredMethod("onPause")).intercept(chain -> {
                publish(chain.getThisObject(), scopedPackage, "paused");
                return chain.proceed();
            });
        } catch (Throwable t) {
            Log.w(TAG, "Activity lifecycle hook unavailable for " + scopedPackage, t);
        }
    }

    private static void publish(Object target, String scopedPackage, String state) {
        try {
            if (!(target instanceof Activity)) return;
            Activity activity = (Activity) target;
            if (!scopedPackage.equals(activity.getPackageName())) return;
            Intent event = new Intent(ACTION);
            event.setClassName(BRIDGE, RECEIVER);
            event.putExtra("package", scopedPackage);
            event.putExtra("activity", activity.getClass().getName());
            event.putExtra("state", state);
            activity.sendBroadcast(event);
        } catch (Throwable t) {
            // Never let instrumentation interrupt the observed app.
            Log.w(TAG, "Lifecycle signal skipped", t);
        }
    }
}
