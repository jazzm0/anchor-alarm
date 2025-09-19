package com.anchoralarm;

import static java.util.Objects.isNull;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1;
    private static final int NOTIFICATION_PERMISSION_REQUEST_CODE = 2;
    private static final String PREFS_NAME = "AnchorPrefs";
    private static final String PREF_ANCHOR_LAT = "anchorLat";
    private static final String PREF_ANCHOR_LON = "anchorLon";
    private static final String PREF_RADIUS = "radius";

    private LocationManager locationManager;
    private Location anchorLocation;
    private Location currentLocation;
    private float driftRadius;
    private TextView statusText;
    private SharedPreferences prefs;
    private int satelliteCount = 0;
    private float locationAccuracy = 0.0f;

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            currentLocation = location;
            locationAccuracy = location.hasAccuracy() ? location.getAccuracy() : 0.0f;
            updateStatusDisplay();
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }
    };

    private final GnssStatus.Callback gnssStatusCallback = new GnssStatus.Callback() {
        @Override
        public void onSatelliteStatusChanged(GnssStatus status) {
            satelliteCount = status.getSatelliteCount();
            updateStatusDisplay();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        Button setAnchorButton = findViewById(R.id.setAnchorButton);
        Button resetAnchorButton = findViewById(R.id.resetAnchorButton);
        EditText radiusInput = findViewById(R.id.radiusInput);
        statusText = findViewById(R.id.statusText);

        createNotificationChannel();

        // Load saved anchor point and radius
        if (prefs.contains(PREF_ANCHOR_LAT) && prefs.contains(PREF_ANCHOR_LON)) {
            anchorLocation = new Location("");
            anchorLocation.setLatitude(prefs.getFloat(PREF_ANCHOR_LAT, 0));
            anchorLocation.setLongitude(prefs.getFloat(PREF_ANCHOR_LON, 0));
            driftRadius = prefs.getFloat(PREF_RADIUS, 0);
            // Initial display with placeholder values for precision and satellites
            statusText.setText(getString(R.string.anchor_set_with_radius,
                    anchorLocation.getLatitude(), anchorLocation.getLongitude(), driftRadius, 0.0f, 0));
        }

        setAnchorButton.setOnClickListener(v -> {
            if (radiusInput.getText().toString().isEmpty()) {
                Toast.makeText(this, "Please enter a radius", Toast.LENGTH_SHORT).show();
                return;
            }
            driftRadius = Float.parseFloat(radiusInput.getText().toString());
            checkLocationPermissionAndSetAnchor();
        });

        resetAnchorButton.setOnClickListener(v -> {
            stopLocationService();
            anchorLocation = null;
            SharedPreferences.Editor editor = prefs.edit();
            editor.clear();
            editor.apply();
            // Update status display - will show current location if available, otherwise "Anchor not set"
            if (currentLocation != null) {
                updateStatusDisplay();
            } else {
                statusText.setText(getString(R.string.anchor_not_set));
            }
            Toast.makeText(this, "Anchor reset", Toast.LENGTH_SHORT).show();
        });

        // Request notification permission on Android 13+
        checkNotificationPermission();

        // Start location updates if permission is granted
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopLocationUpdates();
    }

    private void startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 1, locationListener);
        locationManager.registerGnssStatusCallback(gnssStatusCallback);
    }

    private void stopLocationUpdates() {
        locationManager.removeUpdates(locationListener);
        locationManager.unregisterGnssStatusCallback(gnssStatusCallback);
    }

    private void updateStatusDisplay() {
        if (!isNull(anchorLocation)) {
            statusText.setText(getString(R.string.anchor_set_with_radius,
                    anchorLocation.getLatitude(), anchorLocation.getLongitude(), driftRadius,
                    locationAccuracy, satelliteCount));
        } else if (!isNull(currentLocation)) {
            // Show current location status when no anchor is set
            statusText.setText(getString(R.string.current_location_status,
                    currentLocation.getLatitude(), currentLocation.getLongitude(),
                    locationAccuracy, satelliteCount));
        }
    }

    private void checkLocationPermissionAndSetAnchor() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    LOCATION_PERMISSION_REQUEST_CODE);
        } else {
            setAnchorPoint();
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
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLocationUpdates();
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
        }
    }

    private void setAnchorPoint() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        Location location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        if (!isNull(location)) {
            anchorLocation = location;
            currentLocation = location;
            locationAccuracy = location.hasAccuracy() ? location.getAccuracy() : 0.0f;
            SharedPreferences.Editor editor = prefs.edit();
            editor.putFloat(PREF_ANCHOR_LAT, (float) location.getLatitude());
            editor.putFloat(PREF_ANCHOR_LON, (float) location.getLongitude());
            editor.putFloat(PREF_RADIUS, driftRadius);
            editor.apply();
            statusText.setText(getString(R.string.anchor_set_with_radius,
                    location.getLatitude(), location.getLongitude(), driftRadius, locationAccuracy, satelliteCount));
            startLocationService();
        } else {
            Toast.makeText(this, "Unable to get location. Ensure GPS is enabled.", Toast.LENGTH_SHORT).show();
        }
    }

    private void startLocationService() {
        Intent intent = new Intent(this, LocationService.class);
        intent.putExtra("anchorLat", anchorLocation.getLatitude());
        intent.putExtra("anchorLon", anchorLocation.getLongitude());
        intent.putExtra("radius", driftRadius);
        startForegroundService(intent);
    }

    private void stopLocationService() {
        Intent intent = new Intent(this, LocationService.class);
        stopService(intent);
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                "ANCHOR_ALARM_CHANNEL",
                "Anchor Alarm",
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Notifications for anchor drift alerts");

        // Configure sound for alarm notifications
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
