package pt.xsoulp.domotica;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.hardware.biometrics.BiometricManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

public final class SettingsActivity extends Activity {
    private static final String SETTINGS = "app_settings";
    private static final String SERVER_URL = "server_url";
    private static final String DEFAULT_SERVER_URL = "";
    private SecretStore secretStore;
    private SharedPreferences settings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        applySystemBarInsets();

        secretStore = new SecretStore(this);
        settings = getSharedPreferences(SETTINGS, MODE_PRIVATE);
        EditText bearerInput = findViewById(R.id.bearerInput);
        EditText serverInput = findViewById(R.id.serverInput);
        String configuredServer = settings.getString(SERVER_URL, DEFAULT_SERVER_URL);
        serverInput.setText(configuredServer);
        if (secretStore.hasBearer()) {
            bearerInput.setHint(getString(R.string.token_saved_leave_blank_to_keep_it));
        }

        findViewById(R.id.backButton).setOnClickListener(view -> finish());
        findViewById(R.id.saveButton).setOnClickListener(view -> {
            String bearer = bearerInput.getText().toString().trim();
            String server = serverInput.getText().toString().trim();
            if (!secretStore.hasBearer() && bearer.isEmpty()) {
                bearerInput.setError(getString(R.string.bearer_token_required));
                return;
            }
            if (server.isEmpty()) {
                serverInput.setError(getString(R.string.server_required));
                return;
            }
            if (!server.startsWith("http://") && !server.startsWith("https://")) {
                serverInput.setError(getString(R.string.use_http_or_https));
                return;
            }
            try {
                if (!bearer.isEmpty()) {
                    secretStore.saveBearer(bearer);
                }
                settings.edit().putString(SERVER_URL, server).apply();
                Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show();
                finish();
            } catch (Exception error) {
                Toast.makeText(this, getString(R.string.could_not_save_the_bearer_token), Toast.LENGTH_LONG).show();
            }
        });

        updateStatuses();
    }

    private void applySystemBarInsets() {
        View root = findViewById(R.id.rootScroll);
        root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            android.graphics.Insets insets = windowInsets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
            );
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return windowInsets;
        });
        root.requestApplyInsets();
    }

    private void updateStatuses() {
        TextView networkStatus = findViewById(R.id.networkStatus);
        boolean online = hasNetwork();
        networkStatus.setText(online ? getString(R.string.online_2) : getString(R.string.offline_2));
        networkStatus.setTextColor(getColor(online ? R.color.success : R.color.danger));

        TextView biometricStatus = findViewById(R.id.biometricStatus);
        KeyguardManager keyguardManager = getSystemService(KeyguardManager.class);
        boolean deviceCredentialActive = keyguardManager != null && keyguardManager.isDeviceSecure();
        if (!BuildConfig.USE_BIOMETRIC_AUTH) {
            biometricStatus.setText(
                    deviceCredentialActive
                            ? getString(R.string.pin_screen_lock_enabled)
                            : getString(R.string.screen_lock_not_configured)
            );
            biometricStatus.setTextColor(getColor(
                    deviceCredentialActive ? R.color.success : R.color.danger
            ));
            return;
        }

        BiometricManager manager = getSystemService(BiometricManager.class);
        boolean biometricActive = manager != null
                && manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                == BiometricManager.BIOMETRIC_SUCCESS;
        boolean active = biometricActive || deviceCredentialActive;
        biometricStatus.setText(
                biometricActive
                        ? getString(R.string.fingerprint_or_pin_enabled)
                        : (deviceCredentialActive
                                ? getString(R.string.pin_screen_lock_enabled)
                                : getString(R.string.screen_lock_not_configured))
        );
        biometricStatus.setTextColor(getColor(active ? R.color.success : R.color.danger));
    }

    private boolean hasNetwork() {
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        if (manager == null) {
            return false;
        }
        Network network = manager.getActiveNetwork();
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
        return capabilities != null;
    }
}
