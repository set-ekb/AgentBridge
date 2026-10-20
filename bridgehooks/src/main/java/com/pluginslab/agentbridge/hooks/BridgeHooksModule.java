package com.pluginslab.agentbridge.hooks;

import android.app.Activity;
import android.content.Intent;
import android.util.Log;
import io.github.libxposed.api.XposedModule;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Optional Vector module: read-only Activity lifecycle, no UI mutation.
 * Early events/errors are logged into Vector using libxposed's own logger.
 * Only coarse status and class/package names are emitted (no screen contents).
 */
public final class BridgeHooksModule extends XposedModule {
    private static final String TAG = "AgentBridgeHooks";
    private static final String BRIDGE = "com.pluginslab.agentbridge";
    private static final String RECEIVER = "com.pluginslab.agentbridge.agent.HookEventReceiver";
    private static final String ACTION = "com.pluginslab.agentbridge.action.HOOK_LIFECYCLE";
    private final AtomicBoolean callbackReported = new AtomicBoolean(false);

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, "module_loaded api=102");
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        final String scopedPackage = param.getPackageName();
        if (scopedPackage == null || scopedPackage.equals(BRIDGE)
                || scopedPackage.equals("android")
                || scopedPackage.equals("com.android.systemui")) {
            log(Log.INFO, TAG, "package_skipped");
            return;
        }
        log(Log.INFO, TAG, "package_ready package=" + scopedPackage);
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
            log(Log.INFO, TAG, "hooks_installed package=" + scopedPackage);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hook_install_failed package=" + scopedPackage
                    + " exception=" + t.getClass().getSimpleName());
        }
    }

    private void publish(Object target, String scopedPackage, String state) {
        if (!(target instanceof Activity)) return;
        Activity activity = (Activity) target;
        if (!scopedPackage.equals(activity.getPackageName())) return;
        if (callbackReported.compareAndSet(false, true)) {
            log(Log.INFO, TAG, "first_callback package=" + scopedPackage);
            dispatch(activity, scopedPackage, "hook_active", "");
        }
        dispatch(activity, scopedPackage, "", state);
    }

    private void dispatch(Activity activity, String scopedPackage, String stage, String state) {
        try {
            Intent event = new Intent(ACTION);
            event.setClassName(BRIDGE, RECEIVER);
            event.putExtra("package", scopedPackage);
            event.putExtra("activity", activity.getClass().getName());
            if (!stage.isEmpty()) event.putExtra("stage", stage);
            if (!state.isEmpty()) event.putExtra("state", state);
            activity.sendBroadcast(event);
        } catch (Throwable t) {
            // Never let instrumentation break the observed app.
            log(Log.WARN, TAG, "dispatch_failed package=" + scopedPackage
                    + " exception=" + t.getClass().getSimpleName());
        }
    }
}
