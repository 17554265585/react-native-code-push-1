package com.microsoft.codepush.react;

import android.app.Activity;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;

import com.facebook.react.ReactApplication;
import com.facebook.react.ReactInstanceManager;
import com.facebook.react.ReactRootView;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.JSBundleLoader;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.ChoreographerCompat;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.facebook.react.modules.core.ReactChoreographer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CodePushNativeModule extends ReactContextBaseJavaModule {
    private ReactApplicationContext mReactContext = null;
    private String mClientUniqueId = null;
    private LifecycleEventListener mLifecycleEventListener = null;
    private int mMinimumBackgroundDuration = 0;

    private CodePush mCodePush;

    public CodePushNativeModule(ReactApplicationContext reactContext, CodePush codePush) {
        super(reactContext);

        mCodePush = codePush;
        mReactContext = reactContext;
        // Initialize module state while we have a reference to the current context.
        mClientUniqueId = Settings.Secure.getString(reactContext.getContentResolver(), Settings.Secure.ANDROID_ID);
    }

    @Override
    public Map<String, Object> getConstants() {
        final Map<String, Object> constants = new HashMap<>();

        constants.put("codePushInstallModeImmediate", CodePushInstallMode.IMMEDIATE.getValue());
        constants.put("codePushInstallModeOnNextRestart", CodePushInstallMode.ON_NEXT_RESTART.getValue());
        constants.put("codePushInstallModeOnNextResume", CodePushInstallMode.ON_NEXT_RESUME.getValue());
        constants.put("codePushInstallModeOnNextSuspend", CodePushInstallMode.ON_NEXT_SUSPEND.getValue());

        constants.put("codePushUpdateStateRunning", CodePushUpdateState.RUNNING.getValue());
        constants.put("codePushUpdateStatePending", CodePushUpdateState.PENDING.getValue());
        constants.put("codePushUpdateStateLatest", CodePushUpdateState.LATEST.getValue());

        return constants;
    }

    @Override
    public String getName() {
        return "CodePush";
    }

    public String getBinaryContentsHash() {
        return CodePushUpdateUtils.getHashForBinaryContents(mReactContext, mCodePush.isDebugMode());
    }

    private void loadBundleLegacy() {
        final Activity currentActivity = getCurrentActivity();
        if (currentActivity == null) {
            // The currentActivity can be null if it is backgrounded / destroyed, so we simply
            // no-op to prevent any null pointer exceptions.
            return;
        }
        mCodePush.invalidateCurrentInstance();

        currentActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                currentActivity.recreate();
            }
        });
    }

    // Use reflection to find and set the appropriate fields on ReactInstanceManager. See #556 for a proposal for a less brittle way
    // to approach this.
    private void setJSBundle(ReactInstanceManager instanceManager, String latestJSBundleFile) throws IllegalAccessException {
        try {
            JSBundleLoader latestJSBundleLoader;
            if (latestJSBundleFile.toLowerCase().startsWith("assets://")) {
                latestJSBundleLoader = JSBundleLoader.createAssetLoader(getReactApplicationContext(), latestJSBundleFile, false);
            } else {
                latestJSBundleLoader = JSBundleLoader.createFileLoader(latestJSBundleFile);
            }

            Field bundleLoaderField = instanceManager.getClass().getDeclaredField("mBundleLoader");
            bundleLoaderField.setAccessible(true);
            bundleLoaderField.set(instanceManager, latestJSBundleLoader);
        } catch (Exception e) {
            CodePushUtils.log("Unable to set JSBundle - CodePush may not support this version of React Native");
            throw new IllegalAccessException("Could not setJSBundle");
        }
    }

    private void loadBundle(final String resourceName) {
        clearLifecycleEventListener();
        try {
            mCodePush.clearDebugCacheIfNeeded(resolveInstanceManager(), resourceName);
        } catch(Exception e) {
            // If we got error in out reflection we should clear debug cache anyway.
            mCodePush.clearDebugCacheIfNeeded(null, resourceName);
        }

        try {
            // #1) Get the ReactInstanceManager instance, which is what includes the
            //     logic to reload the current React context.
            final ReactInstanceManager instanceManager = resolveInstanceManager();
            if (instanceManager == null) {
                return;
            }

            String latestJSBundleFile = mCodePush.getJSBundleFileInternal(resourceName);

            // #2) Update the locally stored JS bundle file path
            setJSBundle(instanceManager, latestJSBundleFile);

            // #3) Get the context creation method and fire it on the UI thread (which RN enforces)
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    try {
                        // We don't need to resetReactRootViews anymore
                        // due the issue https://github.com/facebook/react-native/issues/14533
                        // has been fixed in RN 0.46.0
                        //resetReactRootViews(instanceManager);

                        instanceManager.recreateReactContextInBackground();
                        mCodePush.initializeUpdateAfterRestart(resourceName);
                    } catch (Exception e) {
                        // The recreation method threw an unknown exception
                        // so just simply fallback to restarting the Activity (if it exists)
                        loadBundleLegacy();
                    }
                }
            });

        } catch (Exception e) {
            // Our reflection logic failed somewhere
            // so fall back to restarting the Activity (if it exists)
            CodePushUtils.log("Failed to load the bundle, falling back to restarting the Activity (if it exists). " + e.getMessage());
            loadBundleLegacy();
        }
    }

    // This workaround has been implemented in order to fix https://github.com/facebook/react-native/issues/14533
    // resetReactRootViews allows to call recreateReactContextInBackground without any exceptions
    // This fix also relates to https://github.com/Microsoft/react-native-code-push/issues/878
    private void resetReactRootViews(ReactInstanceManager instanceManager) throws NoSuchFieldException, IllegalAccessException {
        Field mAttachedRootViewsField = instanceManager.getClass().getDeclaredField("mAttachedRootViews");
        mAttachedRootViewsField.setAccessible(true);
        List<ReactRootView> mAttachedRootViews = (List<ReactRootView>)mAttachedRootViewsField.get(instanceManager);
        for (ReactRootView reactRootView : mAttachedRootViews) {
            reactRootView.removeAllViews();
            reactRootView.setId(View.NO_ID);
        }
        mAttachedRootViewsField.set(instanceManager, mAttachedRootViews);
    }

    private void clearLifecycleEventListener() {
        // Remove LifecycleEventListener to prevent infinite restart loop
        if (mLifecycleEventListener != null) {
            getReactApplicationContext().removeLifecycleEventListener(mLifecycleEventListener);
            mLifecycleEventListener = null;
        }
    }

    // Use reflection to find the ReactInstanceManager. See #556 for a proposal for a less brittle way to approach this.
    private ReactInstanceManager resolveInstanceManager() throws NoSuchFieldException, IllegalAccessException {
        ReactInstanceManager instanceManager = CodePush.getReactInstanceManager();
        if (instanceManager != null) {
            return instanceManager;
        }

        final Activity currentActivity = getCurrentActivity();
        if (currentActivity == null) {
            return null;
        }

        ReactApplication reactApplication = (ReactApplication) currentActivity.getApplication();
        instanceManager = reactApplication.getReactNativeHost().getReactInstanceManager();

        return instanceManager;
    }

    @ReactMethod
    public void downloadUpdate(final ReadableMap updatePackage, final boolean notifyProgress, final String resourceName, final Promise promise) {
        AsyncTask<Void, Void, Void> asyncTask = new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                ModuleInstance moduleInstance = mCodePush.getModuleInstance(resourceName);
                try {
                    JSONObject mutableUpdatePackage = CodePushUtils.convertReadableToJsonObject(updatePackage);
                    CodePushUtils.setJSONValueForKey(mutableUpdatePackage, CodePushConstants.BINARY_MODIFIED_TIME_KEY, "" + mCodePush.getBinaryResourcesModifiedTime());
                    moduleInstance.updateManager.downloadPackage(mutableUpdatePackage, mCodePush.getBundleName(resourceName), new DownloadProgressCallback() {
                        private boolean hasScheduledNextFrame = false;
                        private DownloadProgress latestDownloadProgress = null;

                        @Override
                        public void call(DownloadProgress downloadProgress) {
                            if (!notifyProgress) {
                                return;
                            }

                            latestDownloadProgress = downloadProgress;
                            // If the download is completed, synchronously send the last event.
                            if (latestDownloadProgress.isCompleted()) {
                                dispatchDownloadProgressEvent();
                                return;
                            }

                            if (hasScheduledNextFrame) {
                                return;
                            }

                            hasScheduledNextFrame = true;
                            getReactApplicationContext().runOnUiQueueThread(new Runnable() {
                                @Override
                                public void run() {
                                    ReactChoreographer.getInstance().postFrameCallback(ReactChoreographer.CallbackType.TIMERS_EVENTS, new ChoreographerCompat.FrameCallback() {
                                        @Override
                                        public void doFrame(long frameTimeNanos) {
                                            if (!latestDownloadProgress.isCompleted()) {
                                                dispatchDownloadProgressEvent();
                                            }

                                            hasScheduledNextFrame = false;
                                        }
                                    });
                                }
                            });
                        }

                        public void dispatchDownloadProgressEvent() {
                            getReactApplicationContext()
                                    .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                                    .emit(CodePushConstants.DOWNLOAD_PROGRESS_EVENT_NAME, latestDownloadProgress.createWritableMap());
                        }
                    }, mCodePush.getPublicKey());

                    JSONObject newPackage = moduleInstance.updateManager.getPackage(CodePushUtils.tryGetString(updatePackage, CodePushConstants.PACKAGE_HASH_KEY));
                    promise.resolve(CodePushUtils.convertJsonObjectToWritable(newPackage));
                } catch (CodePushInvalidUpdateException e) {
                    CodePushUtils.log(e, resourceName);
                    moduleInstance.settingsManager.saveFailedUpdate(CodePushUtils.convertReadableToJsonObject(updatePackage));
                    promise.reject(e);
                } catch (IOException | CodePushUnknownException e) {
                    CodePushUtils.log(e, resourceName);
                    promise.reject(e);
                }

                return null;
            }
        };

        asyncTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    @ReactMethod
    public void getConfiguration(final String resourceName, Promise promise) {
        try {
            WritableMap configMap =  Arguments.createMap();
            configMap.putString("appVersion", mCodePush.getAppVersion());
            configMap.putString("clientUniqueId", mClientUniqueId);
            configMap.putString("serverUrl", mCodePush.getServerUrl());

            // The binary hash may be null in debug builds
            String binaryContentsHash = getBinaryContentsHash();
            if (binaryContentsHash != null) {
                configMap.putString(CodePushConstants.PACKAGE_HASH_KEY, binaryContentsHash);
            }
            promise.resolve(configMap);
        } catch(CodePushUnknownException e) {
            CodePushUtils.log(e, resourceName);
            promise.reject(e);
        }
    }

    @ReactMethod
    public void getUpdateMetadata(final int updateState,final String resourceName, final Promise promise) {
        AsyncTask<Void, Void, Void> asyncTask = new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                ModuleInstance moduleInstance = mCodePush.getModuleInstance(resourceName);
                try {
                    JSONObject currentPackage = moduleInstance.updateManager.getCurrentPackage();

                    if (currentPackage == null) {
                        promise.resolve(null);
                        return null;
                    }

                    Boolean currentUpdateIsPending = false;

                    if (currentPackage.has(CodePushConstants.PACKAGE_HASH_KEY)) {
                        String currentHash = currentPackage.optString(CodePushConstants.PACKAGE_HASH_KEY, null);
                        currentUpdateIsPending = moduleInstance.settingsManager.isPendingUpdate(currentHash);
                    }

                    if (updateState == CodePushUpdateState.PENDING.getValue() && !currentUpdateIsPending) {
                        // The caller wanted a pending update
                        // but there isn't currently one.
                        promise.resolve(null);
                    } else if (updateState == CodePushUpdateState.RUNNING.getValue() && currentUpdateIsPending) {
                        // The caller wants the running update, but the current
                        // one is pending, so we need to grab the previous.
                        JSONObject previousPackage = moduleInstance.updateManager.getPreviousPackage();

                        if (previousPackage == null) {
                            promise.resolve(null);
                            return null;
                        }

                        promise.resolve(CodePushUtils.convertJsonObjectToWritable(previousPackage));
                    } else {
                        // The current package satisfies the request:
                        // 1) Caller wanted a pending, and there is a pending update
                        // 2) Caller wanted the running update, and there isn't a pending
                        // 3) Caller wants the latest update, regardless if it's pending or not
                        if (mCodePush.isRunningBinaryVersion()) {
                            // This only matters in Debug builds. Since we do not clear "outdated" updates,
                            // we need to indicate to the JS side that somehow we have a current update on
                            // disk that is not actually running.
                            CodePushUtils.setJSONValueForKey(currentPackage, "_isDebugOnly", true);
                        }

                        // Enable differentiating pending vs. non-pending updates
                        CodePushUtils.setJSONValueForKey(currentPackage, "isPending", currentUpdateIsPending);
                        promise.resolve(CodePushUtils.convertJsonObjectToWritable(currentPackage));
                    }
                } catch (CodePushMalformedDataException e) {
                    // We need to recover the app in case 'codepush.json' is corrupted
                    CodePushUtils.log(e.getMessage(), resourceName);
                    clearUpdates(resourceName);
                    promise.resolve(null);
                } catch(CodePushUnknownException e) {
                    CodePushUtils.log(e, resourceName);
                    promise.reject(e);
                }

                return null;
            }
        };

        asyncTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    @ReactMethod
    public void getNewStatusReport(final String resourceName, final Promise promise) {
        AsyncTask<Void, Void, Void> asyncTask = new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                ModuleInstance moduleInstance = mCodePush.getModuleInstance(resourceName);
                try {
                    if (mCodePush.needToReportRollback()) {
                        mCodePush.setNeedToReportRollback(false);
                        JSONArray failedUpdates = moduleInstance.settingsManager.getFailedUpdates();
                        if (failedUpdates != null && failedUpdates.length() > 0) {
                            try {
                                JSONObject lastFailedPackageJSON = failedUpdates.getJSONObject(failedUpdates.length() - 1);
                                WritableMap lastFailedPackage = CodePushUtils.convertJsonObjectToWritable(lastFailedPackageJSON);
                                WritableMap failedStatusReport = moduleInstance.telemetryManager.getRollbackReport(lastFailedPackage);
                                if (failedStatusReport != null) {
                                    promise.resolve(failedStatusReport);
                                    return null;
                                }
                            } catch (JSONException e) {
                                throw new CodePushUnknownException("Unable to read failed updates information stored in SharedPreferences.", e);
                            }
                        }
                    } else if (mCodePush.didUpdate()) {
                        JSONObject currentPackage = moduleInstance.updateManager.getCurrentPackage();
                        if (currentPackage != null) {
                            WritableMap newPackageStatusReport = moduleInstance.telemetryManager.getUpdateReport(CodePushUtils.convertJsonObjectToWritable(currentPackage));
                            if (newPackageStatusReport != null) {
                                promise.resolve(newPackageStatusReport);
                                return null;
                            }
                        }
                    } else if (mCodePush.isRunningBinaryVersion()) {
                        WritableMap newAppVersionStatusReport = moduleInstance.telemetryManager.getBinaryUpdateReport(mCodePush.getAppVersion());
                        if (newAppVersionStatusReport != null) {
                            promise.resolve(newAppVersionStatusReport);
                            return null;
                        }
                    } else {
                        WritableMap retryStatusReport = moduleInstance.telemetryManager.getRetryStatusReport();
                        if (retryStatusReport != null) {
                            promise.resolve(retryStatusReport);
                            return null;
                        }
                    }

                    promise.resolve("");
                } catch(CodePushUnknownException e) {
                    CodePushUtils.log(e, resourceName);
                    promise.reject(e);
                }
                return null;
            }
        };

        asyncTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    @ReactMethod
    public void installUpdate(final ReadableMap updatePackage, final String resourceName, final int installMode, final int minimumBackgroundDuration, final Promise promise) {
        AsyncTask<Void, Void, Void> asyncTask = new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                final ModuleInstance moduleInstance = mCodePush.getModuleInstance(resourceName);
                try {
                    moduleInstance.updateManager.installPackage(CodePushUtils.convertReadableToJsonObject(updatePackage), moduleInstance.settingsManager.isPendingUpdate(null));

                    String pendingHash = CodePushUtils.tryGetString(updatePackage, CodePushConstants.PACKAGE_HASH_KEY);
                    if (pendingHash == null) {
                        throw new CodePushUnknownException("Update package to be installed has no hash.");
                    } else {
                        moduleInstance.settingsManager.savePendingUpdate(pendingHash, /* isLoading */false);
                    }

                    if (installMode == CodePushInstallMode.ON_NEXT_RESUME.getValue() ||
                            // We also add the resume listener if the installMode is IMMEDIATE, because
                            // if the current activity is backgrounded, we want to reload the bundle when
                            // it comes back into the foreground.
                            installMode == CodePushInstallMode.IMMEDIATE.getValue() ||
                            installMode == CodePushInstallMode.ON_NEXT_SUSPEND.getValue()) {

                        // Store the minimum duration on the native module as an instance
                        // variable instead of relying on a closure below, so that any
                        // subsequent resume-based installs could override it.
                        CodePushNativeModule.this.mMinimumBackgroundDuration = minimumBackgroundDuration;

                        if (mLifecycleEventListener == null) {
                            // Ensure we do not add the listener twice.
                            mLifecycleEventListener = new LifecycleEventListener() {
                                private Date lastPausedDate = null;
                                private Handler appSuspendHandler = new Handler(Looper.getMainLooper());
                                private Runnable loadBundleRunnable = new Runnable() {
                                    @Override
                                    public void run() {
                                        CodePushUtils.log("Loading bundle on suspend", resourceName);
                                        loadBundle(resourceName);
                                    }
                                };

                                @Override
                                public void onHostResume() {
                                    appSuspendHandler.removeCallbacks(loadBundleRunnable);
                                    // As of RN 36, the resume handler fires immediately if the app is in
                                    // the foreground, so explicitly wait for it to be backgrounded first
                                    // if (lastPausedDate != null) {
                                    //     long durationInBackground = (new Date().getTime() - lastPausedDate.getTime()) / 1000;
                                    //     if (installMode == CodePushInstallMode.IMMEDIATE.getValue()
                                    //             || durationInBackground >= CodePushNativeModule.this.mMinimumBackgroundDuration) {
                                    //         CodePushUtils.log("Loading bundle on resume", resourceName);
                                    //         loadBundle(resourceName);
                                    //     }
                                    // }
                                }

                                @Override
                                public void onHostPause() {
                                    // Save the current time so that when the app is later
                                    // resumed, we can detect how long it was in the background.
                                    lastPausedDate = new Date();

                                    if (installMode == CodePushInstallMode.ON_NEXT_SUSPEND.getValue() && moduleInstance.settingsManager.isPendingUpdate(null)) {
                                        appSuspendHandler.postDelayed(loadBundleRunnable, minimumBackgroundDuration * 1000);
                                    }
                                }

                                @Override
                                public void onHostDestroy() {
                                }
                            };

                            getReactApplicationContext().addLifecycleEventListener(mLifecycleEventListener);
                        }
                    }

                    promise.resolve("");
                } catch(CodePushUnknownException e) {
                    CodePushUtils.log(e, resourceName);
                    promise.reject(e);
                }

                return null;
            }
        };

        asyncTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    @ReactMethod
    public void isFailedUpdate(String packageHash, String resourceName, Promise promise) {
        ModuleInstance moduleInstance = mCodePush.getModuleInstance(resourceName);
        try {
            promise.resolve(moduleInstance.settingsManager.isFailedHash(packageHash));
        } catch (CodePushUnknownException e) {
            CodePushUtils.log(e, resourceName);
            promise.reject(e);
        }
    }

    @ReactMethod
    public void getLatestRollbackInfo(String resourceName, Promise promise) {
        ModuleInstance moduleInstance = mCodePush.getModuleInstance(resourceName);
        try {
            JSONObject latestRollbackInfo = moduleInstance.settingsManager.getLatestRollbackInfo();
            if (latestRollbackInfo != null) {
                promise.resolve(CodePushUtils.convertJsonObjectToWritable(latestRollbackInfo));
            } else {
                promise.resolve(null);
            }
        } catch (CodePushUnknownException e) {
            CodePushUtils.log(e, resourceName);
            promise.reject(e);
        }
    }

    @ReactMethod
    public void setLatestRollbackInfo(String packageHash, String resourceName, Promise promise) {
        ModuleInstance moduleInstance = mCodePush.getModuleInstance(resourceName);
        try {
            moduleInstance.settingsManager.setLatestRollbackInfo(packageHash);
            promise.resolve(null);
        } catch (CodePushUnknownException e) {
            CodePushUtils.log(e, resourceName);
            promise.reject(e);
        }
    }

    @ReactMethod
    public void isFirstRun(String packageHash, final String resourceName, Promise promise) {
        ModuleInstance moduleInstance = mCodePush.getModuleInstance(resourceName);
        try {
            boolean isFirstRun = mCodePush.didUpdate()
                    && packageHash != null
                    && packageHash.length() > 0
                    && packageHash.equals(moduleInstance.updateManager.getCurrentPackageHash());
            promise.resolve(isFirstRun);
        } catch(CodePushUnknownException e) {
            CodePushUtils.log(e, resourceName);
            promise.reject(e);
        }
    }

    @ReactMethod
    public void notifyApplicationReady(String resourceName, Promise promise) {
        ModuleInstance moduleInstance = mCodePush.getModuleInstance(resourceName);
        try {
            moduleInstance.settingsManager.removePendingUpdate();
            promise.resolve("");
        } catch(CodePushUnknownException e) {
            CodePushUtils.log(e, resourceName);
            promise.reject(e);
        }
    }

    @ReactMethod
    public void recordStatusReported(String resourceName, ReadableMap statusReport) {
        ModuleInstance moduleInstance = mCodePush.getModuleInstance(resourceName);
        try {
            moduleInstance.telemetryManager.recordStatusReported(statusReport);
        } catch(CodePushUnknownException e) {
            CodePushUtils.log(e, resourceName);
        }
    }

    @ReactMethod
    public void restartApp(boolean onlyIfUpdateIsPending, Promise promise) {
        final String resourceName = "common";
        ModuleInstance moduleInstance = mCodePush.getModuleInstance(resourceName);
        try {
            // If this is an unconditional restart request, or there
            // is current pending update, then reload the app.
            if (!onlyIfUpdateIsPending || moduleInstance.settingsManager.isPendingUpdate(null)) {
                loadBundle(resourceName);
                promise.resolve(true);
                return;
            }

            promise.resolve(false);
        } catch(CodePushUnknownException e) {
            CodePushUtils.log(e);
            promise.reject(e);
        }
    }

    @ReactMethod
    public void saveStatusReportForRetry(String resourceName, ReadableMap statusReport) {
        ModuleInstance moduleInstance = mCodePush.getModuleInstance(resourceName);
        try {
            moduleInstance.telemetryManager.saveStatusReportForRetry(statusReport);
        } catch(CodePushUnknownException e) {
            CodePushUtils.log(e, resourceName);
        }
    }

    @ReactMethod
    // Replaces the current bundle with the one downloaded from removeBundleUrl.
    // It is only to be used during tests. No-ops if the test configuration flag is not set.
    public void downloadAndReplaceCurrentBundle(String remoteBundleUrl, final String resourceName) {
        ModuleInstance moduleInstance = mCodePush.getModuleInstance(resourceName);
        try {
            if (mCodePush.isUsingTestConfiguration()) {
                try {
                    moduleInstance.updateManager.downloadAndReplaceCurrentBundle(remoteBundleUrl, resourceName);
                } catch (IOException e) {
                    throw new CodePushUnknownException("Unable to replace current bundle", e);
                }
            }
        } catch(CodePushUnknownException | CodePushMalformedDataException e) {
            CodePushUtils.log(e, resourceName);
        }
    }

    /**
     * This method clears CodePush's downloaded updates.
     * It is needed to switch to a different deployment if the current deployment is more recent.
     * Note: we don’t recommend to use this method in scenarios other than that (CodePush will call
     * this method automatically when needed in other cases) as it could lead to unpredictable
     * behavior.
     */
    @ReactMethod
    public void clearUpdates(final String resourceName) {
        CodePushUtils.log("Clearing updates.", resourceName);
        mCodePush.clearUpdates(resourceName);
    }
}
