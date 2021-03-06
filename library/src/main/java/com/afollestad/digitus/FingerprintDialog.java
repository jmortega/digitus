package com.afollestad.digitus;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.content.ContextCompat;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import com.afollestad.materialdialogs.DialogAction;
import com.afollestad.materialdialogs.MaterialDialog;
import com.afollestad.materialdialogs.internal.MDTintHelper;

/**
 * A dialog which uses fingerprint APIs to authenticate the user, and falls back to password
 * authentication if fingerprint is not available.
 */
@SuppressWarnings("ResourceType")
public class FingerprintDialog extends DialogFragment
        implements TextView.OnEditorActionListener, DigitusCallback {

    public interface Callback {
        void onFingerprintDialogAuthenticated();

        void onFingerprintDialogVerifyPassword(FingerprintDialog dialog, String password);
    }

    static final long ERROR_TIMEOUT_MILLIS = 1600;
    static final long SUCCESS_DELAY_MILLIS = 1300;
    static final String TAG = "[DIGITUS_FPDIALOG]";

    private View mFingerprintContent;
    private View mBackupContent;
    private EditText mPassword;
    private CheckBox mUseFingerprintFutureCheckBox;
    private TextView mPasswordDescriptionTextView;
    private TextView mNewFingerprintEnrolledTextView;
    private ImageView mFingerprintIcon;
    private TextView mFingerprintStatus;

    private Stage mStage = Stage.FINGERPRINT;
    private Digitus mDigitus;
    private Callback mCallback;

    public FingerprintDialog() {
    }

    public static <T extends FragmentActivity & Callback> FingerprintDialog show(T context, String keyName, int requestCode) {
        FingerprintDialog dialog = getVisible(context);
        if (dialog != null)
            dialog.dismiss();

        dialog = new FingerprintDialog();
        Bundle args = new Bundle();
        args.putString("key_name", keyName);
        args.putInt("request_code", requestCode);
        args.putBoolean("was_initialized", Digitus.get() != null && Digitus.get().mCallback == context);
        dialog.setArguments(args);
        dialog.show(context.getSupportFragmentManager(), TAG);
        return dialog;
    }

    public static <T extends FragmentActivity> FingerprintDialog getVisible(T context) {
        Fragment frag = context.getSupportFragmentManager().findFragmentByTag(TAG);
        if (frag != null && frag instanceof FingerprintDialog)
            return (FingerprintDialog) frag;
        return null;
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putSerializable("stage", mStage);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        if (getArguments() == null || !getArguments().containsKey("key_name"))
            throw new IllegalStateException("FingerprintDialog must be shown with show(Activity, String, int).");
        else if (savedInstanceState != null)
            mStage = (Stage) savedInstanceState.getSerializable("stage");

        MaterialDialog dialog = new MaterialDialog.Builder(getActivity())
                .title(R.string.sign_in)
                .customView(R.layout.fingerprint_dialog_container, false)
                .positiveText(android.R.string.cancel)
                .negativeText(R.string.use_password)
                .autoDismiss(false)
                .onPositive(new MaterialDialog.SingleButtonCallback() {
                    @Override
                    public void onClick(@NonNull MaterialDialog materialDialog, @NonNull DialogAction dialogAction) {
                        materialDialog.dismiss();
                    }
                })
                .onNegative(new MaterialDialog.SingleButtonCallback() {
                    @Override
                    public void onClick(@NonNull MaterialDialog materialDialog, @NonNull DialogAction dialogAction) {
                        if (mStage == Stage.FINGERPRINT) {
                            goToBackup(materialDialog);
                        } else {
                            verifyPassword();
                        }
                    }
                })
                .build();

        final View v = dialog.getCustomView();
        assert v != null;
        mFingerprintContent = v.findViewById(R.id.fingerprint_container);
        mBackupContent = v.findViewById(R.id.backup_container);
        mPassword = (EditText) v.findViewById(R.id.password);
        mPassword.setOnEditorActionListener(this);
        mPasswordDescriptionTextView = (TextView) v.findViewById(R.id.password_description);
        mUseFingerprintFutureCheckBox = (CheckBox) v.findViewById(R.id.use_fingerprint_in_future_check);
        mNewFingerprintEnrolledTextView = (TextView) v.findViewById(R.id.new_fingerprint_enrolled_description);
        mFingerprintIcon = (ImageView) v.findViewById(R.id.fingerprint_icon);
        mFingerprintStatus = (TextView) v.findViewById(R.id.fingerprint_status);
        mFingerprintStatus.setText(R.string.initializing);

        return dialog;
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        updateStage(null);
    }

    @Override
    public void onResume() {
        super.onResume();
        mDigitus = Digitus.init(getActivity(),
                getArguments().getString("key_name", ""),
                getArguments().getInt("request_code", -1),
                FingerprintDialog.this);
    }

    @Override
    public void onPause() {
        super.onPause();
        if (Digitus.get() != null)
            Digitus.get().stopListening();
    }

    @Override
    public void onCancel(DialogInterface dialog) {
        super.onCancel(dialog);
        redirectToActivity();
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
        super.onDismiss(dialog);
        redirectToActivity();
    }

    private void redirectToActivity() {
        Digitus.deinit();
        if (getActivity() != null &&
                getActivity() instanceof DigitusCallback &&
                getArguments().getBoolean("was_initialized", false)) {
            Digitus.init(getActivity(),
                    getArguments().getString("key_name", ""),
                    getArguments().getInt("request_code", -1),
                    (DigitusCallback) getActivity());
        }
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        if (!(activity instanceof Callback)) {
            Digitus.deinit();
            throw new IllegalStateException("Activities showing a FingerprintDialog must implement FingerprintDialog.Callback.");
        }
        mCallback = (Callback) activity;
    }

    /**
     * Switches to backup (password) screen. This either can happen when fingerprint is not
     * available or the user chooses to use the password authentication method by pressing the
     * button. This can also happen when the user had too many fingerprint attempts.
     */
    private void goToBackup(MaterialDialog dialog) {
        mStage = Stage.PASSWORD;
        updateStage(dialog);
        mPassword.requestFocus();
        // Show the keyboard.
        mPassword.postDelayed(mShowKeyboardRunnable, 500);
        // Fingerprint is not used anymore. Stop listening for it.
        mDigitus.stopListening();
    }

    private void toggleButtonsEnabled(boolean enabled) {
        MaterialDialog dialog = (MaterialDialog) getDialog();
        dialog.getActionButton(DialogAction.POSITIVE).setEnabled(enabled);
        dialog.getActionButton(DialogAction.NEGATIVE).setEnabled(enabled);
    }

    private void verifyPassword() {
        toggleButtonsEnabled(false);
        mCallback.onFingerprintDialogVerifyPassword(this, mPassword.getText().toString());
    }

    public void notifyPasswordValidation(boolean valid) {
        final MaterialDialog dialog = (MaterialDialog) getDialog();
        final View positive = dialog.getActionButton(DialogAction.POSITIVE);
        final View negative = dialog.getActionButton(DialogAction.NEGATIVE);
        toggleButtonsEnabled(true);

        if (valid) {
            if (mStage == Stage.NEW_FINGERPRINT_ENROLLED &&
                    mUseFingerprintFutureCheckBox.isChecked()) {
                // Re-create the key so that fingerprints including new ones are validated.
                Digitus.get().recreateKey();
                mStage = Stage.FINGERPRINT;
            }
            mPassword.setText("");
            mCallback.onFingerprintDialogAuthenticated();
            dismiss();
        } else {
            mPasswordDescriptionTextView.setText(R.string.password_not_recognized);
            final int red = ContextCompat.getColor(getActivity(), R.color.material_red_500);
            MDTintHelper.setTint(mPassword, red);
            ((TextView) positive).setTextColor(red);
            ((TextView) negative).setTextColor(red);
        }
    }

    private final Runnable mShowKeyboardRunnable = new Runnable() {
        @Override
        public void run() {
            if (mDigitus != null)
                mDigitus.mInputMethodManager.showSoftInput(mPassword, 0);
        }
    };

    private void updateStage(@Nullable MaterialDialog dialog) {
        if (dialog == null)
            dialog = (MaterialDialog) getDialog();
        if (dialog == null) return;
        switch (mStage) {
            case FINGERPRINT:
                dialog.setActionButton(DialogAction.POSITIVE, android.R.string.cancel);
                dialog.setActionButton(DialogAction.NEGATIVE, R.string.use_password);
                mFingerprintContent.setVisibility(View.VISIBLE);
                mBackupContent.setVisibility(View.GONE);
                break;
            case NEW_FINGERPRINT_ENROLLED:
                // Intentional fall through
            case PASSWORD:
                dialog.setActionButton(DialogAction.POSITIVE, android.R.string.cancel);
                dialog.setActionButton(DialogAction.NEGATIVE, android.R.string.ok);
                mFingerprintContent.setVisibility(View.GONE);
                mBackupContent.setVisibility(View.VISIBLE);
                if (mStage == Stage.NEW_FINGERPRINT_ENROLLED) {
                    mPasswordDescriptionTextView.setVisibility(View.GONE);
                    mNewFingerprintEnrolledTextView.setVisibility(View.VISIBLE);
                    mUseFingerprintFutureCheckBox.setVisibility(View.VISIBLE);
                }
                break;
        }
    }

    @Override
    public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
        if (actionId == EditorInfo.IME_ACTION_GO) {
            verifyPassword();
            return true;
        }
        return false;
    }

    /**
     * Enumeration to indicate which authentication method the user is trying to authenticate with.
     */
    public enum Stage {
        FINGERPRINT,
        NEW_FINGERPRINT_ENROLLED,
        PASSWORD
    }

    private void showError(CharSequence error) {
        if (getActivity() == null) return;
        mFingerprintIcon.setImageResource(R.drawable.ic_fingerprint_error);
        mFingerprintStatus.setText(error);
        mFingerprintStatus.setTextColor(ContextCompat.getColor(getActivity(), R.color.warning_color));
        mFingerprintStatus.removeCallbacks(mResetErrorTextRunnable);
        mFingerprintStatus.postDelayed(mResetErrorTextRunnable, ERROR_TIMEOUT_MILLIS);
    }

    Runnable mResetErrorTextRunnable = new Runnable() {
        @Override
        public void run() {
            if (getActivity() == null) return;
            mFingerprintStatus.setTextColor(ContextCompat.getColor(getActivity(), R.color.hint_color));
            mFingerprintStatus.setText(getResources().getString(R.string.fingerprint_hint));
            mFingerprintIcon.setImageResource(R.drawable.ic_fp_40px);
        }
    };

    // Digitus callbacks

    @Override
    public void onDigitusReady(Digitus digitus) {
        digitus.startListening();
    }

    @Override
    public void onDigitusListening(boolean newFingerprint) {
        mFingerprintStatus.setText(R.string.fingerprint_hint);
        if (newFingerprint)
            mStage = Stage.NEW_FINGERPRINT_ENROLLED;
        updateStage(null);
    }

    @Override
    public void onDigitusAuthenticated(Digitus digitus) {
        toggleButtonsEnabled(false);
        mFingerprintStatus.removeCallbacks(mResetErrorTextRunnable);
        mFingerprintIcon.setImageResource(R.drawable.ic_fingerprint_success);
        mFingerprintStatus.setTextColor(ContextCompat.getColor(getActivity(), R.color.success_color));
        mFingerprintStatus.setText(getResources().getString(R.string.fingerprint_success));
        mFingerprintIcon.postDelayed(new Runnable() {
            @Override
            public void run() {
                mCallback.onFingerprintDialogAuthenticated();
                dismiss();
            }
        }, SUCCESS_DELAY_MILLIS);
    }

    @Override
    public void onDigitusError(Digitus digitus, DigitusErrorType type, Exception e) {
        switch (type) {
            case FINGERPRINTS_UNSUPPORTED:
                goToBackup(null);
                break;
            case UNRECOVERABLE_ERROR:
            case PERMISSION_DENIED:
                showError(e.getMessage());
                mFingerprintIcon.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        goToBackup(null);
                    }
                }, ERROR_TIMEOUT_MILLIS);
                break;
            case REGISTRATION_NEEDED:
                mPasswordDescriptionTextView.setText(R.string.no_fingerprints_registered);
                goToBackup(null);
                break;
            case HELP_ERROR:
                showError(e.getMessage());
                break;
            case FINGERPRINT_NOT_RECOGNIZED:
                showError(getResources().getString(R.string.fingerprint_not_recognized));
                break;
        }
    }
}