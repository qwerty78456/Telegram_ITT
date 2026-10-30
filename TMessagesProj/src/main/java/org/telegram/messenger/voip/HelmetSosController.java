package org.telegram.messenger.voip;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_phone;
import org.telegram.ui.Components.voip.VoIPHelper;
import org.telegram.utils.settings.SharedSettings;

/**
 * Handles the smart helmet "SOS" hardware button.
 * <p>
 * The vendor-modified system_server sends the implicit broadcasts {@link #ACTION_SOS_DOWN} on press and
 * {@link #ACTION_SOS_UP} on release. A press answers a ringing 1:1 call, otherwise (when no call is active)
 * calls the contact configured in the SOS settings. Holding the button for {@link #HANG_UP_HOLD_MS} during an
 * active call hangs it up; a short press during a call does nothing.
 * <p>
 * All state is touched on the UI thread only (the receiver is registered without a scheduler, so onReceive runs
 * on the main looper).
 */
public final class HelmetSosController {

    public static final String ACTION_SOS_DOWN = "rd.sdk.action.F11_DOWN6";
    public static final String ACTION_SOS_UP = "rd.sdk.action.F11_UP6";

    // Only senders holding INJECT_EVENTS may deliver the SOS broadcasts to us. INJECT_EVENTS is a signature
    // permission, so no third-party app can hold it and spoof the button to silently place or answer calls.
    // The real broadcast still arrives: it comes from system_server (uid 1000), and system/root uids pass every
    // permission check in ActivityManager (adb shell also holds INJECT_EVENTS, which is handy for testing).
    private static final String SOS_SENDER_PERMISSION = "android.permission.INJECT_EVENTS";

    private static final long DOWN_DEBOUNCE_MS = 800;
    private static final long HANG_UP_HOLD_MS = 2000;
    private static final long ANSWER_POLL_INTERVAL_MS = 200;
    private static final long ANSWER_POLL_TIMEOUT_MS = 6000;
    private static final long DIAL_GUARD_MS = 2000;
    private static final long USER_LOAD_GUARD_MS = 10000;
    private static final int ERROR_TONE_MS = 300;

    private static boolean registered;
    private static long lastDownTime;
    private static long lastDialTime;
    private static long loadingUserSince;
    private static Runnable pendingHangUp;
    private static Runnable pendingAnswer;
    private static long pendingAnswerDeadline;

