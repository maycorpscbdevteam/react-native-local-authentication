package com.bebnev.RNLocalAuthentication;

import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.fragment.app.FragmentActivity;
import androidx.biometric.BiometricPrompt;
import androidx.biometric.BiometricManager;

import static androidx.biometric.BiometricConstants.ERROR_NEGATIVE_BUTTON;
import static androidx.biometric.BiometricConstants.ERROR_USER_CANCELED;

import java.io.IOException;
import java.nio.charset.Charset;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.Arrays;
import java.util.concurrent.Executor;

import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.module.annotations.ReactModule;
import com.facebook.react.bridge.UiThreadUtil;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.Arguments;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;

@ReactModule(name = RNLocalAuthenticationModule.NAME)
public class RNLocalAuthenticationModule extends ReactContextBaseJavaModule {
    public static final String NAME = "RNLocalAuthentication";
    public static final String KEY_NAME = "biometric_example";
    private static final int AUTHORIZATION_FAILED = 9999;
    private static final int FINGERPRINT_CHANGE = 7777;
    private Executor executor = new MainThreadExecutor();
    private BiometricPrompt biometricPrompt = null;

    @Override
    public String getName() {
        return NAME;
    }

    public RNLocalAuthenticationModule(ReactApplicationContext context) {
        super(context);
    }

    /**
     * Create Biometry Manager instance
     *
     * @return BiometricManager
     */
    private BiometricManager getBiometricManager() {
        return BiometricManager.from(getReactApplicationContext());
    }

    /**
     * Check if scanner is supported on the device
     *
     * @param p
     */
    @ReactMethod
    public void isSupportedAsync(final Promise p) {
        int status = getBiometricManager().canAuthenticate();

        p.resolve(status != BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE);
    }

    /**
     * Check if scanner is available
     *
     * @param p
     */
    @ReactMethod
    public void isAvailableAsync(final Promise p) {
        int status = getBiometricManager().canAuthenticate();

        p.resolve(status == BiometricManager.BIOMETRIC_SUCCESS);
    }

    /**
     * Get current status of the biometry scanner
     *
     * @param p
     */
    @ReactMethod
    public void getBiometryStatusAsync(final Promise p) {
        int status = getBiometricManager().canAuthenticate();

        if (status == BiometricManager.BIOMETRIC_SUCCESS) {
            p.resolve("BiometryIsAvailable");
        } else {
            String errorDescription = convertErrorCode(status);

            if (errorDescription != null) {
                p.resolve(errorDescription);
            } else {
                p.resolve(String.valueOf(status));
            }
        }
    }

