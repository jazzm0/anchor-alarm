package com.anchoralarm;

import static com.anchoralarm.location.AnchorWatchdogService.ANCHOR_WATCHDOG_CHANNEL;
import static com.anchoralarm.location.LocationService.LOCATION_SERVICE_CHANNEL;
import static java.util.Locale.ENGLISH;
import static java.util.Objects.isNull;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.anchoralarm.location.AnchorWatchdogService;
import com.anchoralarm.location.GNSSConstellationMonitor;
import com.anchoralarm.location.LocationService;
import com.anchoralarm.location.LocationUpdateListener;
import com.anchoralarm.model.LocationTrack;
import com.anchoralarm.repository.LocationTrackRepository;

import java.util.List;

public class MainActivity extends AppCompatActivity implements LocationUpdateListener {


    public static final String INTENT_STOP_ALARM = "STOP_ALARMS";

    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1;
    private static final int NOTIFICATION_PERMISSION_REQUEST_CODE = 2;
    private static final int BACKGROUND_LOCATION_PERMISSION_REQUEST_CODE = 3;
    private static final String PREFS_NAME = "AnchorPrefs";
    private static final String PREF_ANCHOR_LAT = "anchorLat";
    private static final String PREF_ANCHOR_LON = "anchorLon";
    private static final String PREF_ANCHOR_DEPTH = "anchorDepth";
    private static final String PREF_CHAIN_LENGTH = "chainLength";

    // UI components
    private Location anchorLocation;
    private Location currentLocation;
    private float anchorDepth;
    private float chainLength;
    private float driftRadius;
    private TextView statusText;
    private TextView satelliteCountText;
    private TextView accuracyText;
    private TextView qualityText;
    private ImageView qualityIcon;
    private SwoyRadiusView swoyRadiusView;
    private SharedPreferences prefs;
    private float locationAccuracy = 0.0f;
    private boolean isWatchdogServiceRunning = false;
    private AnchorWatchdogService anchorWatchdogService;

    // Service binding
    private LocationService locationService;
    private AnchorWatchdogService watchdogService;
    private boolean isLocationServiceBound = false;
    private boolean isWatchdogServiceBound = false;
    private GNSSConstellationMonitor currentGnssData;