    private static final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            ApplicationLoader.postInitApplication();
            if (intent == null) {
                return;
            }
            final String action = intent.getAction();
            try {
                if (ACTION_SOS_DOWN.equals(action)) {
                    onSosDown();
                } else if (ACTION_SOS_UP.equals(action)) {
                    onSosUp();
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
    };

    private HelmetSosController() {
    }

    @SuppressLint({"UnspecifiedRegisterReceiverFlag", "InlinedApi"})
    public static synchronized void init(Context appContext) {
        if (registered || appContext == null) {
            return;
        }
        final IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_SOS_DOWN);
        filter.addAction(ACTION_SOS_UP);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                appContext.registerReceiver(receiver, filter, SOS_SENDER_PERMISSION, null, Context.RECEIVER_EXPORTED);
            } else {
                appContext.registerReceiver(receiver, filter, SOS_SENDER_PERMISSION, null);
            }
            registered = true;
            FileLog.d("HelmetSos: receiver registered for " + ACTION_SOS_DOWN + " / " + ACTION_SOS_UP);
        } catch (Throwable e) {
            FileLog.e("HelmetSos: failed to register receiver", e);
        }
    }

    private static void onSosDown() {
        if (!SharedSettings.helmetSosEnabled.get()) {
            FileLog.d("HelmetSos: DOWN ignored, SOS button is disabled");
            return;
        }
        final long now = SystemClock.elapsedRealtime();
        if (lastDownTime != 0 && now - lastDownTime < DOWN_DEBOUNCE_MS) {
            FileLog.d("HelmetSos: DOWN ignored, debounce (" + (now - lastDownTime) + " ms since last)");
            return;
        }
        lastDownTime = now;

        final VoIPService svc = VoIPService.getSharedInstance();
        if (svc != null) {
            final int state = svc.getCallState();
            final boolean incomingPrivate = !svc.isOutgoing() && svc.groupCall == null && !svc.isConference();
            FileLog.d("HelmetSos: DOWN with active service, state=" + state + " outgoing=" + svc.isOutgoing() + " group=" + (svc.groupCall != null));
            if (incomingPrivate && state == VoIPService.STATE_WAITING_INCOMING) {
                cancelPendingHangUp();
                answerRingingCall(svc);
            } else if (incomingPrivate && state == 0) {
                cancelPendingHangUp();
                FileLog.d("HelmetSos: incoming call not ringing yet, will answer when ready");
                startPendingAnswer();
            } else if (state != VoIPService.STATE_ENDED && state != VoIPService.STATE_HANGING_UP) {
                if (pendingHangUp != null) {
                    // no UP since the previous DOWN: the button is still held, keep the original timer
                    FileLog.d("HelmetSos: hang-up already pending, keeping the original hold timer");
                } else {
                    scheduleHangUp(svc);
                }
            } else {
                cancelPendingHangUp();
                FileLog.d("HelmetSos: call is already ending, ignoring");
            }
            return;
        }
        cancelPendingHangUp();
        if (tryAnswerPreNotification()) {
            return;
        }
        if (VoIPService.callIShouldHavePutIntoIntent != null) {
            FileLog.d("HelmetSos: incoming call service is starting, will answer when ready");
            startPendingAnswer();
            return;
        }
        final VoIPServiceState sharedState = VoIPService.getSharedState();
        if (sharedState != null && sharedState.getCallState() == VoIPService.STATE_WAITING_INCOMING) {
            FileLog.d("HelmetSos: a conference invite is ringing, ignoring (not placing an outgoing call)");
            return;
        }
        if (pendingAnswer != null) {
            FileLog.d("HelmetSos: still waiting to answer an incoming call, ignoring");
            return;
        }
        startSosCall();
    }

    private static void onSosUp() {
        if (pendingHangUp != null) {
            FileLog.d("HelmetSos: UP before hold timeout, hang-up cancelled");
            cancelPendingHangUp();
        }
    }

    // region answer

    private static void answerRingingCall(VoIPService svc) {
        cancelPendingAnswer();
        FileLog.d("HelmetSos: answering incoming call " + svc.getCallID());
        warnIfNoMicForAnswer();
        final TL_phone.PhoneCall call = svc.getPrivateCall();
        if (call != null && call.video && hasPermission(Manifest.permission.RECORD_AUDIO) && !hasPermission(Manifest.permission.CAMERA)) {
            // the notification answer path would only open VoIPPermissionActivity for the camera, which nobody can
            // tap on the headless helmet, so answer directly; the call then runs without our video
            FileLog.d("HelmetSos: video call but CAMERA not granted, answering without video");
            fail(R.string.HelmetSosNoCamera, "answering a video call without camera permission");
            try {
                svc.acceptIncomingCall();
            } catch (Throwable e) {
                FileLog.e("HelmetSos: failed to answer call", e);
            }
            return;
        }
        try {
            svc.handleNotificationAction(new Intent(ApplicationLoader.applicationContext.getPackageName() + ".ANSWER_CALL"));
        } catch (Throwable e) {
            FileLog.e("HelmetSos: failed to answer call", e);
        }
    }

    // Android 13+ shows incoming calls through VoIPPreNotificationService before VoIPService is started.
    private static boolean tryAnswerPreNotification() {
        if (Build.VERSION.SDK_INT < 33) {
            return false;
        }
        final VoIPPreNotificationService.State state = VoIPPreNotificationService.getState();
        if (state == null || state.getCallState() != VoIPService.STATE_WAITING_INCOMING || VoIPPreNotificationService.pendingVoIP == null) {
            return false;
        }
        cancelPendingAnswer();
        FileLog.d("HelmetSos: answering pre-notified incoming call");
        warnIfNoMicForAnswer();
        try {
            state.acceptIncomingCall();
        } catch (Throwable e) {
            FileLog.e("HelmetSos: failed to answer pre-notified call", e);
        }
        return true;
    }

    private static void warnIfNoMicForAnswer() {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            // the answer flow will still open the permission screen, as a normal answer would
            fail(R.string.HelmetSosNoMic, "answering without microphone permission");
        }
    }

    private static void startPendingAnswer() {
        pendingAnswerDeadline = SystemClock.elapsedRealtime() + ANSWER_POLL_TIMEOUT_MS;
        schedulePendingAnswerPoll();
    }

    private static void schedulePendingAnswerPoll() {
        cancelPendingAnswer();
        pendingAnswer = new Runnable() {
            @Override
            public void run() {
                if (pendingAnswer != this) {
                    return;
                }
                pendingAnswer = null;
                pollPendingAnswer();
            }
        };
        AndroidUtilities.runOnUIThread(pendingAnswer, ANSWER_POLL_INTERVAL_MS);
    }

    private static void pollPendingAnswer() {
        final VoIPService svc = VoIPService.getSharedInstance();
        if (svc != null) {
            if (svc.isOutgoing() || svc.groupCall != null || svc.isConference()) {
                FileLog.d("HelmetSos: pending answer stopped, active call is not an incoming 1:1 call");
                return;
            }
            final int state = svc.getCallState();
            if (state == VoIPService.STATE_WAITING_INCOMING) {
                answerRingingCall(svc);
                return;
            }
            if (state != 0) {
                FileLog.d("HelmetSos: pending answer stopped, call state is " + state);
                return;
            }
        } else {
            if (tryAnswerPreNotification()) {
                return;
            }
            if (VoIPService.callIShouldHavePutIntoIntent == null) {
                FileLog.d("HelmetSos: pending answer stopped, incoming call disappeared");
                return;
            }
        }
        if (SystemClock.elapsedRealtime() >= pendingAnswerDeadline) {
            FileLog.d("HelmetSos: pending answer timed out, call never started ringing");
            return;
        }
        schedulePendingAnswerPoll();
    }

    private static void cancelPendingAnswer() {
        if (pendingAnswer != null) {
            AndroidUtilities.cancelRunOnUIThread(pendingAnswer);
            pendingAnswer = null;
        }
    }

    // endregion

    // region hang up

    private static void scheduleHangUp(VoIPService svc) {
        cancelPendingHangUp();
        final VoIPService target = svc;
        final long callId = svc.getCallID();
        FileLog.d("HelmetSos: hold SOS for " + HANG_UP_HOLD_MS + " ms to hang up call " + callId);
        pendingHangUp = new Runnable() {
            @Override
            public void run() {
                if (pendingHangUp != this) {
                    return;
                }
                pendingHangUp = null;
                final VoIPService current = VoIPService.getSharedInstance();
                // every call runs in its own VoIPService instance, so comparing instances is enough; the call id
                // is still 0 for an outgoing call until phone.requestCall returns, so it can't be compared here
                if (current == null || current != target) {
                    FileLog.d("HelmetSos: hang-up skipped, the call is gone or changed");
                    return;
                }
                final int state = current.getCallState();
                if (state == VoIPService.STATE_ENDED || state == VoIPService.STATE_HANGING_UP) {
                    FileLog.d("HelmetSos: hang-up skipped, call is already ending");
                    return;
                }
                FileLog.d("HelmetSos: SOS held, hanging up call " + callId + " in state " + state);
                try {
                    current.hangUp();
                } catch (Throwable e) {
                    FileLog.e("HelmetSos: failed to hang up", e);
                }
            }
        };
        AndroidUtilities.runOnUIThread(pendingHangUp, HANG_UP_HOLD_MS);
    }

    private static void cancelPendingHangUp() {
        if (pendingHangUp != null) {
            AndroidUtilities.cancelRunOnUIThread(pendingHangUp);
            pendingHangUp = null;
        }
    }

    // endregion

    // region outgoing call

    private static void startSosCall() {
        final long now = SystemClock.elapsedRealtime();
        final boolean loadingUser = loadingUserSince != 0 && now - loadingUserSince < USER_LOAD_GUARD_MS;
        if (loadingUser || (lastDialTime != 0 && now - lastDialTime < DIAL_GUARD_MS)) {
            FileLog.d("HelmetSos: an SOS call is already being started, ignoring");
            return;
        }
        final long userId = SharedSettings.helmetSosUserId.get();
        if (userId == 0) {
            fail(R.string.HelmetSosNoContact, "no contact configured");
            return;
        }
        final int account = resolveAccount();
        if (account < 0) {
            fail(R.string.HelmetSosNoContact, "the account that picked the contact is not logged in");
            return;
        }
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            fail(R.string.HelmetSosNoMic, "RECORD_AUDIO not granted");
            return;
        }
        final ConnectionsManager connectionsManager = ConnectionsManager.getInstance(account);
        final int connectionState = connectionsManager.getConnectionState();
        if (connectionState == ConnectionsManager.ConnectionStateWaitingForNetwork) {
            fail(R.string.HelmetSosOffline, "account " + account + " has no network, state=" + connectionState);
            return;
        }
        if (connectionState != ConnectionsManager.ConnectionStateConnected && connectionState != ConnectionsManager.ConnectionStateUpdating) {
            // while the app is in the background the network is paused and reports "connecting";
            // VoIPService resumes the network itself and its requests wait in the queue until connected
            FileLog.d("HelmetSos: account " + account + " not connected yet (state=" + connectionState + "), waking the network and dialing");
            connectionsManager.resumeNetworkMaybe();
        }
        final TLRPC.User user = MessagesController.getInstance(account).getUser(userId);
        if (user != null) {
            placeSosCall(account, user);
            return;
        }
        FileLog.d("HelmetSos: contact " + userId + " not in memory, loading from storage");
        final long loadStartTime = now;
        loadingUserSince = loadStartTime;
        Utilities.globalQueue.postRunnable(() -> {
            TLRPC.User loaded = null;
            try {
                loaded = MessagesStorage.getInstance(account).getUserSync(userId);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            final TLRPC.User result = loaded;
            AndroidUtilities.runOnUIThread(() -> {
                if (loadingUserSince != loadStartTime) {
                    FileLog.d("HelmetSos: stale contact load result ignored");
                    return;
                }
                loadingUserSince = 0;
                if (result == null) {
                    fail(R.string.HelmetSosUserMissing, "contact " + userId + " not found in storage");
                    return;
                }
                MessagesController.getInstance(account).putUser(result, true);
                final TLRPC.User cached = MessagesController.getInstance(account).getUser(userId);
                placeSosCall(account, cached != null ? cached : result);
            });
        });
    }

    private static void placeSosCall(int account, TLRPC.User user) {
        if (!SharedSettings.helmetSosEnabled.get()) {
            FileLog.d("HelmetSos: SOS disabled before dialing, not calling");
            return;
        }
        if (VoIPService.getSharedInstance() != null || VoIPService.callIShouldHavePutIntoIntent != null) {
            FileLog.d("HelmetSos: another call appeared before dialing, not calling");
            return;
        }
        final VoIPServiceState sharedState = VoIPService.getSharedState();
        if (sharedState != null && sharedState.getCallState() == VoIPService.STATE_WAITING_INCOMING) {
            FileLog.d("HelmetSos: an incoming call is ringing, not calling");
            return;
        }
        final long now = SystemClock.elapsedRealtime();
        if (lastDialTime != 0 && now - lastDialTime < DIAL_GUARD_MS) {
            FileLog.d("HelmetSos: an SOS call was just started, not calling again");
            return;
        }
        if (UserObject.isDeleted(user)) {
            fail(R.string.HelmetSosUserMissing, "contact " + user.id + " is deleted");
            return;
        }

        final Context context = ApplicationLoader.applicationContext;
        final boolean wantVideo = SharedSettings.helmetSosVideo.get();
        final boolean video = wantVideo && hasPermission(Manifest.permission.CAMERA);
        if (wantVideo && !video) {
            FileLog.d("HelmetSos: video call requested but CAMERA not granted, starting a voice call");
        }

        // same extras as VoIPHelper.doInitiateCall for a 1:1 call, with the stored account
        final Intent intent = new Intent(context, VoIPService.class);
        intent.putExtra("user_id", user.id);
        intent.putExtra("is_outgoing", true);
        intent.putExtra("start_incall_activity", true);
        intent.putExtra("video_call", video);
        intent.putExtra("can_video_call", video);
        intent.putExtra("account", account);

        lastDialTime = now;
        VoIPHelper.lastCallTime = now;
        FileLog.d("HelmetSos: calling user " + user.id + " from account " + account + " video=" + video);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable e) {
            FileLog.e("HelmetSos: failed to start VoIPService", e);
            lastDialTime = 0;
            fail(R.string.ErrorOccurred, "failed to start VoIPService");
            return;
        }

        String name = ContactsController.formatName(user.first_name, user.last_name);
        if (TextUtils.isEmpty(name)) {
            name = SharedSettings.helmetSosUserName.get();
        }
        if (TextUtils.isEmpty(name)) {
            name = String.valueOf(user.id);
        }
        showToast(LocaleController.formatString(R.string.HelmetSosCalling, name));
    }

    private static int resolveAccount() {
        final long ownerId = SharedSettings.helmetSosOwnerId.get();
        if (ownerId == 0) {
            return -1;
        }
        final int stored = SharedSettings.helmetSosAccount.get();
        if (UserConfig.isValidAccount(stored) && UserConfig.getInstance(stored).getClientUserId() == ownerId) {
            return stored;
        }
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.isValidAccount(a) && UserConfig.getInstance(a).getClientUserId() == ownerId) {
                FileLog.d("HelmetSos: owner account moved from slot " + stored + " to " + a);
                return a;
            }
        }
        return -1;
    }

    // endregion

    // region feedback

    private static boolean hasPermission(String permission) {
        try {
            return ApplicationLoader.applicationContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    private static void fail(int resId, String reason) {
        FileLog.d("HelmetSos: failure, " + reason);
        String text = null;
        try {
            text = LocaleController.getString(resId);
        } catch (Throwable e) {
            FileLog.e(e);
        }
        showToast(text);
        playErrorTone();
    }

    private static void showToast(CharSequence text) {
        if (TextUtils.isEmpty(text)) {
            return;
        }
        try {
            Toast.makeText(ApplicationLoader.applicationContext, text, Toast.LENGTH_LONG).show();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static void playErrorTone() {
        ToneGenerator created = null;
        try {
            created = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, ToneGenerator.MAX_VOLUME);
            created.startTone(ToneGenerator.TONE_PROP_NACK, ERROR_TONE_MS);
        } catch (Throwable e) {
            FileLog.e(e);
        }
        if (created == null) {
            return;
        }
        final ToneGenerator toneGenerator = created;
        AndroidUtilities.runOnUIThread(() -> {
            try {
                toneGenerator.release();
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }, ERROR_TONE_MS + 200);
    }

    // endregion
}
