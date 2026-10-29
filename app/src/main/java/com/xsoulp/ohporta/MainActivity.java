package com.xsoulp.ohporta;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.KeyguardManager;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.time.OffsetDateTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int LOCATION_PERMISSION_REQUEST = 10;
    private static final int DEVICE_CREDENTIAL_REQUEST = 11;
    private static final long MAX_CACHED_LOCATION_AGE_NS = 20_000_000_000L;
    private static final long VALIDATED_LOCATION_MAX_AGE_NS = 120_000_000_000L;
    private static final float MAX_CACHED_LOCATION_ACCURACY_M = 100.0f;
    private static final String SETTINGS = "app_settings";
    private static final String SERVER_URL = "server_url";
    private static final String DEFAULT_SERVER_URL = "";
    private static final int ACCESS_HISTORY_LIMIT = 30;
    private static final int ACCESS_HISTORY_DAYS = 2;
    private static final String PENDING_AUTH_PATH = "pending_auth_path";

    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService historyExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private SharedPreferences settings;
    private SecretStore secretStore;
    private TextView statusText;
    private TextView connectivityText;
    private TextView residenceStatus;
    private TextView aptActionTitle;
    private TextView bldActionTitle;
    private ProgressBar progressBar;
    private View openApartmentButton;
    private View aptInteractionLayer;
    private View openBuildingButton;
    private View usersButton;
    private View accessHistorySection;
    private LinearLayout accessHistoryContainer;
    private TextView accessHistoryMessage;
    private CancellationSignal locationCancellation;
    private Location validatedLocation;
    private boolean aptCanOpen;
    private boolean bldCanOpen;
    private boolean adminUser;
    private int capabilitiesGeneration;
    private int historyGeneration;
    private String pendingAuthenticationPath;

    private interface LocationCallback {
        void onLocation(Location location);

        void onError(String message);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        applySystemBarInsets();

        if (savedInstanceState != null) {
            pendingAuthenticationPath = savedInstanceState.getString(PENDING_AUTH_PATH);
        }

        settings = getSharedPreferences(SETTINGS, MODE_PRIVATE);
        secretStore = new SecretStore(this);
        statusText = findViewById(R.id.statusText);
        connectivityText = findViewById(R.id.connectivityText);
        residenceStatus = findViewById(R.id.residenceStatus);
        aptActionTitle = findViewById(R.id.aptActionTitle);
        bldActionTitle = findViewById(R.id.bldActionTitle);
        progressBar = findViewById(R.id.progressBar);

        openApartmentButton = findViewById(R.id.openApartmentButton);
        aptInteractionLayer = findViewById(R.id.aptInteractionLayer);
        openBuildingButton = findViewById(R.id.openBuildingButton);
        usersButton = findViewById(R.id.usersButton);
        accessHistorySection = findViewById(R.id.accessHistorySection);
        accessHistoryContainer = findViewById(R.id.accessHistoryContainer);
        accessHistoryMessage = findViewById(R.id.accessHistoryMessage);
        aptInteractionLayer.setOnClickListener(view -> {
            if (aptCanOpen) {
                beginOperation("/apt_door/open", getString(R.string.open_apartment));
            } else {
                setStatus(getString(R.string.apartment_opening_unavailable), true);
            }
        });
        aptInteractionLayer.setOnLongClickListener(view -> {
            showAptActions();
            return true;
        });
        openBuildingButton.setOnClickListener(view -> beginOperation("/bld_door", getString(R.string.open_building)));
        findViewById(R.id.settingsButton).setOnClickListener(view -> showSettings());
        usersButton.setOnClickListener(view -> startActivity(
                new Intent(this, UserListActivity.class)
        ));
        usersButton.setVisibility(View.GONE);

        disableOpening(getString(R.string.checking_2));

        if (settings.getString(SERVER_URL, DEFAULT_SERVER_URL).trim().isEmpty()) {
            capabilitiesFailed(getString(R.string.set_your_server_in_settings));
            return;
        }
        requestLocationPermissionIfNeeded();
        if (!secretStore.hasBearer()) {
            statusText.setText(getString(R.string.set_your_bearer_token_in_settings));
        }
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

    private void beginOperation(String path, String title) {
        if (settings.getString(SERVER_URL, DEFAULT_SERVER_URL).trim().isEmpty()) {
            showSettings();
            return;
        }
        if (!secretStore.hasBearer()) {
            showSettings();
            return;
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION
                    },
                    LOCATION_PERMISSION_REQUEST
            );
            setStatus(getString(R.string.location_permission_is_required), true);
            return;
        }
        if (!hasNetwork()) {
            setStatus(getString(R.string.no_network_connection), true);
            return;
        }

        if (findValidatedLocation() == null && !hasLocationProvider()) {
            showEnableLocationDialog();
            return;
        }

        authenticateForOperation(path, title);
    }

    private void authenticateForOperation(String path, String title) {
        if (!BuildConfig.USE_BIOMETRIC_AUTH) {
            authenticateWithDeviceCredential(path, title);
            return;
        }

        BiometricManager manager = getSystemService(BiometricManager.class);
        boolean biometricAvailable = manager != null
                && manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                == BiometricManager.BIOMETRIC_SUCCESS;

        if (!biometricAvailable) {
            authenticateWithDeviceCredential(path, title);
            return;
        }

        BiometricPrompt prompt = new BiometricPrompt.Builder(this)
                .setTitle(title)
                .setSubtitle(getString(R.string.confirm_with_your_fingerprint))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButton(
                        getString(R.string.use_pin),
                        getMainExecutor(),
                        (dialog, which) -> authenticateWithDeviceCredential(path, title)
                )
                .build();

        prompt.authenticate(
                new CancellationSignal(),
                getMainExecutor(),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                        obtainLocationAndSend(path);
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, CharSequence message) {
                        if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE
                                || errorCode == BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT
                                || errorCode == BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS
                                || errorCode == BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT
                                || errorCode == BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT) {
                            authenticateWithDeviceCredential(path, title);
                        } else if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED) {
                            setStatus(message.toString(), true);
                        }
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        setStatus(getString(R.string.fingerprint_not_recognised), true);
                    }
                }
        );
    }

    private void authenticateWithDeviceCredential(String path, String title) {
        KeyguardManager keyguardManager = getSystemService(KeyguardManager.class);
        if (keyguardManager == null || !keyguardManager.isDeviceSecure()) {
            setStatus(getString(R.string.set_up_a_device_pin_pattern_or_password), true);
            return;
        }

        Intent intent = keyguardManager.createConfirmDeviceCredentialIntent(
                title,
                getString(R.string.confirm_with_your_device_pin_pattern_or_password)
        );
        if (intent == null) {
            setStatus(getString(R.string.could_not_open_device_authentication), true);
            return;
        }

        pendingAuthenticationPath = path;
        startActivityForResult(intent, DEVICE_CREDENTIAL_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != DEVICE_CREDENTIAL_REQUEST) {
            return;
        }

        String path = pendingAuthenticationPath;
        pendingAuthenticationPath = null;
        if (resultCode == RESULT_OK && path != null) {
            // Run after onResume so its capability refresh cannot cancel this location request.
            mainHandler.post(() -> obtainLocationAndSend(path));
        }
    }

    private void obtainLocationAndSend(String path) {
        setBusy(true, getString(R.string.getting_precise_location));
        obtainCurrentLocation(new LocationCallback() {
            @Override
            public void onLocation(Location location) {
                send(path, location);
            }

            @Override
            public void onError(String message) {
                setBusy(false, message);
                statusText.setTextColor(getColor(R.color.danger));
            }
        });
    }

    private void obtainCurrentLocation(LocationCallback callback) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            callback.onError(getString(R.string.location_permission_missing));
            return;
        }

        Location validated = findValidatedLocation();
        if (validated != null) {
            callback.onLocation(validated);
            return;
        }

        LocationManager manager = getSystemService(LocationManager.class);
        if (manager == null) {
            callback.onError(getString(R.string.location_service_unavailable));
            return;
        }

        Location cachedLocation = findRecentLocation(manager);
        if (cachedLocation != null) {
            callback.onLocation(cachedLocation);
            return;
        }

        String provider = findEnabledLocationProvider(manager);
        if (provider == null) {
            callback.onError(getString(R.string.enable_location_in_settings));
            return;
        }

        CancellationSignal cancellation = new CancellationSignal();
        locationCancellation = cancellation;
        Runnable timeout = () -> {
            if (!cancellation.isCanceled()) {
                cancellation.cancel();
                if (locationCancellation == cancellation) {
                    locationCancellation = null;
                }
                callback.onError(getString(R.string.could_not_get_location));
            }
        };
        mainHandler.postDelayed(timeout, 15_000);

        manager.getCurrentLocation(
                provider,
                cancellation,
                getMainExecutor(),
                location -> {
                    mainHandler.removeCallbacks(timeout);
                    if (locationCancellation == cancellation) {
                        locationCancellation = null;
                    }
                    if (location == null) {
                        callback.onError(getString(R.string.location_unavailable));
                    } else {
                        callback.onLocation(location);
                    }
                }
        );
    }

    private Location findValidatedLocation() {
        if (validatedLocation != null) {
            // Use the fix's original age so repeated operations never extend the window.
            long age = SystemClock.elapsedRealtimeNanos()
                    - validatedLocation.getElapsedRealtimeNanos();
            if (age >= 0 && age < VALIDATED_LOCATION_MAX_AGE_NS) {
                return new Location(validatedLocation);
            }
            validatedLocation = null;
        }
        return null;
    }

    private String findEnabledLocationProvider(LocationManager manager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && manager.hasProvider(LocationManager.FUSED_PROVIDER)
                && manager.isProviderEnabled(LocationManager.FUSED_PROVIDER)) {
            return LocationManager.FUSED_PROVIDER;
        }
        if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            return LocationManager.GPS_PROVIDER;
        }
        if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            return LocationManager.NETWORK_PROVIDER;
        }
        return null;
    }

    private boolean hasLocationProvider() {
        LocationManager manager = getSystemService(LocationManager.class);
        return manager != null && findEnabledLocationProvider(manager) != null;
    }

    private void showEnableLocationDialog() {
        setStatus(getString(R.string.enable_location_in_settings), true);
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.location_disabled))
                .setMessage(getString(R.string.precise_location_is_required_to_check_your_distance_from_the_door))
                .setPositiveButton(getString(R.string.open_settings), (dialog, which) -> startActivity(
                        new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                ))
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private Location findRecentLocation(LocationManager manager) {
        Location best = null;
        java.util.List<String> providers = new java.util.ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            providers.add(LocationManager.FUSED_PROVIDER);
        }
        providers.add(LocationManager.NETWORK_PROVIDER);
        providers.add(LocationManager.GPS_PROVIDER);
        long now = SystemClock.elapsedRealtimeNanos();
        for (String provider : providers) {
            try {
                Location location = manager.getLastKnownLocation(provider);
                if (location == null || !location.hasAccuracy()
                        || location.getAccuracy() > MAX_CACHED_LOCATION_ACCURACY_M
                        || now - location.getElapsedRealtimeNanos() > MAX_CACHED_LOCATION_AGE_NS) {
                    continue;
                }
                if (best == null
                        || location.getElapsedRealtimeNanos() > best.getElapsedRealtimeNanos()) {
                    best = location;
                }
            } catch (IllegalArgumentException | SecurityException ignored) {
                // Provider is unavailable or location permission was revoked.
            }
        }
        return best;
    }

    private void refreshCapabilities() {
        int generation = ++capabilitiesGeneration;
        aptCanOpen = false;
        bldCanOpen = false;
        adminUser = false;
        accessHistorySection.setVisibility(View.GONE);
        disableOpening(getString(R.string.checking_2));

        if (settings.getString(SERVER_URL, DEFAULT_SERVER_URL).trim().isEmpty()) {
            capabilitiesFailed(getString(R.string.set_your_server_in_settings));
            return;
        }

        if (!secretStore.hasBearer()) {
            capabilitiesFailed(getString(R.string.set_your_bearer_token_in_settings));
            return;
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            capabilitiesFailed(getString(R.string.location_permission_missing));
            return;
        }
        if (!hasNetwork()) {
            capabilitiesFailed(getString(R.string.no_network_connection));
            return;
        }

        final String bearer;
        try {
            bearer = secretStore.getBearer();
        } catch (Exception error) {
            capabilitiesFailed(getString(R.string.could_not_read_the_bearer_token));
            return;
        }

        if (locationCancellation != null) {
            locationCancellation.cancel();
            locationCancellation = null;
        }
        progressBar.setVisibility(View.VISIBLE);
        setStatus(getString(R.string.checking_access), false);
        obtainCurrentLocation(new LocationCallback() {
            @Override
            public void onLocation(Location location) {
                if (generation != capabilitiesGeneration || isDestroyed()) {
                    return;
                }
                String serverUrl = settings.getString(SERVER_URL, DEFAULT_SERVER_URL);
                networkExecutor.execute(() -> {
                    ApiClient.CapabilitiesResult result = ApiClient.getCapabilities(
                            serverUrl,
                            bearer,
                            location
                    );
                    runOnUiThread(() -> {
                        if (generation != capabilitiesGeneration || isDestroyed()) {
                            return;
                        }
                        progressBar.setVisibility(View.GONE);
                        if (!result.successful) {
                            usersButton.setVisibility(View.GONE);
                            capabilitiesFailed(result.message);
                            return;
                        }
                        if (result.apartment.canOpen || result.building.canOpen) {
                            validatedLocation = new Location(location);
                        }
                        adminUser = result.user != null && result.user.isAdmin();
                        usersButton.setVisibility(adminUser ? View.VISIBLE : View.GONE);
                        accessHistorySection.setVisibility(adminUser ? View.VISIBLE : View.GONE);
                        applyDoorCapability(
                                "APT",
                                openApartmentButton,
                                aptActionTitle,
                                result.apartment
                        );
                        applyDoorCapability(
                                "BLD",
                                openBuildingButton,
                                bldActionTitle,
                                result.building
                        );
                        aptCanOpen = result.apartment.canOpen;
                        bldCanOpen = result.building.canOpen;
                        updateResidenceStatus(
                                true,
                                nearestDistance(
                                        result.apartment.distanceM,
                                        result.building.distanceM
                                ),
                                false
                        );
                        aptInteractionLayer.setContentDescription(
                                result.apartment.canOpen
                                        ? getString(R.string.open_apartment_door_press_and_hold_for_more_actions)
                                        : getString(R.string.apartment_opening_unavailable_press_and_hold_to_lock_or_unlock)
                        );
                        if (adminUser) {
                            loadAccessHistory();
                        }
                        setStatus(getString(R.string.access_updated), false);
                    });
                });
            }

            @Override
            public void onError(String message) {
                if (generation == capabilitiesGeneration) {
                    capabilitiesFailed(message);
                }
            }
        });
    }

    private void applyDoorCapability(
            String doorName,
            View button,
            TextView title,
            ApiClient.DoorCapability capability
    ) {
        String distance = formatDistance(capability.distanceM);
        title.setText(doorName);
        if (!capability.canOpen) {
            button.setContentDescription(getString(R.string.door_unavailable, doorName));
        } else if (capability.mode.equals("remote")) {
            button.setContentDescription(
                    getString(R.string.open_door_remote, doorName, distance.isEmpty() ? "" : ", " + distance)
            );
        } else {
            button.setContentDescription(
                    getString(R.string.open_door_distance, doorName, distance.isEmpty() ? "" : ", " + distance)
            );
        }
        button.setEnabled(capability.canOpen);
        button.setAlpha(capability.canOpen ? 1.0f : 0.45f);
    }

    private String formatDistance(Double distanceM) {
        if (distanceM == null) {
            return "";
        }
        if (distanceM < 1_000.0) {
            return Math.round(distanceM) + " m";
        }
        return String.format(Locale.getDefault(), "%.1f km", distanceM / 1_000.0);
    }

    private Double nearestDistance(Double apartmentDistanceM, Double buildingDistanceM) {
        if (apartmentDistanceM == null) {
            return buildingDistanceM;
        }
        if (buildingDistanceM == null) {
            return apartmentDistanceM;
        }
        return Math.min(apartmentDistanceM, buildingDistanceM);
    }

    private void updateResidenceStatus(boolean online, Double distanceM, boolean locating) {
        String state = online ? getString(R.string.online) : getString(R.string.offline);
        String distance = formatDistance(distanceM);
        String suffix = locating
                ? getString(R.string.locating)
                : (distance.isEmpty() ? "" : "  •  " + distance);
        String text = getString(R.string.my_residence_n) + state + suffix;
        SpannableString styled = new SpannableString(text);
        int dot = text.indexOf('●');
        styled.setSpan(
                new ForegroundColorSpan(getColor(online ? R.color.success : R.color.danger)),
                dot,
                dot + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        );
        residenceStatus.setText(styled);
    }

    private void disableOpening(String state) {
        aptCanOpen = false;
        bldCanOpen = false;
        aptActionTitle.setText("APT");
        bldActionTitle.setText("BLD");
        openApartmentButton.setContentDescription(getString(R.string.apartment_opening_unavailable_2) + state);
        openBuildingButton.setContentDescription(getString(R.string.building_opening_unavailable) + state);
        openApartmentButton.setEnabled(false);
        openBuildingButton.setEnabled(false);
        openApartmentButton.setAlpha(0.45f);
        openBuildingButton.setAlpha(0.45f);
    }

    private void capabilitiesFailed(String message) {
        progressBar.setVisibility(View.GONE);
        disableOpening(getString(R.string.unavailable));
        updateResidenceStatus(hasNetwork(), null, false);
        setStatus(message, true);
    }

    private void loadAccessHistory() {
        if (!adminUser || !secretStore.hasBearer()) {
            accessHistorySection.setVisibility(View.GONE);
            return;
        }
        final String bearer;
        try {
            bearer = secretStore.getBearer();
        } catch (Exception error) {
            accessHistoryMessage.setText(getString(R.string.unavailable_2));
            return;
        }
        int capabilityGeneration = capabilitiesGeneration;
        int requestGeneration = ++historyGeneration;
        accessHistoryMessage.setText(getString(R.string.updating));
        String serverUrl = settings.getString(SERVER_URL, DEFAULT_SERVER_URL);
        historyExecutor.execute(() -> {
            ApiClient.AccessHistoryResult result = ApiClient.getAccessHistory(
                    serverUrl,
                    bearer,
                    ACCESS_HISTORY_LIMIT
            );
            List<ApiClient.AccessEvent> recentEntries = result.successful
                    ? keepRecentAccesses(result.entries)
                    : Collections.emptyList();
            runOnUiThread(() -> {
                if (capabilityGeneration != capabilitiesGeneration
                        || requestGeneration != historyGeneration
                        || !adminUser
                        || isDestroyed()) {
                    return;
                }
                if (!result.successful) {
                    accessHistoryMessage.setText(getString(R.string.unavailable_2));
                    return;
                }
                accessHistoryMessage.setText("");
                renderAccessHistory(recentEntries);
            });
        });
    }

    private List<ApiClient.AccessEvent> keepRecentAccesses(
            List<ApiClient.AccessEvent> entries
    ) {
        Instant cutoff = Instant.now().minus(ACCESS_HISTORY_DAYS, ChronoUnit.DAYS);
        List<ApiClient.AccessEvent> recent = new ArrayList<>();
        for (ApiClient.AccessEvent entry : entries) {
            try {
                if (!OffsetDateTime.parse(entry.openedAt).toInstant().isBefore(cutoff)) {
                    recent.add(entry);
                }
            } catch (DateTimeParseException ignored) {
                // Ignore malformed timestamps so the two-day window remains strict.
            }
        }
        return Collections.unmodifiableList(recent);
    }

    private void renderAccessHistory(java.util.List<ApiClient.AccessEvent> entries) {
        accessHistoryContainer.removeAllViews();
        if (entries.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(getString(R.string.no_access_events_in_the_last_2_days));
            empty.setTextColor(getColor(R.color.text_secondary));
            empty.setTextSize(12);
            empty.setPadding(0, dp(20), 0, dp(12));
            accessHistoryContainer.addView(empty);
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(this);
        for (ApiClient.AccessEvent entry : entries) {
            View row = inflater.inflate(R.layout.item_access_history, accessHistoryContainer, false);
            TextView user = row.findViewById(R.id.historyUser);
            TextView door = row.findViewById(R.id.historyDoor);
            TextView time = row.findViewById(R.id.historyTime);
            user.setText(entry.userName);
            door.setText(entry.door + " · " + historyActionLabel(entry.action));
            time.setText(formatHistoryTime(entry.openedAt));
            accessHistoryContainer.addView(row);
        }
    }

    private String historyActionLabel(String action) {
        switch (action) {
            case "lock":
                return getString(R.string.lock_2);
            case "unlock":
                return getString(R.string.unlock_2);
            case "open":
            default:
                return getString(R.string.open);
        }
    }

    private String formatHistoryTime(String openedAt) {
        try {
            return OffsetDateTime.parse(openedAt).format(
                    DateTimeFormatter.ofPattern("dd/MM  HH:mm", Locale.getDefault())
            );
        } catch (DateTimeParseException error) {
            return openedAt.replace('T', ' ');
        }
    }

    private void send(String path, Location location) {
        final String bearer;
        try {
            bearer = secretStore.getBearer();
        } catch (Exception error) {
            setBusy(false, getString(R.string.could_not_read_the_bearer_token));
            statusText.setTextColor(getColor(R.color.danger));
            return;
        }

        String serverUrl = settings.getString(SERVER_URL, DEFAULT_SERVER_URL);
        setBusy(true, getString(R.string.running));
        networkExecutor.execute(() -> {
            ApiClient.Result result = ApiClient.post(serverUrl, path, bearer, location);
            runOnUiThread(() -> {
                if (result.successful) {
                    validatedLocation = new Location(location);
                }
                setBusy(false, result.successful ? getString(R.string.done) : result.message);
                statusText.setTextColor(getColor(
                        result.successful ? R.color.primary_dark : R.color.danger
                ));
                if (result.successful && adminUser) {
                    loadAccessHistory();
                }
            });
        });
    }

    private boolean hasNetwork() {
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        if (manager == null) {
            return false;
        }
        Network network = manager.getActiveNetwork();
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private void showSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    private void showAptActions() {
        if (!aptInteractionLayer.isEnabled()) {
            return;
        }

        Dialog sheet = new Dialog(this, R.style.AptBottomSheetDialog);
        sheet.setContentView(R.layout.dialog_apt_actions);
        sheet.setCanceledOnTouchOutside(true);

        View open = sheet.findViewById(R.id.sheetOpenButton);
        View lock = sheet.findViewById(R.id.sheetLockButton);
        View unlock = sheet.findViewById(R.id.sheetUnlockButton);
        open.setEnabled(aptCanOpen);
        open.setAlpha(aptCanOpen ? 1.0f : 0.42f);
        open.setContentDescription(aptCanOpen ? getString(R.string.open_apartment_door) : getString(R.string.apartment_opening_unavailable));

        sheet.findViewById(R.id.closeSheetButton).setOnClickListener(view -> sheet.dismiss());
        open.setOnClickListener(view -> runSheetAction(
                sheet, "/apt_door/open", getString(R.string.open_apartment)
        ));
        lock.setOnClickListener(view -> runSheetAction(
                sheet, "/apt_door/lock", getString(R.string.lock_2)
        ));
        unlock.setOnClickListener(view -> runSheetAction(
                sheet, "/apt_door/unlock", getString(R.string.unlock_2)
        ));

        sheet.show();
        Window window = sheet.getWindow();
        if (window != null) {
            window.setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT
            );
            window.setGravity(Gravity.BOTTOM);
            window.setNavigationBarColor(getColor(R.color.background));
        }
    }

    private void runSheetAction(Dialog sheet, String path, String title) {
        sheet.dismiss();
        beginOperation(path, title);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean online = hasNetwork();
        connectivityText.setText(online ? getString(R.string.connected_to_service) : getString(R.string.disconnected_from_service));
        connectivityText.setTextColor(getColor(online ? R.color.success : R.color.danger));
        updateResidenceStatus(online, null, online);
        refreshCapabilities();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED) {
                refreshCapabilities();
            } else {
                capabilitiesFailed(getString(R.string.allow_precise_location_to_continue));
            }
        }
    }

    private void requestLocationPermissionIfNeeded() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION
                    },
                    LOCATION_PERMISSION_REQUEST
            );
        }
    }

    private void setBusy(boolean busy, String message) {
        progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        aptInteractionLayer.setEnabled(!busy);
        openApartmentButton.setEnabled(!busy && aptCanOpen);
        openBuildingButton.setEnabled(!busy && bldCanOpen);
        setStatus(message, false);
    }

    private void setStatus(String message, boolean error) {
        statusText.setText(message);
        statusText.setTextColor(getColor(error ? R.color.danger : R.color.text_secondary));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(PENDING_AUTH_PATH, pendingAuthenticationPath);
    }

    @Override
    protected void onDestroy() {
        capabilitiesGeneration++;
        historyGeneration++;
        if (locationCancellation != null) {
            locationCancellation.cancel();
        }
        networkExecutor.shutdownNow();
        historyExecutor.shutdownNow();
        super.onDestroy();
    }
}
