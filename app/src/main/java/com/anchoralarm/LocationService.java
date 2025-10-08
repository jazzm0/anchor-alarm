package com.anchoralarm;

import static java.util.Objects.isNull;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.location.GnssStatus;
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

import com.anchoralarm.location.GnssConstellationMonitor;
import com.anchoralarm.repository.LocationTrackRepository;

public class LocationService extends Service {

    private static final int LOCATION_UPDATE_MIN_TIME = 1000;
    private LocationManager locationManager;
    private LocationListener locationListener;
    private Location anchorLocation;
    private float driftRadius;
    private MediaPlayer alarmMediaPlayer;
    private Vibrator vibrator;
    private PowerManager.WakeLock wakeLock;
    private android.os.Handler alarmHandler;
    private Runnable alarmStopRunnable;
    private Runnable vibrationStopRunnable;
    private boolean isAlarmActive = false;
    private LocationTrackRepository trackRepository;
    private GnssConstellationMonitor constellationMonitor;
    private GnssStatus.Callback gnssStatusCallback;

    @Override
    public void onCreate() {
        super.onCreate();
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        alarmHandler = new android.os.Handler();
        trackRepository = new LocationTrackRepository(this);

        // Initialize GNSS constellation monitor for Android N+ (API 24+)
        constellationMonitor = new GnssConstellationMonitor();
        setupGnssStatusCallback();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Check if this is a stop alarms command
        if (!isNull(intent) && "STOP_ALARMS".equals(intent.getAction())) {
            stopAllAlarms();
            isAlarmActive = false;
            return START_NOT_STICKY;
        }

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
        if (isNull(wakeLock) || !wakeLock.isHeld()) {
            PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "AnchorAlarm::LocationWakeLock"
            );
            wakeLock.acquire(24 * 60 * 60 * 1000L /*1 day*/);
        }
    }

    private void releaseWakeLock() {
        if (!isNull(wakeLock) && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
        }
    }

    private void setupGnssStatusCallback() {
        gnssStatusCallback = new GnssStatus.Callback() {
            @Override
            public void onSatelliteStatusChanged(GnssStatus status) {
                if (!isNull(constellationMonitor)) {
                    constellationMonitor.processGnssStatus(status);
                    // Update foreground notification with constellation info
                    updateForegroundNotification();
                }
            }

            @Override
            public void onFirstFix(int ttffMillis) {
                // Time to first fix - could be useful for accuracy assessment
            }

            @Override
            public void onStarted() {
                // GNSS engine started
            }

            @Override
            public void onStopped() {
                // GNSS engine stopped
            }
        };
    }

    private Notification buildForegroundNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        String contentText = "Monitoring boat position";

        // Add constellation info if available
        if (!isNull(constellationMonitor)) {
            int totalSats = constellationMonitor.getTotalSatellites();
            int usedSats = constellationMonitor.getTotalUsedInFix();
            if (totalSats > 0) {
                contentText = String.format("Monitoring - %d/%d satellites", usedSats, totalSats);
            }
        }

        return new NotificationCompat.Builder(this, "ANCHOR_ALARM_CHANNEL")
                .setContentTitle("Anchor Alarm Running")
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentIntent(pendingIntent)
                .build();
    }

    private void updateForegroundNotification() {
        Notification notification = buildForegroundNotification();
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        if (!isNull(notificationManager)) {
            notificationManager.notify(1, notification);
        }
    }

    private void startLocationUpdates() {
        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(@NonNull Location currentLocation) {
                // Track the location
                trackRepository.addLocationTrack(currentLocation, anchorLocation);

                float distance = currentLocation.distanceTo(anchorLocation);
                if (distance > driftRadius) {
                    // Only trigger alarm if not already active
                    if (!isAlarmActive) {
                        triggerAlarm();
                    }
                } else {
                    // Boat is back within safe radius - reset alarm state
                    if (isAlarmActive) {
                        stopAllAlarms();
                        isAlarmActive = false;
                    }
                }
            }

            @Override
            public void onProviderEnabled(@NonNull String provider) {
            }

            @Override
            public void onProviderDisabled(@NonNull String provider) {
                stopSelf();
                if (!isAlarmActive) {
                    triggerAlarm();
                }
            }
        };

        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            // Request high-priority, frequent location updates for safety-critical app
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    LOCATION_UPDATE_MIN_TIME,
                    0,
                    locationListener);

            // Also request network provider as backup
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        10000, // 10 seconds for network provider
                        0,
                        locationListener);
            }

            // Register GNSS status callback for constellation monitoring (Android N+)
            if (gnssStatusCallback != null) {
                locationManager.registerGnssStatusCallback(gnssStatusCallback);
            }
        }
    }

    private void triggerAlarm() {
        // Set alarm as active to prevent retriggering
        isAlarmActive = true;

        // Play alarm sound directly using MediaPlayer
        playAlarmSound();

        // Trigger vibration
        triggerVibration();

        // Show notification
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "ANCHOR_ALARM_CHANNEL")
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
            stopAlarmSound();

            // Get alarm sound URI, with fallbacks
            Uri alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (isNull(alarmUri)) {
                alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            }
            if (isNull(alarmUri)) {
                alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            }

            if (!isNull(alarmUri)) {
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

                alarmMediaPlayer.setLooping(true);
                alarmMediaPlayer.prepare();
                alarmMediaPlayer.start();

                // Stop alarm after 5 minutes seconds to prevent infinite playing
                if (!isNull(alarmStopRunnable)) {
                    alarmHandler.removeCallbacks(alarmStopRunnable);
                }
                alarmStopRunnable = () -> {
                    stopAlarmSound();
                    isAlarmActive = false; // Reset alarm state after automatic timeout
                };
                alarmHandler.postDelayed(alarmStopRunnable, 5 * 60 * 1000);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void triggerVibration() {
        try {
            // Stop any current vibration first
            stopVibration();

            vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (!isNull(vibrator) && vibrator.hasVibrator()) {
                // Create vibration pattern: pause, vibrate, pause, vibrate...
                long[] pattern = {0, 1000, 500, 1000, 500, 1000};
                vibrator.vibrate(pattern, 0); // Repeat the pattern

                // Stop vibration after 30 seconds
                if (!isNull(vibrationStopRunnable)) {
                    alarmHandler.removeCallbacks(vibrationStopRunnable);
                }
                vibrationStopRunnable = this::stopVibration;
                alarmHandler.postDelayed(vibrationStopRunnable, 30000);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Stop alarm sound and clean up MediaPlayer
     */
    private void stopAlarmSound() {
        try {
            // Cancel any pending alarm stop runnable
            if (!isNull(alarmStopRunnable) && !isNull(alarmHandler)) {
                alarmHandler.removeCallbacks(alarmStopRunnable);
                alarmStopRunnable = null;
            }

            // Stop and release MediaPlayer
            if (!isNull(alarmMediaPlayer)) {
                if (alarmMediaPlayer.isPlaying()) {
                    alarmMediaPlayer.stop();
                }
                alarmMediaPlayer.release();
                alarmMediaPlayer = null;
            }
        } catch (Exception e) {
            e.printStackTrace();
            // Ensure MediaPlayer is nullified even if there's an exception
            alarmMediaPlayer = null;
        }
    }

    /**
     * Stop vibration
     */
    private void stopVibration() {
        try {
            // Cancel any pending vibration stop runnable
            if (!isNull(vibrationStopRunnable) && !isNull(alarmHandler)) {
                alarmHandler.removeCallbacks(vibrationStopRunnable);
                vibrationStopRunnable = null;
            }

            // Stop vibrator
            if (!isNull(vibrator)) {
                vibrator.cancel();
                vibrator = null;
            }
        } catch (Exception e) {
            e.printStackTrace();
            // Ensure vibrator is nullified even if there's an exception
            vibrator = null;
        }
    }

    /**
     * Stop all alarms (sound and vibration)
     */
    public void stopAllAlarms() {
        stopAlarmSound();
        stopVibration();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        // Stop all alarms immediately
        stopAllAlarms();

        // Remove location updates
        if (!isNull(locationListener)) {
            locationManager.removeUpdates(locationListener);
            locationListener = null;
        }

        // Unregister GNSS status callback
        if (gnssStatusCallback != null) {
            locationManager.unregisterGnssStatusCallback(gnssStatusCallback);
            gnssStatusCallback = null;
        }

        // Release wake lock
        releaseWakeLock();

        // Clean up handler callbacks
        if (!isNull(alarmHandler)) {
            alarmHandler.removeCallbacksAndMessages(null);
            alarmHandler = null;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
