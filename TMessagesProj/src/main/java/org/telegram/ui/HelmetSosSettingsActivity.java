package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.Nullable;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;
import org.telegram.utils.settings.SharedSettings;

import java.util.ArrayList;

public class HelmetSosSettingsActivity extends UniversalFragment {

    private static final int BUTTON_ENABLE = 1;
    private static final int BUTTON_CONTACT = 2;
    private static final int BUTTON_VIDEO = 3;
    private static final int BUTTON_PERMISSIONS = 4;

    private static final int REQUEST_CODE_PERMISSIONS = 1701;
    // a permission result this soon after the request means no dialog was shown
    private static final long INSTANT_DENIAL_MS = 500;

    private boolean permissionsPermanentlyDenied;
    private long lastPermissionRequestTime;

    @Override
    public View createView(Context context) {
        super.createView(context);

        listView.setSections();
        actionBar.setAdaptiveBackground(listView);

        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listView != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.HelmetSos);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        final boolean enabled = SharedSettings.helmetSosEnabled.get();
        final String contactName = getSelectedContactName(currentAccount);

        items.add(UItem.asHeader(getString(R.string.HelmetSosHeader)));
        items.add(UItem.asCheck(BUTTON_ENABLE, getString(R.string.HelmetSosEnable)).setChecked(enabled));
        items.add(UItem.asButton(BUTTON_CONTACT, getString(R.string.HelmetSosContact), contactName != null ? contactName : getString(R.string.HelmetSosContactNotSet)).setEnabled(enabled));
        items.add(UItem.asCheck(BUTTON_VIDEO, getString(R.string.HelmetSosUseVideo)).setChecked(SharedSettings.helmetSosVideo.get()).setEnabled(enabled));
        items.add(UItem.asButton(BUTTON_PERMISSIONS, getString(R.string.HelmetSosPermissions), getString(hasCallPermissions() ? R.string.HelmetSosPermissionsOk : R.string.HelmetSosPermissionsMissing)));
        items.add(UItem.asShadow(getString(R.string.HelmetSosInfo)));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == BUTTON_ENABLE) {
            final boolean enabled = SharedSettings.helmetSosEnabled.toggle();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(enabled);
            }
            listView.adapter.update(true);
        } else if (item.id == BUTTON_CONTACT) {
            if (!SharedSettings.helmetSosEnabled.get()) {
                return;
            }
            openContactPicker();
        } else if (item.id == BUTTON_VIDEO) {
            if (!SharedSettings.helmetSosEnabled.get()) {
                return;
            }
            final boolean video = SharedSettings.helmetSosVideo.toggle();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(video);
            }
            listView.adapter.update(true);
        } else if (item.id == BUTTON_PERMISSIONS) {
            if (!hasCallPermissions()) {
                requestCallPermissions();
            }
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    @Override
    public void onRequestPermissionsResultFragment(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != REQUEST_CODE_PERMISSIONS) {
            return;
        }
        permissionsPermanentlyDenied = false;
        final Activity activity = getParentActivity();
        if (activity != null && permissions != null && grantResults != null) {
            for (int i = 0, n = Math.min(permissions.length, grantResults.length); i < n; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED && !activity.shouldShowRequestPermissionRationale(permissions[i])) {
                    permissionsPermanentlyDenied = true;
                }
            }
        }
        if (permissionsPermanentlyDenied && SystemClock.elapsedRealtime() - lastPermissionRequestTime < INSTANT_DENIAL_MS) {
            // denied without the system ever showing a dialog: the permission was already set to
            // "don't ask again", so go straight to the app's settings page instead of waiting for another tap
            openAppSettings(activity);
        }
        if (listView != null) {
            listView.adapter.update(true);
        }
    }

    private void openContactPicker() {
        final Bundle args = new Bundle();
        args.putBoolean("destroyAfterSelect", true);
        args.putBoolean("returnAsResult", true);
        args.putBoolean("onlyUsers", true);
        args.putBoolean("needPhonebook", false);
        args.putBoolean("allowSelf", false);
        args.putBoolean("allowBots", false);
        final ContactsActivity fragment = new ContactsActivity(args);
        fragment.setDelegate((user, param, activity) -> onContactSelected(user));
        presentFragment(fragment);
    }

    private void onContactSelected(TLRPC.User user) {
        if (user == null || user.id == 0 || user.bot || UserObject.isUserSelf(user) || UserObject.isDeleted(user)) {
            return;
        }
        final long selfId = getUserConfig().getClientUserId();
        if (selfId == 0 || user.id == selfId) {
            return;
        }
        SharedSettings.helmetSosAccount.set(currentAccount);
        SharedSettings.helmetSosOwnerId.set(selfId);
        SharedSettings.helmetSosUserName.set(UserObject.getUserName(user));
        SharedSettings.helmetSosUserId.set(user.id);
        FileLog.d("helmet sos: contact set to " + user.id + " (account " + currentAccount + ")");
        if (listView != null) {
            listView.adapter.update(true);
        }
    }

    private void requestCallPermissions() {
        final Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        if (permissionsPermanentlyDenied) {
            openAppSettings(activity);
            return;
        }
        try {
            lastPermissionRequestTime = SystemClock.elapsedRealtime();
            activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA}, REQUEST_CODE_PERMISSIONS);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static void openAppSettings(Activity activity) {
        if (activity == null) {
            return;
        }
        try {
            final Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + ApplicationLoader.applicationContext.getPackageName()));
            activity.startActivity(intent);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static boolean hasCallPermissions() {
        final Context context = ApplicationLoader.applicationContext;
        return context != null
            && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            && context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Display name of the contact chosen for the SOS button, or null when none is set.
     * Uses the live user object only when the stored account slot and owner match {@code account},
     * otherwise falls back to the cached name.
     */
    @Nullable
    public static String getSelectedContactName(int account) {
        final long userId = SharedSettings.helmetSosUserId.get();
        if (userId == 0) {
            return null;
        }
        if (account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT && SharedSettings.helmetSosAccount.get() == account) {
            final UserConfig userConfig = UserConfig.getInstance(account);
            final long ownerId = SharedSettings.helmetSosOwnerId.get();
            if (ownerId != 0 && userConfig.isClientActivated() && ownerId == userConfig.getClientUserId()) {
                final TLRPC.User user = MessagesController.getInstance(account).getUser(userId);
                if (user != null && !UserObject.isDeleted(user)) {
                    final String name = UserObject.getUserName(user);
                    if (!TextUtils.isEmpty(name)) {
                        return name;
                    }
                }
            }
        }
        if (!isOwnerLoggedIn()) {
            // the SOS button can't call this contact any more (HelmetSosController resolves the same way)
            return null;
        }
        final String cached = SharedSettings.helmetSosUserName.get();
        return TextUtils.isEmpty(cached) ? null : cached;
    }

    private static boolean isOwnerLoggedIn() {
        final long ownerId = SharedSettings.helmetSosOwnerId.get();
        if (ownerId == 0) {
            return false;
        }
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.isValidAccount(a) && UserConfig.getInstance(a).getClientUserId() == ownerId) {
                return true;
            }
        }
        return false;
    }
}