    /**
     * Authenticate user biometrically
     *
     * @param options
     * @param p
     */
    @RequiresApi(api = Build.VERSION_CODES.M)
    @ReactMethod
    public void authenticateAsync(ReadableMap options, final Promise p) {
        final boolean fallbackEnabled = options.hasKey("fallbackEnabled") && !options.isNull("fallbackEnabled")
                ? options.getBoolean("fallbackEnabled")
                : false;
        final boolean isInit = options.hasKey("isInit") && !options.isNull("isInit")
                ? options.getBoolean("isInit")
                : false;

        if (!options.hasKey("reason") || options.isNull("reason")) {
            p.reject("ReasonNotSet", "Reason for requesting authentication is not specified");
            return;
        }

        if ((!options.hasKey("cancelTitle") || options.isNull("cancelTitle"))
                && (!options.hasKey("fallbackToPinCodeAction") || options.isNull("fallbackToPinCodeAction"))) {
            p.reject("CancelTitleNotSet", "Cancel button text must be set and non-empty");
            return;
        }

        biometricPrompt = new BiometricPrompt((FragmentActivity) getCurrentActivity(),
                executor, new BiometricPrompt.AuthenticationCallback() {

            @Override
            public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                super.onAuthenticationError(errorCode, errString);

                if (errorCode == ERROR_NEGATIVE_BUTTON && biometricPrompt != null) {
                    biometricPrompt.cancelAuthentication();
                    release();
                } else if (errorCode == ERROR_USER_CANCELED && !fallbackEnabled && biometricPrompt != null) {
                    biometricPrompt.cancelAuthentication();
                    p.resolve(makeAuthorizationResponse(false, BiometricPrompt.ERROR_NEGATIVE_BUTTON));
                    release();
                    return;
                }

                p.resolve(makeAuthorizationResponse(false, errorCode));
            }

            @RequiresApi(api = Build.VERSION_CODES.N)
            @Override
            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                super.onAuthenticationSucceeded(result);
                p.resolve(makeAuthorizationResponse(true, null));
                release();
            }

            @Override
            public void onAuthenticationFailed() {
                super.onAuthenticationFailed();

                // p.resolve(makeAuthorizationResponse(false, AUTHORIZATION_FAILED));
            }
        });

        final BiometricPrompt.PromptInfo promptInfo = buildBiometricPrompt(options);

        UiThreadUtil.runOnUiThread(
                new Runnable() {
                    @RequiresApi(api = Build.VERSION_CODES.N)
                    @Override
                    public void run() {
                        try {
                            Cipher cipher = getCipher();
                            SecretKey secretKey = getOrCreateSecretKey();
                            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
                            biometricPrompt.authenticate(promptInfo, new BiometricPrompt.CryptoObject(cipher));
                        } catch (KeyPermanentlyInvalidatedException ex) {
                            Log.e("run#KeyPermanently", ex.toString());
                            removeSecretKey();
                            if (isInit) {
                                biometricPrompt.authenticate(promptInfo);
                            } else {
                                p.resolve(makeAuthorizationResponse(false, FINGERPRINT_CHANGE));
                            }
                        } catch (InvalidKeyException ex) {
                            Log.e("run#InvalidKey", ex.toString());
                            p.resolve(makeAuthorizationResponse(false, BiometricPrompt.ERROR_LOCKOUT));
                        }
                    }
                }
        );
    }

    /**
     * Make authorization response that will be resolved in promise
     *
     * @param success
     * @param errorCode
     * @return WritableMap
     */
    private WritableMap makeAuthorizationResponse(boolean success, Integer errorCode) {
        String error = null;

        if (errorCode != null) {
            error = convertErrorCode(errorCode);

            if (error == null) {
                error = String.valueOf(errorCode);
            }
        }

        WritableMap map = Arguments.createMap();

        map.putBoolean("success", success);
        map.putString("error", error);
        map.putString("warning", "");

        return map;
    }

    /**
     * Create biometric prompt builder
     *
     * @param options
     * @return BiometricPrompt.PromptInfo
     */
    private BiometricPrompt.PromptInfo buildBiometricPrompt(ReadableMap options) {
        String title = options.hasKey("title") && !options.isNull("title") ? options.getString("title") : "Biometric Login";
        String subtitle = options.getString("reason");
        boolean fallbackToPinCodeAction = options.hasKey("fallbackToPinCodeAction") && !options.isNull("fallbackToPinCodeAction")
                ? options.getBoolean("fallbackToPinCodeAction")
                : false;

        BiometricPrompt.PromptInfo.Builder biometryBuilder = new BiometricPrompt.PromptInfo.Builder();

        biometryBuilder.setTitle(title)
                .setSubtitle(subtitle);

        if (options.hasKey("description") && !options.isNull("description")) {
            biometryBuilder.setDescription(options.getString("description"));
        }

        if (fallbackToPinCodeAction) {
            biometryBuilder.setDeviceCredentialAllowed(true);
        } else if (options.hasKey("cancelTitle") && !options.isNull("cancelTitle")) {
            biometryBuilder.setNegativeButtonText(options.getString("cancelTitle"));
        }

        return biometryBuilder.build();
    }

    /**
     * Convert error codes from native to JS
     *
     * @param errorCode
     * @return string
     */
    private String convertErrorCode(int errorCode) {
        switch (errorCode) {
            /**
             * No biometric features available on this device.
             */
            case BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE:
                return "BiometryNotAvailable";
            /**
             * The hardware is unavailable. Try again later.
             */
            case BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE:
                return "BiometryTemporaryNotAvailable";
            /**
             * The user hasn't associated any biometric credentials with their account.
             */
            case BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED:
                return "BiometryNotEnrolled";
            /**
             * The operation was canceled because the biometric sensor is unavailable. For example, this may
             * happen when the user is switched, the device is locked or another pending operation prevents
             * or disables it.
             */
            case BiometricPrompt.ERROR_CANCELED:
                return "SystemCancel";
            /**
             * The operation was canceled because the API is locked out due to too many attempts.
             * This occurs after 5 failed attempts, and lasts for 30 seconds.
             */
            case BiometricPrompt.ERROR_LOCKOUT:
                return "BiometryTemporaryLockout"; // TODO: question about naming???
            /**
             * The operation was canceled because ERROR_LOCKOUT occurred too many times.
             * Biometric authentication is disabled until the user unlocks with strong authentication
             * (PIN/Pattern/Password)
             */
            case BiometricPrompt.ERROR_LOCKOUT_PERMANENT:
                return "BiometryLockout";
            /**
             * The user pressed the negative button.
             */
            case BiometricPrompt.ERROR_NEGATIVE_BUTTON:
                return "UserCancel";
            /**
             * The device does not have pin, pattern, or password set up.
             */
            case BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL:
                return "PasscodeNotSet";
            /**
             * Error state returned for operations like enrollment; the operation cannot be completed
             * because there's not enough storage remaining to complete the operation.
             */
            case BiometricPrompt.ERROR_NO_SPACE:
                return "NoSpace";
            /**
             * Error state returned when the current request has been running too long. This is intended to
             * prevent programs from waiting for the biometric sensor indefinitely. The timeout is platform
             * and sensor-specific, but is generally on the order of 30 seconds.
             */
            case BiometricPrompt.ERROR_TIMEOUT:
                return "Timeout";
            /**
             * Error state returned when the sensor was unable to process the current image.
             */
            case BiometricPrompt.ERROR_UNABLE_TO_PROCESS:
                return "UnableToProcess";
            /**
             * The user canceled the operation. Upon receiving this, applications should use alternate
             * authentication (e.g. a password). The application should also provide the means to return to
             * biometric authentication, such as a "use <biometric>" button.
             */
            case BiometricPrompt.ERROR_USER_CANCELED:
                return "UserFallback";
            /**
             * Hardware vendors may extend this list if there are conditions that do not fall under one of
             * the above categories. Vendors are responsible for providing error strings for these errors.
             * These messages are typically reserved for internal operations such as enrollment, but may be
             * used to express vendor errors not otherwise covered. Applications are expected to show the
             * error message string if they happen, but are advised not to rely on the message id since they
             * will be device and vendor-specific
             */
            case BiometricPrompt.ERROR_VENDOR:
                return "UnexpectedVendorError";
            /**
             * Called when a biometric is valid but not recognized.
             */
            case AUTHORIZATION_FAILED:
                return "AuthenticationFailed";
            case FINGERPRINT_CHANGE:
                return "FingerprintChange";
            default:
                //return "Unexpected error: " + String.valueOf(errorCode);
                return null;
        }
    }

    /**
     * Release memory resources
     */
    @ReactMethod
    public void release() {
        biometricPrompt = null;
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    private SecretKey getOrCreateSecretKey() {
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            // Before the keystore can be accessed, it must be loaded.
            keyStore.load(null);
            SecretKey secretKey = ((SecretKey) keyStore.getKey(KEY_NAME, null));
            if (secretKey != null) {
                return secretKey;
            }
            KeyGenParameterSpec keyGenParameterSpec = new KeyGenParameterSpec.Builder(
                    KEY_NAME,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
                    .setUserAuthenticationRequired(true)
                    .setInvalidatedByBiometricEnrollment(true)
                    .build();
            KeyGenerator keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            keyGenerator.init(keyGenParameterSpec);
            return keyGenerator.generateKey();
        } catch (Exception e) {
            Log.e("generateKey", e.toString());
        }
        return null;
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private Cipher getCipher() {
        try {
            return Cipher.getInstance(KeyProperties.KEY_ALGORITHM_AES + "/"
                    + KeyProperties.BLOCK_MODE_CBC + "/"
                    + KeyProperties.ENCRYPTION_PADDING_PKCS7);
        } catch (NoSuchPaddingException | NoSuchAlgorithmException e) {
            Log.e("getCipher", e.toString());
        }
        return null;
    }

    private void removeSecretKey() {
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            // Before the keystore can be accessed, it must be loaded.
            keyStore.load(null);
            keyStore.deleteEntry(KEY_NAME);
        } catch (KeyStoreException | CertificateException | NoSuchAlgorithmException | IOException e) {
            Log.e("removeSecretKey", e.toString());
        }
    }
}