package com.anchoralarm;

import static com.anchoralarm.MainActivity.ANCHOR_ALARM_CHANNEL;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Vibrator;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

public class LocationService extends Service {
    private LocationManager locationManager;
    private LocationListener locationListener;
    private Location anchorLocation;
    private float driftRadius;
    private MediaPlayer alarmMediaPlayer;
    private Vibrator vibrator;
    private PowerManager.WakeLock wakeLock;

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

        // Acquire partial wake lock to ensure GPS stays active
        acquireWakeLock();

        startForeground(1, buildForegroundNotification());
        startLocationUpdates();
        return START_STICKY;
    }

    private void acquireWakeLock() {
        if (wakeLock == null || !wakeLock.isHeld()) {
            PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "AnchorAlarm::LocationWakeLock"
            );
            wakeLock.acquire(10 * 60 * 1000L /*10 minutes*/);
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
        }
    }

    private Notification buildForegroundNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, ANCHOR_ALARM_CHANNEL)
                .setContentTitle("Anchor Alarm Running")
                .setContentText("Monitoring boat position")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentIntent(pendingIntent)
                .build();
    }

    private void startLocationUpdates() {
        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(@NonNull Location currentLocation) {
                float distance = currentLocation.distanceTo(anchorLocation);
                if (distance > driftRadius) {
                    triggerAlarm();
                }
            }

            @Override
            public void onProviderEnabled(@NonNull String provider) {
            }

            @Override
            public void onProviderDisabled(@NonNull String provider) {
                stopSelf();
                triggerAlarm(); // Alert if GPS is disabled
            }
        };

        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            // Request high-priority, frequent location updates for safety-critical app
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    5000, // 5 seconds - more frequent for anchor monitoring
                    0, // No minimum distance - detect any movement
                    locationListener);

            // Also request network provider as backup
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        10000, // 10 seconds for network provider
                        0,
                        locationListener);
            }
        }
    }

    private void triggerAlarm() {
        // Play alarm sound directly using MediaPlayer
        playAlarmSound();

        // Trigger vibration
        triggerVibration();

        // Show notification
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, ANCHOR_ALARM_CHANNEL)
                .setContentTitle("Anchor Alarm")
                .setContentText("Boat has drifted beyond set radius or GPS disabled!")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);

        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        notificationManager.notify(2, builder.build());
    }

    private void playAlarmSound() {
        try {
            // Stop any currently playing alarm
            if (alarmMediaPlayer != null) {
                alarmMediaPlayer.stop();
                alarmMediaPlayer.release();
            }

            // Get alarm sound URI, with fallbacks
            Uri alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (alarmUri == null) {
                alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            }
            if (alarmUri == null) {
                alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            }

            if (alarmUri != null) {
                alarmMediaPlayer = new MediaPlayer();
                alarmMediaPlayer.setDataSource(this, alarmUri);

                // Set audio attributes for alarm
                AudioAttributes audioAttributes = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build();
                alarmMediaPlayer.setAudioAttributes(audioAttributes);

                // Set to maximum volume for alarm stream
                AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
                int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM);
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0);

                alarmMediaPlayer.setLooping(true); // Loop the alarm sound
                alarmMediaPlayer.prepare();
                alarmMediaPlayer.start();

                // Stop alarm after 30 seconds to prevent infinite playing
                new android.os.Handler().postDelayed(() -> {
                    if (alarmMediaPlayer != null && alarmMediaPlayer.isPlaying()) {
                        alarmMediaPlayer.stop();
                        alarmMediaPlayer.release();
                        alarmMediaPlayer = null;
                    }
                }, 30000);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void triggerVibration() {
        try {
            vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                // Create vibration pattern: pause, vibrate, pause, vibrate...
                long[] pattern = {0, 1000, 500, 1000, 500, 1000};
                vibrator.vibrate(pattern, 0); // Repeat the pattern

                // Stop vibration after 30 seconds
                new android.os.Handler().postDelayed(() -> {
                    if (vibrator != null) {
                        vibrator.cancel();
                    }
                }, 30000);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (locationListener != null) {
            locationManager.removeUpdates(locationListener);
        }

        // Release wake lock
        releaseWakeLock();

        // Cleanup MediaPlayer and Vibrator
        if (alarmMediaPlayer != null) {
            if (alarmMediaPlayer.isPlaying()) {
                alarmMediaPlayer.stop();
            }
            alarmMediaPlayer.release();
            alarmMediaPlayer = null;
        }

        if (vibrator != null) {
            vibrator.cancel();
            vibrator = null;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
