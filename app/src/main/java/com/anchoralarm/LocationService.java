
package com.anchoralarm;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;

public class LocationService extends Service {
    private LocationManager locationManager;
    private LocationListener locationListener;
    private Location anchorLocation;
    private float driftRadius;

    @Override
    public void onCreate() {
        super.onCreate();
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        double anchorLat = intent.getDoubleExtra("anchorLat", 0);
        double anchorLon = intent.getDoubleExtra("anchorLon", 0);
        driftRadius = intent.getFloatExtra("radius", 0);

        anchorLocation = new Location("");
        anchorLocation.setLatitude(anchorLat);
        anchorLocation.setLongitude(anchorLon);

        startForeground(1, buildForegroundNotification());
        startLocationUpdates();
        return START_STICKY;
    }

    private Notification buildForegroundNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, "ANCHOR_ALARM_CHANNEL")
                .setContentTitle("Anchor Alarm Running")
                .setContentText("Monitoring anchor position")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentIntent(pendingIntent)
                .build();
    }

    private void startLocationUpdates() {
        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location currentLocation) {
                float distance = currentLocation.distanceTo(anchorLocation);
                if (distance > driftRadius) {
                    triggerAlarm();
                }
            }

            @Override
            public void onStatusChanged(String provider, int status, android.os.Bundle extras) {}
            @Override
            public void onProviderEnabled(String provider) {}
            @Override
            public void onProviderDisabled(String provider) {
                stopSelf();
                triggerAlarm(); // Alert if GPS is disabled
            }
        };

        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    10000, // 10 seconds
                    0, // No minimum distance
                    locationListener);
        }
    }

    private void triggerAlarm() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "ANCHOR_ALARM_CHANNEL")
                .setContentTitle("Anchor Alarm")
                .setContentText("Boat has drifted beyond set radius or GPS disabled!")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true);

        Uri alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        builder.setSound(alarmSound);

        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        notificationManager.notify(2, builder.build());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (locationListener != null) {
            locationManager.removeUpdates(locationListener);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}