    private final ServiceConnection locationServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            LocationService.LocationServiceBinder binder = (LocationService.LocationServiceBinder) service;
            locationService = binder.getService();
            locationService.addListener(MainActivity.this);
            if (watchdogService != null) {
                locationService.addListener(watchdogService);
            }
            isLocationServiceBound = true;
            // Get current data from service
            currentGnssData = locationService.getConstellationMonitor();
            updateStatusDisplay();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (locationService != null) {
                locationService.removeListener(MainActivity.this);
                if (watchdogService != null) {
                    locationService.removeListener(watchdogService);
                }
            }
            locationService = null;
            isLocationServiceBound = false;
        }
    };

    private final ServiceConnection watchdogServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            AnchorWatchdogService.AnchorWatchdogBinder binder = (AnchorWatchdogService.AnchorWatchdogBinder) service;
            watchdogService = binder.getService();
            if (locationService != null) {
                locationService.addListener(watchdogService);
            }
            isWatchdogServiceBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (locationService != null && watchdogService != null) {
                locationService.removeListener(watchdogService);
            }
            watchdogService = null;
            isWatchdogServiceBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        startLocationService();
        bindToLocationService();

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        Button toggleAnchorButton = findViewById(R.id.toggleAnchorButton);
        EditText anchorDepthInput = findViewById(R.id.anchorDepthInput);
        EditText chainLengthInput = findViewById(R.id.chainLengthInput);
        statusText = findViewById(R.id.statusText);
        satelliteCountText = findViewById(R.id.satelliteCount);
        accuracyText = findViewById(R.id.accuracy);
        qualityText = findViewById(R.id.quality);
        qualityIcon = findViewById(R.id.qualityIcon);
        swoyRadiusView = findViewById(R.id.swoyRadiusView);

        createLocationNotificationChannel();
        createWatchDogNotificationChannel();

        // Load saved anchor point and parameters
        if (prefs.contains(PREF_ANCHOR_LAT) && prefs.contains(PREF_ANCHOR_LON) && isWatchdogServiceRunning) {
            anchorLocation = new Location("");
            anchorLocation.setLatitude(prefs.getFloat(PREF_ANCHOR_LAT, 0));
            anchorLocation.setLongitude(prefs.getFloat(PREF_ANCHOR_LON, 0));
            anchorDepth = prefs.getFloat(PREF_ANCHOR_DEPTH, 0);
            chainLength = prefs.getFloat(PREF_CHAIN_LENGTH, 0);
            driftRadius = calculateDriftRadius(anchorDepth, chainLength);

            // Show saved values in input fields
            anchorDepthInput.setText(String.valueOf(anchorDepth));
            chainLengthInput.setText(String.valueOf(chainLength));

        }

        toggleAnchorButton.setOnClickListener(v -> {
            if (isWatchdogServiceRunning) {
                // Reset anchor
                anchorLocation = null;
                currentLocation = null;
                isWatchdogServiceRunning = false;
                SharedPreferences.Editor editor = prefs.edit();
                editor.clear();
                editor.apply();

                LocationTrackRepository trackRepository = new LocationTrackRepository(this);
                trackRepository.clearTracks();

                // Clear all alarm notifications
                clearAllNotifications();

                // Update status display
                statusText.setText(getString(R.string.anchor_not_set));
                hideSwoyRadiusVisualization();
                updateButtonState(toggleAnchorButton);
                stopWatchdogService();
                Toast.makeText(this, "Anchor reset and location history cleared", Toast.LENGTH_SHORT).show();
            } else {
                // Set anchor
                if (anchorDepthInput.getText().toString().isEmpty() || chainLengthInput.getText().toString().isEmpty()) {
                    Toast.makeText(this, "Please enter both anchor depth and chain length", Toast.LENGTH_SHORT).show();
                    return;
                }
                anchorDepth = Float.parseFloat(anchorDepthInput.getText().toString());
                chainLength = Float.parseFloat(chainLengthInput.getText().toString());
                driftRadius = calculateDriftRadius(anchorDepth, chainLength);
                checkLocationPermissionAndSetAnchor(toggleAnchorButton);
            }
        });

        // Initialize button state
        updateButtonState(toggleAnchorButton);

        // Request notification permission on Android 13+
        checkNotificationPermission();

        // Request battery optimization exemption for reliable background operation
        requestBatteryOptimizationExemption();

        // Request background location permission for Android 10+
        checkBackgroundLocationPermission();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopLocationService();
        unbindFromLocationService();
        stopWatchdogService();
        unbindFromWatchdogService();
    }

    private void bindToLocationService() {
        Intent intent = new Intent(this, LocationService.class);
        bindService(intent, locationServiceConnection, Context.BIND_AUTO_CREATE);
    }

    private void bindToWatchdogService() {
        Intent intent = new Intent(this, AnchorWatchdogService.class);
        bindService(intent, watchdogServiceConnection, Context.BIND_AUTO_CREATE);
    }

    private void unbindFromLocationService() {
        if (isLocationServiceBound) {
            if (locationService != null) {
                locationService.removeListener(this);
            }
            unbindService(locationServiceConnection);
            isLocationServiceBound = false;
            locationService = null;
            currentGnssData = null;
        }
    }

    private void unbindFromWatchdogService() {
        if (isWatchdogServiceBound) {
            if (watchdogService != null) {
                locationService.removeListener(watchdogService);
            }
            unbindService(watchdogServiceConnection);
            isWatchdogServiceBound = false;
            watchdogService = null;
        }
    }

    // LocationUpdateListener implementation
    @Override
    public void onLocationUpdate(Location filteredLocation, GNSSConstellationMonitor gnssData) {
        runOnUiThread(() -> {
            currentLocation = filteredLocation;
            locationAccuracy = filteredLocation.hasAccuracy() ? filteredLocation.getAccuracy() : 0.0f;
            currentGnssData = gnssData;
            updateStatusDisplay();
        });
    }

    @Override
    public void onProviderStatusChange(boolean enabled) {
        runOnUiThread(() -> {
            if (!enabled) {
                Toast.makeText(this, "GPS disabled - anchor monitoring may not work properly", Toast.LENGTH_LONG).show();
            }
        });
    }

    private void updateStatusDisplay() {
        if (!isNull(currentGnssData)) {
            if (!isNull(satelliteCountText)) {
                satelliteCountText.setText(String.format(ENGLISH, "%d/%d", currentGnssData.getTotalUsedInFix(), currentGnssData.getTotalSatellites()));
            }

            if (!isNull(qualityText)) {
                int signalQuality = currentGnssData.getOverallSignalQuality();
                qualityText.setText(String.format(ENGLISH, "%d%%", signalQuality));
                updateSignalQualityIcon(signalQuality);
            }
        }

        if (!isNull(accuracyText)) {
            accuracyText.setText(String.format(ENGLISH, "%.1fm", locationAccuracy));
        }

        if (!isNull(anchorLocation)) {
            // Display anchor info with depth, chain length, and calculated drift radius
            String latDMS = convertToDMS(anchorLocation.getLatitude(), true);
            String lonDMS = convertToDMS(anchorLocation.getLongitude(), false);
            String statusText = String.format(ENGLISH,
                    "Anchor Set\nlat: %s \tlong: %s\nDepth: %.1fm, Chain: %.1fm\nDrift Radius: %.1fm",
                    latDMS, lonDMS,
                    anchorDepth, chainLength, driftRadius);
            this.statusText.setText(statusText);
            updateSwoyRadiusVisualization();
        } else if (!isNull(currentLocation)) {
            // Show current location status when no anchor is set
            String latDMS = convertToDMS(currentLocation.getLatitude(), true);
            String lonDMS = convertToDMS(currentLocation.getLongitude(), false);
            String statusText = String.format(ENGLISH, "Current Location\n lat: %s \tlong: %s", latDMS, lonDMS);
            this.statusText.setText(statusText);
            hideSwoyRadiusVisualization();
        }
    }

    /**
     * Update signal quality icon based on quality percentage (0-100%)
     * Maps to 5 signal strength levels: ic_signal_0 to ic_signal_4
     */
    private void updateSignalQualityIcon(int qualityPercentage) {
        if (qualityIcon == null) return;

        int iconResource;
        if (qualityPercentage <= 0) {
            iconResource = R.drawable.ic_signal_0;      // 0% - No signal
        } else if (qualityPercentage <= 25) {
            iconResource = R.drawable.ic_signal_1;      // 1-25% - Very poor signal
        } else if (qualityPercentage <= 50) {
            iconResource = R.drawable.ic_signal_2;      // 26-50% - Poor signal
        } else if (qualityPercentage <= 75) {
            iconResource = R.drawable.ic_signal_3;      // 51-75% - Good signal
        } else {
            iconResource = R.drawable.ic_signal_4;      // 76-100% - Excellent signal
        }

        qualityIcon.setImageResource(iconResource);
    }

    /**
     * Convert decimal degrees to degrees, minutes, seconds format
     *
     * @param coordinate The decimal degree coordinate
     * @param isLatitude True for latitude (N/S), false for longitude (E/W)
     * @return Formatted DMS string
     */
    private String convertToDMS(double coordinate, boolean isLatitude) {
        String direction;
        if (isLatitude) {
            direction = coordinate >= 0 ? "N" : "S";
        } else {
            direction = coordinate >= 0 ? "E" : "W";
        }

        coordinate = Math.abs(coordinate);
        int degrees = (int) coordinate;
        double minutesDecimal = (coordinate - degrees) * 60;
        int minutes = (int) minutesDecimal;
        double seconds = (minutesDecimal - minutes) * 60;

        return String.format(ENGLISH, "%d°%02d'%05.2f\"%s", degrees, minutes, seconds, direction);
    }

    private void checkLocationPermissionAndSetAnchor(Button toggleButton) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    LOCATION_PERMISSION_REQUEST_CODE);
        } else {
            setAnchorPoint(toggleButton);
        }
    }

    private void updateButtonState(Button toggleButton) {
        if (isWatchdogServiceRunning) {
            toggleButton.setText(getString(R.string.reset_anchor));
        } else {
            toggleButton.setText(getString(R.string.set_anchor));
        }
    }

    private void checkNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        NOTIFICATION_PERMISSION_REQUEST_CODE);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                setAnchorPoint();
            } else {
                Toast.makeText(this, "Location permission denied", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == NOTIFICATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Notification permission granted", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Notification permission denied. Alarms may not be visible.", Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == BACKGROUND_LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Background location permission granted. Anchor monitoring will work reliably in background.", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "Background location denied. Anchor monitoring may stop when app is in background.", Toast.LENGTH_LONG).show();
            }
        }
    }

    /**
     * Calculate the safe drift radius based on anchor depth and chain length.
     * Uses the nautical rule: safe swing radius = sqrt(chain_length² - depth²)
     * This accounts for the catenary curve of the chain.
     */
    private float calculateDriftRadius(float depth, float chainLength) {
        if (chainLength <= depth) {
            //TODO: this is a serious problem in real life - warn user?
            // If chain length is less than or equal to depth, use minimum safe radius
            return Math.max(10.0f, chainLength * 0.5f);
        }

        // Calculate the horizontal distance using Pythagorean theorem
        // This represents the maximum swing radius when the chain is fully extended
        float horizontalDistance = (float) Math.sqrt((chainLength * chainLength) - (depth * depth));

        // Add a safety margin (reduce by 20% for safety)
        return horizontalDistance * 0.8f;
    }

    private void setAnchorPoint(Button toggleButton) {
        // Get current location from service if available, otherwise use a fallback
        Location location = null;
        if (isLocationServiceBound && locationService != null) {
            // Try to get last known location through the service
            // For now, we'll use currentLocation if available
            location = currentLocation;
        }

        // Fallback to LocationManager if service location not available
        if (location == null) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED) {
                android.location.LocationManager locationManager = (android.location.LocationManager) getSystemService(Context.LOCATION_SERVICE);
                location = locationManager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER);
            }
        }

        if (!isNull(location)) {
            anchorLocation = location;
            currentLocation = location;
            locationAccuracy = location.hasAccuracy() ? location.getAccuracy() : 0.0f;
            SharedPreferences.Editor editor = prefs.edit();
            editor.putFloat(PREF_ANCHOR_LAT, (float) location.getLatitude());
            editor.putFloat(PREF_ANCHOR_LON, (float) location.getLongitude());
            editor.putFloat(PREF_ANCHOR_DEPTH, anchorDepth);
            editor.putFloat(PREF_CHAIN_LENGTH, chainLength);
            editor.apply();
            updateStatusDisplay();

            startWatchdogService();
            bindToWatchdogService();
            isWatchdogServiceRunning = true;
            updateButtonState(toggleButton);
        } else {
            Toast.makeText(this, "Unable to get location. Ensure GPS is enabled.", Toast.LENGTH_SHORT).show();
        }
    }

    private void setAnchorPoint() {
        // Fallback method for permission callback - find button reference
        Button toggleButton = findViewById(R.id.toggleAnchorButton);
        setAnchorPoint(toggleButton);
    }

    private void startLocationService() {
        Intent intent = new Intent(this, LocationService.class);
        startForegroundService(intent);
    }

    private void stopLocationService() {
        Intent intent = new Intent(this, LocationService.class);
        stopService(intent);
    }

    private void startWatchdogService() {
        Intent intent = new Intent(this, AnchorWatchdogService.class);
        intent.putExtra("anchorLat", anchorLocation.getLatitude());
        intent.putExtra("anchorLon", anchorLocation.getLongitude());
        intent.putExtra("radius", driftRadius);
        startForegroundService(intent);
    }

    private void stopWatchdogService() {
        unbindFromWatchdogService();
        Intent intent = new Intent(this, AnchorWatchdogService.class);
        stopService(intent);
    }

    /**
     * Show the swoy radius visualization when anchor is set
     */
    private void updateSwoyRadiusVisualization() {
        if (swoyRadiusView != null) {
            swoyRadiusView.setVisibility(View.VISIBLE);
            // Update the view with current position data
            swoyRadiusView.updatePositions(anchorLocation, currentLocation, driftRadius, locationAccuracy);

            // Load and display track history
            LocationTrackRepository trackRepository = new LocationTrackRepository(this);
            List<LocationTrack> tracks = trackRepository.getRecentTracks(100); // Show last 100 tracks for visualization
            swoyRadiusView.setTrackHistory(tracks);
        }
    }

    /**
     * Hide the swoy radius visualization when anchor is not set
     */
    private void hideSwoyRadiusVisualization() {
        if (swoyRadiusView != null) {
            swoyRadiusView.setVisibility(View.GONE);
        }
    }

    /**
     * Request battery optimization exemption to ensure reliable background operation
     */
    private void requestBatteryOptimizationExemption() {
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (!powerManager.isIgnoringBatteryOptimizations(getPackageName())) {
            try {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
                Toast.makeText(this, "Please allow battery optimization exemption for reliable anchor monitoring", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                // Fallback to general battery optimization settings
                try {
                    Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                    startActivity(intent);
                    Toast.makeText(this, "Please find Anchor Alarm in the list and disable battery optimization", Toast.LENGTH_LONG).show();
                } catch (Exception ex) {
                    // If all else fails, just inform the user
                    Toast.makeText(this, "Please disable battery optimization for Anchor Alarm in device settings", Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    /**
     * Request background location permission for Android 10+
     */
    private void checkBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this,
                            new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                            BACKGROUND_LOCATION_PERMISSION_REQUEST_CODE);
                }
            }
        }
    }

    private void clearAllNotifications() {
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        if (!isNull(notificationManager)) {
            notificationManager.cancelAll();
        }
    }

    private void createLocationNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                LOCATION_SERVICE_CHANNEL,
                "Location Service",
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Notifications for Location Service");

        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(channel);
    }

    private void createWatchDogNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                ANCHOR_WATCHDOG_CHANNEL,
                "Anchor Watchdog Alarm",
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Notifications for anchor drift alerts");

        android.net.Uri alarmSound = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM);
        android.media.AudioAttributes audioAttributes = new android.media.AudioAttributes.Builder()
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                .build();

        channel.setSound(alarmSound, audioAttributes);

        // Enable vibration for alarm
        channel.enableVibration(true);
        channel.setVibrationPattern(new long[]{0, 1000, 500, 1000});

        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(channel);
    }
}
