package com.anchoralarm;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1;
    private static final String PREFS_NAME = "AnchorPrefs";
    private static final String PREF_ANCHOR_LAT = "anchorLat";
    private static final String PREF_ANCHOR_LON = "anchorLon";
    private static final String PREF_RADIUS = "radius";

    private LocationManager locationManager;
    private Location anchorLocation;
    private float driftRadius;
    private TextView statusText;
    private SharedPreferences prefs;

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
            statusText.setText("Anchor set at: " + anchorLocation.getLatitude() + ", " + anchorLocation.getLongitude() + "\nRadius: " + driftRadius + " meters");
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
            statusText.setText("Anchor not set");
            Toast.makeText(this, "Anchor reset", Toast.LENGTH_SHORT).show();
        });
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

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                setAnchorPoint();
            } else {
                Toast.makeText(this, "Location permission denied", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void setAnchorPoint() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        Location location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        if (location != null) {
            anchorLocation = location;
            SharedPreferences.Editor editor = prefs.edit();
            editor.putFloat(PREF_ANCHOR_LAT, (float) location.getLatitude());
            editor.putFloat(PREF_ANCHOR_LON, (float) location.getLongitude());
            editor.putFloat(PREF_RADIUS, driftRadius);
            editor.apply();
            statusText.setText("Anchor set at: " + location.getLatitude() + ", " + location.getLongitude() + "\nRadius: " + driftRadius + " meters");
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
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(channel);
    }
}