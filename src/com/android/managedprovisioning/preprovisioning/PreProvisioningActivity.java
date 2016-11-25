/*
 * Copyright 2016, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.managedprovisioning.preprovisioning;

import android.annotation.NonNull;
import android.app.Activity;
import android.app.DialogFragment;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.SoundEffectConstants;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.managedprovisioning.R;
import com.android.managedprovisioning.common.DialogBuilder;
import com.android.managedprovisioning.common.LogoUtils;
import com.android.managedprovisioning.common.MdmPackageInfo;
import com.android.managedprovisioning.common.ProvisionLogger;
import com.android.managedprovisioning.common.SetupLayoutActivity;
import com.android.managedprovisioning.common.SimpleDialog;
import com.android.managedprovisioning.model.ProvisioningParams;
import com.android.managedprovisioning.preprovisioning.terms.TermsActivity;
import com.android.managedprovisioning.provisioning.ProvisioningActivity;
import com.android.setupwizardlib.GlifLayout;

public class PreProvisioningActivity extends SetupLayoutActivity
        implements UserConsentDialog.ConsentCallback, SimpleDialog.SimpleDialogListener,
        DeleteManagedProfileDialog.DeleteManagedProfileCallback, PreProvisioningController.Ui {

    @VisibleForTesting
    protected static final int ENCRYPT_DEVICE_REQUEST_CODE = 1;
    @VisibleForTesting
    protected static final int PROVISIONING_REQUEST_CODE = 2;
    @VisibleForTesting
    protected static final int WIFI_REQUEST_CODE = 3;
    @VisibleForTesting
    protected static final int CHANGE_LAUNCHER_REQUEST_CODE = 4;

    // Note: must match the constant defined in HomeSettings
    private static final String EXTRA_SUPPORT_MANAGED_PROFILES = "support_managed_profiles";

    private static final String PRE_PROVISIONING_ERROR_AND_CLOSE_DIALOG =
            "PreProvisioningErrorAndCloseDialog";
    private static final String PRE_PROVISIONING_BACK_PRESSED_DIALOG =
            "PreProvisioningBackPressedDialog";
    private static final String PRE_PROVISIONING_CANCELLED_CONSENT_DIALOG =
            "PreProvisioningCancelledConsentDialog";
    private static final String PRE_PROVISIONING_CURRENT_LAUNCHER_INVALID_DIALOG =
            "PreProvisioningCurrentLauncherInvalidDialog";
    private static final String PRE_PROVISIONING_USER_CONSENT_DIALOG =
            "PreProvisioningUserConsentDialog";
    private static final String PRE_PROVISIONING_DELETE_MANAGED_PROFILE_DIALOG =
            "PreProvisioningDeleteManagedProfileDialog";

    private PreProvisioningController mController;
    private BenefitsAnimation mBenefitsAnimation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mController = new PreProvisioningController(this, this);
        mController.initiateProvisioning(getIntent(), getCallingPackage());
    }

    @Override
    public void finish() {
        // The user has backed out of provisioning, so we perform the necessary clean up steps.
        LogoUtils.cleanUp(this);
        EncryptionController.getInstance(this).cancelEncryptionReminder();
        super.finish();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == ENCRYPT_DEVICE_REQUEST_CODE) {
            if (resultCode == RESULT_CANCELED) {
                ProvisionLogger.loge("User canceled device encryption.");
                setResult(Activity.RESULT_CANCELED);
                finish();
            }
        } else if (requestCode == PROVISIONING_REQUEST_CODE) {
            setResult(resultCode);
            finish();
        } else if (requestCode == CHANGE_LAUNCHER_REQUEST_CODE) {
            if (!mUtils.currentLauncherSupportsManagedProfiles(this)) {
                showCurrentLauncherInvalid();
            } else {
                mController.stopTimeLogger();
                startProfileOwnerProvisioning(mController.getParams());
            }
        } else if (requestCode == WIFI_REQUEST_CODE) {
            if (resultCode == RESULT_CANCELED) {
                ProvisionLogger.loge("User canceled wifi picking.");
                setResult(RESULT_CANCELED);
                finish();
            } else if (resultCode == RESULT_OK) {
                ProvisionLogger.logd("Wifi request result is OK");
                if (mUtils.isConnectedToWifi(this)) {
                    mController.askForConsentOrStartDeviceOwnerProvisioning();
                } else {
                    requestWifiPick();
                }
            }
        } else {
            ProvisionLogger.logw("Unknown result code :" + resultCode);
        }
    }

    @Override
    public void showErrorAndClose(int resourceId, String logText) {
        ProvisionLogger.loge(logText);

        SimpleDialog.Builder dialogBuilder = new SimpleDialog.Builder()
                .setTitle(R.string.provisioning_error_title)
                .setMessage(resourceId)
                .setCancelable(false)
                .setPositiveButtonMessage(R.string.device_owner_error_ok);
        showDialog(dialogBuilder, PRE_PROVISIONING_ERROR_AND_CLOSE_DIALOG);
    }

    @Override
    public void onNegativeButtonClick(DialogFragment dialog) {
        switch (dialog.getTag()) {
            case PRE_PROVISIONING_CANCELLED_CONSENT_DIALOG:
            case PRE_PROVISIONING_BACK_PRESSED_DIALOG:
                // user chose to continue. Do nothing
                break;
            case PRE_PROVISIONING_CURRENT_LAUNCHER_INVALID_DIALOG:
                dialog.dismiss();
                setResult(Activity.RESULT_CANCELED);
                finish();
                break;
            default:
                SimpleDialog.throwButtonClickHandlerNotImplemented(dialog);
        }
    }

    @Override
    public void onPositiveButtonClick(DialogFragment dialog) {
        switch (dialog.getTag()) {
            case PRE_PROVISIONING_ERROR_AND_CLOSE_DIALOG:
            case PRE_PROVISIONING_BACK_PRESSED_DIALOG:
                // Close activity
                setResult(Activity.RESULT_CANCELED);
                // TODO: Move logging to close button, if we finish provisioning there.
                mController.logPreProvisioningCancelled();
                finish();
                break;
            case PRE_PROVISIONING_CANCELLED_CONSENT_DIALOG:
                mUtils.sendFactoryResetBroadcast(this, "Device owner setup cancelled");
                break;
            case PRE_PROVISIONING_CURRENT_LAUNCHER_INVALID_DIALOG:
                requestLauncherPick();
                break;
            default:
                SimpleDialog.throwButtonClickHandlerNotImplemented(dialog);
        }
    }

    @Override
    public void requestEncryption(ProvisioningParams params) {
        Intent encryptIntent = new Intent(this, EncryptDeviceActivity.class);
        encryptIntent.putExtra(ProvisioningParams.EXTRA_PROVISIONING_PARAMS, params);
        startActivityForResult(encryptIntent, ENCRYPT_DEVICE_REQUEST_CODE);
    }

    @Override
    public void requestWifiPick() {
        startActivityForResult(mUtils.getWifiPickIntent(), WIFI_REQUEST_CODE);
    }

    @Override
    public void showCurrentLauncherInvalid() {
        SimpleDialog.Builder dialogBuilder = new SimpleDialog.Builder()
                .setCancelable(false)
                .setMessage(R.string.managed_provisioning_not_supported_by_launcher)
                .setNegativeButtonMessage(R.string.cancel_provisioning)
                .setPositiveButtonMessage(R.string.pick_launcher);
        showDialog(dialogBuilder, PRE_PROVISIONING_CURRENT_LAUNCHER_INVALID_DIALOG);
    }

    private void requestLauncherPick() {
        Intent changeLauncherIntent = new Intent(Settings.ACTION_HOME_SETTINGS);
        changeLauncherIntent.putExtra(EXTRA_SUPPORT_MANAGED_PROFILES, true);
        startActivityForResult(changeLauncherIntent, CHANGE_LAUNCHER_REQUEST_CODE);
    }

    @Override
    public void startDeviceOwnerProvisioning(int userId, ProvisioningParams params) {
        Intent intent = new Intent(this, ProvisioningActivity.class);
        intent.putExtra(ProvisioningParams.EXTRA_PROVISIONING_PARAMS, params);
        startActivityForResultAsUser(intent, PROVISIONING_REQUEST_CODE, new UserHandle(userId));
        // Set cross-fade transition animation into the interstitial progress activity.
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @Override
    public void startProfileOwnerProvisioning(ProvisioningParams params) {
        Intent intent = new Intent(this, ProvisioningActivity.class);
        intent.putExtra(ProvisioningParams.EXTRA_PROVISIONING_PARAMS, params);
        startActivityForResult(intent, PROVISIONING_REQUEST_CODE);
        // Set cross-fade transition animation into the interstitial progress activity.
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @Override
    public void initiateUi(int layoutRes, int titleRes, int mainColorRes,
            ProvisioningParams params) {
        // Setup the UI.
        setContentView(layoutRes);

        Button nextButton = (Button) findViewById(R.id.next_button);
        nextButton.setOnClickListener(v -> {
            ProvisionLogger.logi("Next button (next_button) is clicked.");
            mController.afterNavigateNext();
        });

        setMainColor(getColor(mainColorRes));
        setStatusBarIconColor(false /* set to light */);
        setTitle(titleRes);

        if (mController.isProfileOwnerProvisioning()) {
            // set up the cancel button
            Button cancelButton = (Button) findViewById(R.id.close_button);
            cancelButton.setOnClickListener(v -> {
                ProvisionLogger.logi("Close button (close_button) is clicked.");
                PreProvisioningActivity.this.onBackPressed();
            });

            // set up show terms button
            findViewById(R.id.show_terms_button).setOnClickListener(this::onViewTermsClick);

            // show the intro animation
            mBenefitsAnimation = new BenefitsAnimation(this);
        } else {
            GlifLayout layout = (GlifLayout) findViewById(R.id.intro_device_owner);
            layout.setIcon(getDrawable(R.drawable.ic_enterprise_blue_24dp));
            layout.setHeaderText(R.string.set_up_your_device);
            setMdmIconAndLabel(params.inferDeviceAdminPackageName());

            // short terms info text with clickable 'view terms' link
            TextView shortInfoText = (TextView) findViewById(R.id.device_owner_short_info);
            shortInfoText.setText(assembleTermsMessage(R.string.device_owner_short_info,
                    R.string.view_terms, this::onViewTermsClick));
            shortInfoText.setMovementMethod(LinkMovementMethod.getInstance()); // make clicks work
        }
    }

    private void onViewTermsClick(View view) {
        Intent intent = new Intent(PreProvisioningActivity.this, TermsActivity.class);
        intent.putExtra(TermsActivity.IS_PROFILE_OWNER_FLAG,
                mController.isProfileOwnerProvisioning());
        startActivity(intent);
    }

    private Spannable assembleTermsMessage(int messageTextId, int linkTextId,
            View.OnClickListener viewTermsClickListener) {
        String linkText = getString(linkTextId);
        String messageText = getResources().getString(messageTextId, linkText);

        Spannable result = new SpannableString(messageText);
        int start = messageText.indexOf(linkText);
        makeClickable(result, start, start + linkText.length(), viewTermsClickListener);
        return result;
    }

    private void makeClickable(Spannable spannable, int start, int end,
            final View.OnClickListener onClickListener) {
        ClickableSpan clickable = new ClickableSpan() {
            @Override
            public void onClick(View widget) {
                onClickListener.onClick(widget);
                widget.playSoundEffect(SoundEffectConstants.CLICK);
            }

            @Override
            public void updateDrawState(TextPaint ds) {
                super.updateDrawState(ds);
                ds.setUnderlineText(false);
                ds.setColor(getColor(R.color.blue));
            }
        };

        spannable.setSpan(clickable, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void setMdmIconAndLabel(@NonNull String packageName) {
        MdmPackageInfo packageInfo = MdmPackageInfo.createFromPackageName(this, packageName);
        TextView deviceManagerName = (TextView) findViewById(R.id.device_manager_name);
        if (packageInfo != null) {
            String appLabel = packageInfo.appLabel;
            ImageView imageView = (ImageView) findViewById(R.id.device_manager_icon_view);
            imageView.setImageDrawable(packageInfo.packageIcon);
            imageView.setContentDescription(
                    getResources().getString(R.string.mdm_icon_label, appLabel));

            deviceManagerName.setText(appLabel);
        } else {
            // During provisioning from trusted source, the package is not actually on the device
            // yet, so show a default information.
            deviceManagerName.setText(packageName);
        }
    }

    @Override
    public void showUserConsentDialog(ProvisioningParams params,
            boolean isProfileOwnerProvisioning) {
        // TODO: consider a builder being a part of UserConsentDialog
        showDialog(() -> isProfileOwnerProvisioning
                        ? UserConsentDialog.newProfileOwnerInstance()
                        : UserConsentDialog.newDeviceOwnerInstance(!params.startedByTrustedSource),
                PRE_PROVISIONING_USER_CONSENT_DIALOG);
    }

    /**
     * Callback for successful user consent request.
     */
    @Override
    public void onDialogConsent() {
        // Right after user consent, provisioning will be started. To avoid TalkBack reading out
        // the activity title in the time this activity briefly comes back to the foreground, we
        // remove the title.
        setTitle("");

        mController.continueProvisioningAfterUserConsent();
    }

    /**
     * Callback for cancelled user consent request.
     */
    @Override
    public void onDialogCancel() {
        // only show special UI for device owner provisioning.
        if (mController.isProfileOwnerProvisioning()) {
            return;
        }

        // For Nfc provisioning, we automatically show the user consent dialog if applicable.
        // If the user then decides to cancel, we should finish the entire activity and exit.
        // For other cases, dismissing the consent dialog will lead back to PreProvisioningActivity,
        // where we show another dialog asking for user confirmation to cancel the setup and
        // factory reset the device.
        if (mController.getParams().startedByTrustedSource) {
            setResult(RESULT_CANCELED);
            finish();
        } else {
            SimpleDialog.Builder dialogBuilder = new SimpleDialog.Builder()
                    .setTitle(R.string.cancel_setup_and_factory_reset_dialog_title)
                    .setMessage(R.string.cancel_setup_and_factory_reset_dialog_msg)
                    .setNegativeButtonMessage(R.string.cancel_setup_and_factory_reset_dialog_cancel)
                    .setPositiveButtonMessage(R.string.cancel_setup_and_factory_reset_dialog_ok);
            showDialog(dialogBuilder, PRE_PROVISIONING_CANCELLED_CONSENT_DIALOG);
        }
    }

    @Override
    public void showDeleteManagedProfileDialog(ComponentName mdmPackageName, String domainName,
            int userId) {
        // TODO: consider a builder being a part of DeleteManagedProfileDialog
        DialogBuilder dialogBuilder = () -> DeleteManagedProfileDialog.newInstance(userId,
                mdmPackageName, domainName);
        showDialog(dialogBuilder, PRE_PROVISIONING_DELETE_MANAGED_PROFILE_DIALOG);
    }

    /**
     * Callback for user agreeing to remove existing managed profile.
     */
    @Override
    public void onRemoveProfileApproval(int existingManagedProfileUserId) {
        UserManager userManager = (UserManager) getSystemService(Context.USER_SERVICE);
        userManager.removeUser(existingManagedProfileUserId);

        // Check again if it fulfill the condition to skip user consent. If yes, it will start
        // provisioning
        mController.maybeStartProfileOwnerProvisioningIfSkipUserConsent();
    }

    /**
     * Callback for cancelled deletion of existing managed profile.
     */
    @Override
    public void onRemoveProfileCancel() {
        setResult(Activity.RESULT_CANCELED);
        finish();
    }

    /**
     * When the user backs out of creating a managed profile, show a dialog to double check.
     */
    @Override
    public void onBackPressed() {
        if (!mController.isProfileOwnerProvisioning()) {
            super.onBackPressed();
            // TODO: Move logging to close button, if we finish provisioning there.
            mController.logPreProvisioningCancelled();
            return;
        }

        SimpleDialog.Builder dialogBuilder = new SimpleDialog.Builder()
                .setTitle(R.string.work_profile_setup_later_title)
                .setMessage(R.string.work_profile_setup_later_message)
                .setCancelable(false)
                .setPositiveButtonMessage(R.string.work_profile_setup_stop)
                .setNegativeButtonMessage(R.string.work_profile_setup_continue);
        showDialog(dialogBuilder, PRE_PROVISIONING_BACK_PRESSED_DIALOG);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mBenefitsAnimation != null) {
            mBenefitsAnimation.start();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mBenefitsAnimation != null) {
            mBenefitsAnimation.stop();
        }
    }
}