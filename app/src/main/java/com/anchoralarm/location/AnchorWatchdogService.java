package com.anchoralarm.location;

import static java.util.Objects.isNull;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.anchoralarm.MainActivity;
import com.anchoralarm.R;
import com.anchoralarm.repository.LocationTrackRepository;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles anchor drift detection and alarm management
 * Receives location updates from LocationService and triggers alarms when drift is detected
 */
public class AnchorWatchdogService extends Service implements LocationUpdateListener {

    public static final String ANCHOR_WATCHDOG_CHANNEL = "ANCHOR_WATCHDOG_CHANNEL";
    private static final int FOREGROUND_NOTIFICATION_ID = 2;
    private static final int ALARM_NOTIFICATION_ID = 3;


    private static final String TAG = "AnchorWatchdog";

    private Location anchorLocation;
    private float driftRadius;
    private volatile boolean isAlarmTriggered = false;
    private volatile boolean isAlarmActive = false;

    // Alarm components
    private MediaPlayer alarmMediaPlayer;
    private Vibrator vibrator;
    private PowerManager.WakeLock wakeLock;
    private final IBinder binder = new AnchorWatchdogService.AnchorWatchdogBinder();
    private LocationTrackRepository trackRepository;
    private final List<AlarmStateListener> alarmStateListeners = new ArrayList<>();

    public AnchorWatchdogService() {
    }

    public class AnchorWatchdogBinder extends Binder {
        public AnchorWatchdogService getService() {
            return AnchorWatchdogService.this;
        }
    }

    public interface AlarmStateListener {
        void onAlarmStateChanged(boolean active);
    }

    public void addAlarmStateListener(AlarmStateListener l) {
        if (l == null) return;
        synchronized (alarmStateListeners) {
            if (!alarmStateListeners.contains(l)) {
                alarmStateListeners.add(l);
            }
        }
    }

    public void removeAlarmStateListener(AlarmStateListener l) {
        synchronized (alarmStateListeners) {
            alarmStateListeners.remove(l);
        }
    }

    @Override
    public void onLocationUpdate(Location filteredLocation, GNSSConstellationMonitor gnssData) {
        if (anchorLocation == null) {
            return; // No anchor set yet
        }

        trackRepository.addLocationTrack(filteredLocation);
        float distance = filteredLocation.distanceTo(anchorLocation);
        if (distance > driftRadius) {
            if (!isAlarmTriggered) {
                Log.i(TAG, "Drift detected: " + distance + "m > " + driftRadius + "m");
                triggerAlarm();
            }
        } else {
            if (isAlarmTriggered) {
                Log.i(TAG, "Back within radius: " + distance + "m <= " + driftRadius + "m");
                stopAllAlarms();
            }
        }
    }

    @Override
    public void onProviderStatusChange(boolean enabled) {
        if (!enabled && !isAlarmTriggered) {
            // GPS disabled - trigger alarm
            Log.w(TAG, "GPS provider disabled - triggering alarm");
            triggerAlarm();
        }
    }

    private void acquireWakeLock() {
        if (isNull(wakeLock) || !wakeLock.isHeld()) {
            PowerManager powerManager = (PowerManager) this.getSystemService(Context.POWER_SERVICE);
            wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "AnchorAlarm::WatchdogWakeLock"
            );
            wakeLock.acquire(24 * 60 * 60 * 1000L); // 24 hours
        }
    }

    private void releaseWakeLock() {
        if (!isNull(wakeLock) && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
        }
    }

    private void triggerAlarm() {
        isAlarmTriggered = true;
        playAlarmSound();
        triggerVibration();
        showAlarmNotification();
        notifyAlarmStateChanged();
        Log.w(TAG, "Alarm triggered");
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

                AudioManager audioManager = (AudioManager) this.getSystemService(Context.AUDIO_SERVICE);
                int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM);
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0);

                alarmMediaPlayer.setLooping(true);
                alarmMediaPlayer.prepare();
                alarmMediaPlayer.start();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error playing alarm sound", e);
        }
    }

    private void triggerVibration() {
        try {
            stopVibration();

            // Obtain vibrator with backward compatibility
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                if (vm != null) {
                    vibrator = vm.getDefaultVibrator();
                }
            } else {
                vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            }

            if (!isNull(vibrator) && vibrator.hasVibrator()) {
                long[] pattern = {0, 1000, 500, 1000, 500, 1000};
                VibrationEffect effect = VibrationEffect.createWaveform(pattern, 0);
                vibrator.vibrate(effect);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error triggering vibration", e);
        }
    }

    private void showAlarmNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, ANCHOR_WATCHDOG_CHANNEL)
                .setContentTitle("Anchor Alarm")
                .setContentText("Boat has drifted beyond set radius or GPS disabled!")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(ALARM_NOTIFICATION_ID, builder.build());
        }
    }

    private void clearAlarmNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(ALARM_NOTIFICATION_ID);
        }
    }

    private void stopAlarmSound() {
        try {
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
            if (!isNull(vibrator)) {
                vibrator.cancel();
                vibrator = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error stopping vibration", e);
            vibrator = null;
        }
    }

    /**
     * Stop all alarm activities (sound, vibration, notifications)
     */
    public void stopAllAlarms() {
        stopAlarmSound();
        stopVibration();
        clearAlarmNotification();
        isAlarmTriggered = false;
        notifyAlarmStateChanged();
        Log.i(TAG, "All alarms stopped");
    }

    /**
     * Clean up resources when watchdog is no longer needed
     */
    public void destroy() {
        this.isAlarmActive = false;
        stopAllAlarms();
        releaseWakeLock();
        Log.i(TAG, "AnchorWatchdog destroyed");
    }

    private void notifyAlarmStateChanged() {
        boolean active = isAlarmActive;
        List<AlarmStateListener> copy;
        synchronized (alarmStateListeners) {
            copy = new ArrayList<>(alarmStateListeners);
        }
        for (AlarmStateListener l : copy) {
            try {
                l.onAlarmStateChanged(active);
            } catch (Exception ignored) {
            }
        }
    }

    public boolean isAlarmActive() {
        return isAlarmActive;
    }

    private Notification buildForegroundNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 1, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, ANCHOR_WATCHDOG_CHANNEL)
                .setContentTitle("Anchor Watchdog Running")
                .setContentText("Monitoring boat position")
                .setSmallIcon(R.drawable.ic_anchor_watchdog)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        acquireWakeLock();
        trackRepository = new LocationTrackRepository(this);
        Log.i(TAG, "AnchorWatchdogService created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        double anchorLat = intent.getDoubleExtra("anchorLat", 0);
        double anchorLon = intent.getDoubleExtra("anchorLon", 0);
        this.driftRadius = intent.getFloatExtra("radius", 0);

        this.anchorLocation = new Location("");
        this.anchorLocation.setLatitude(anchorLat);
        this.anchorLocation.setLongitude(anchorLon);
        this.isAlarmActive = true;
        if (isAlarmTriggered) {
            stopAllAlarms();
        }

        // Start foreground service
        startForeground(FOREGROUND_NOTIFICATION_ID, buildForegroundNotification());

        Log.i(TAG, "AnchorWatchdogService started! Anchor set at " + this.anchorLocation.getLatitude() + "," + this.anchorLocation.getLongitude() + " with radius " + this.driftRadius + "m");

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        destroy();
        Log.i(TAG, "AnchorWatchdogService destroying");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG, "AnchorWatchdogService bound");
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.i(TAG, "AnchorWatchdogService unbound");
        return super.onUnbind(intent);
    }
}
