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
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.anchoralarm.location.GNSSConstellationMonitor;
import com.anchoralarm.location.filter.KalmanLocationFilter;
import com.anchoralarm.location.filter.OutlierDetector;
import com.anchoralarm.repository.LocationTrackRepository;

public class LocationService extends Service {

    private static final String TAG = "LocationService";
    private static final int LOCATION_UPDATE_MIN_TIME = 1000;
    private LocationManager locationManager;
    private LocationListener locationListener;
    private Location anchorLocation;
    private Location previousLocation;
    private float driftRadius;
    private MediaPlayer alarmMediaPlayer;
    private Vibrator vibrator;
    private PowerManager.WakeLock wakeLock;
    private android.os.Handler alarmHandler;
    private Runnable alarmStopRunnable;
    private Runnable vibrationStopRunnable;
    private boolean isAlarmActive = false;
    private LocationTrackRepository trackRepository;
    private GNSSConstellationMonitor constellationMonitor;
    private GnssStatus.Callback gnssStatusCallback;
    private final KalmanLocationFilter kalmanLocationFilter = new KalmanLocationFilter();
    private final OutlierDetector outlierDetector = new OutlierDetector();

    @Override
    public void onCreate() {
        super.onCreate();
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        alarmHandler = new android.os.Handler();
        trackRepository = new LocationTrackRepository(this);

        constellationMonitor = new GNSSConstellationMonitor();
        reset();
        setupGNSSStatusCallback();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!isNull(intent) && "STOP_ALARMS".equals(intent.getAction())) {
            stopAllAlarms();
            isAlarmActive = false;
            // Clear alarm notification
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.cancel(2); // Cancel the alarm notification
            }
            return START_NOT_STICKY;
        }

        reset();

        double anchorLat = intent.getDoubleExtra("anchorLat", 0);
        double anchorLon = intent.getDoubleExtra("anchorLon", 0);
        driftRadius = intent.getFloatExtra("radius", 0);

        anchorLocation = new Location("");
        anchorLocation.setLatitude(anchorLat);
        anchorLocation.setLongitude(anchorLon);

        acquireWakeLock();

        startForeground(1, buildForegroundNotification());
        startLocationUpdates();
        return START_STICKY;
    }

    private void reset() {
        outlierDetector.reset();
        kalmanLocationFilter.reset();
    }

    private void acquireWakeLock() {
        if (isNull(wakeLock) || !wakeLock.isHeld()) {
            PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "AnchorAlarm::LocationWakeLock"
            );
            wakeLock.acquire(24 * 60 * 60 * 1000L);
        }
    }

    private void releaseWakeLock() {
        if (!isNull(wakeLock) && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
        }
    }

    private void setupGNSSStatusCallback() {
        gnssStatusCallback = new GnssStatus.Callback() {
            @Override
            public void onSatelliteStatusChanged(@NonNull GnssStatus status) {
                if (!isNull(constellationMonitor)) {
                    constellationMonitor.processGNSSStatus(status);
                }
            }
        };
    }

    private Notification buildForegroundNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        String contentText = "Monitoring boat position";

        return new NotificationCompat.Builder(this, "ANCHOR_ALARM_CHANNEL")
                .setContentTitle("Anchor Alarm Running")
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentIntent(pendingIntent)
                .build();
    }

    private void startLocationUpdates() {
        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(@NonNull Location currentLocation) {
                if (!isNull(previousLocation) && outlierDetector.isOutlier(currentLocation, previousLocation, currentLocation.getTime() - previousLocation.getTime())) {
                    return; // Reject outlier
                }
                previousLocation = currentLocation;

                var filteredLocation = kalmanLocationFilter.filter(currentLocation, currentLocation.getAccuracy());

                trackRepository.addLocationTrack(filteredLocation);

                float distance = filteredLocation.distanceTo(anchorLocation);
                if (distance > driftRadius) {
                    if (!isAlarmActive) {
                        triggerAlarm();
                    }
                } else {
                    if (isAlarmActive) {
                        stopAllAlarms();
                        isAlarmActive = false;
                    }
                }
            }

            @Override
            public void onProviderDisabled(@NonNull String provider) {
                stopSelf();
                reset();
                if (!isAlarmActive) {
                    triggerAlarm();
                }
            }
        };

        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    LOCATION_UPDATE_MIN_TIME,
                    0,
                    locationListener);

            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        10000,
                        0,
                        locationListener);
            }

            if (gnssStatusCallback != null) {
                locationManager.registerGnssStatusCallback(gnssStatusCallback);
            }
        }
    }

    private void triggerAlarm() {
        isAlarmActive = true;
        playAlarmSound();
        triggerVibration();

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
            stopAlarmSound();

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

                AudioAttributes audioAttributes = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build();
                alarmMediaPlayer.setAudioAttributes(audioAttributes);

                AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
                int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM);
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0);

                alarmMediaPlayer.setLooping(true);
                alarmMediaPlayer.prepare();
                alarmMediaPlayer.start();

                if (!isNull(alarmStopRunnable)) {
                    alarmHandler.removeCallbacks(alarmStopRunnable);
                }
                alarmStopRunnable = () -> {
                    stopAlarmSound();
                    isAlarmActive = false;
                };
                alarmHandler.postDelayed(alarmStopRunnable, 5 * 60 * 1000);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error playing alarm sound", e);
        }
    }

    private void triggerVibration() {
        try {
            stopVibration();

            vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (!isNull(vibrator) && vibrator.hasVibrator()) {
                long[] pattern = {0, 1000, 500, 1000, 500, 1000};
                VibrationEffect effect = VibrationEffect.createWaveform(pattern, 0);
                vibrator.vibrate(effect);

                if (!isNull(vibrationStopRunnable)) {
                    alarmHandler.removeCallbacks(vibrationStopRunnable);
                }
                vibrationStopRunnable = this::stopVibration;
                alarmHandler.postDelayed(vibrationStopRunnable, 30000);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error triggering vibration", e);
        }
    }

    private void stopAlarmSound() {
        try {
            if (!isNull(alarmStopRunnable) && !isNull(alarmHandler)) {
                alarmHandler.removeCallbacks(alarmStopRunnable);
                alarmStopRunnable = null;
            }

            if (!isNull(alarmMediaPlayer)) {
                try {
                    if (alarmMediaPlayer.isPlaying()) {
                        alarmMediaPlayer.stop();
                    }
                } catch (IllegalStateException e) {
                    Log.w(TAG, "MediaPlayer was in invalid state when stopping", e);
                }
                try {
                    alarmMediaPlayer.release();
                } catch (Exception e) {
                    Log.w(TAG, "Error releasing MediaPlayer", e);
                }
                alarmMediaPlayer = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error stopping alarm sound", e);
            alarmMediaPlayer = null;
        }
    }

    private void stopVibration() {
        try {
            if (!isNull(vibrationStopRunnable) && !isNull(alarmHandler)) {
                alarmHandler.removeCallbacks(vibrationStopRunnable);
                vibrationStopRunnable = null;
            }

            if (!isNull(vibrator)) {
                vibrator.cancel();
                vibrator = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error stopping vibration", e);
            vibrator = null;
        }
    }

    public void stopAllAlarms() {
        stopAlarmSound();
        stopVibration();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopAllAlarms();
        reset();

        if (!isNull(locationListener)) {
            locationManager.removeUpdates(locationListener);
            locationListener = null;
        }

        if (gnssStatusCallback != null) {
            locationManager.unregisterGnssStatusCallback(gnssStatusCallback);
            gnssStatusCallback = null;
        }

        releaseWakeLock();

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
