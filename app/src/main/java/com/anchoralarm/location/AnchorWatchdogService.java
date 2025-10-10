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
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.anchoralarm.MainActivity;
import com.anchoralarm.R;

/**
 * Handles anchor drift detection and alarm management
 * Receives location updates from LocationService and triggers alarms when drift is detected
 */
public class AnchorWatchdogService extends Service implements LocationUpdateListener {

    public static final String ANCHOR_WATCHDOG_CHANNEL = "ANCHOR_WATCHDOG_CHANNEL";

    private static final String TAG = "AnchorWatchdog";

    private Context context;
    private Location anchorLocation;
    private float driftRadius;
    private boolean isAlarmActive = false;

    // Alarm components
    private MediaPlayer alarmMediaPlayer;
    private Vibrator vibrator;
    private PowerManager.WakeLock wakeLock;
    private Handler alarmHandler;
    private Runnable alarmStopRunnable;
    private Runnable vibrationStopRunnable;
    private final IBinder binder = new AnchorWatchdogService.AnchorWatchdogBinder();

    public AnchorWatchdogService() {
    }

    public class AnchorWatchdogBinder extends Binder {
        public AnchorWatchdogService getService() {
            return AnchorWatchdogService.this;
        }
    }


    public AnchorWatchdogService(Context context) {
        this.context = context;
        this.alarmHandler = new Handler();
        acquireWakeLock();
    }

    @Override
    public void onLocationUpdate(Location filteredLocation, GNSSConstellationMonitor gnssData) {
        if (anchorLocation == null) {
            return; // No anchor set yet
        }

        float distance = filteredLocation.distanceTo(anchorLocation);
        if (distance > driftRadius) {
            if (!isAlarmActive) {
                Log.i(TAG, "Drift detected: " + distance + "m > " + driftRadius + "m");
                triggerAlarm();
            }
        } else {
            if (isAlarmActive) {
                Log.i(TAG, "Back within radius: " + distance + "m <= " + driftRadius + "m");
                stopAllAlarms();
                isAlarmActive = false;
                clearAlarmNotification();
            }
        }
    }

    @Override
    public void onProviderStatusChange(boolean enabled) {
        if (!enabled && !isAlarmActive) {
            // GPS disabled - trigger alarm
            Log.w(TAG, "GPS provider disabled - triggering alarm");
            triggerAlarm();
        }
    }

    private void acquireWakeLock() {
        if (isNull(wakeLock) || !wakeLock.isHeld()) {
            PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
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
        isAlarmActive = true;
        playAlarmSound();
        triggerVibration();
        showAlarmNotification();
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
                alarmMediaPlayer.setDataSource(context, alarmUri);

                AudioAttributes audioAttributes = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build();
                alarmMediaPlayer.setAudioAttributes(audioAttributes);

                AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM);
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0);

                alarmMediaPlayer.setLooping(true);
                alarmMediaPlayer.prepare();
                alarmMediaPlayer.start();

                // Auto-stop alarm after 5 minutes
                if (!isNull(alarmStopRunnable)) {
                    alarmHandler.removeCallbacks(alarmStopRunnable);
                }
                // Keep alarm state active but stop sound
                alarmStopRunnable = this::stopAlarmSound;
                alarmHandler.postDelayed(alarmStopRunnable, 5 * 60 * 1000);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error playing alarm sound", e);
        }
    }

    private void triggerVibration() {
        try {
            stopVibration();

            vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (!isNull(vibrator) && vibrator.hasVibrator()) {
                long[] pattern = {0, 1000, 500, 1000, 500, 1000};
                VibrationEffect effect = VibrationEffect.createWaveform(pattern, 0);
                vibrator.vibrate(effect);

                // Auto-stop vibration after 30 seconds
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

    private void showAlarmNotification() {
        Intent notificationIntent = new Intent(context, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, "ANCHOR_ALARM_CHANNEL")
                .setContentTitle("Anchor Alarm")
                .setContentText("Boat has drifted beyond set radius or GPS disabled!")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);

        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(2, builder.build());
    }

    private void clearAlarmNotification() {
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.cancel(2);
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

    /**
     * Stop all alarm activities (sound, vibration, notifications)
     */
    public void stopAllAlarms() {
        stopAlarmSound();
        stopVibration();
        clearAlarmNotification();
        Log.i(TAG, "All alarms stopped");
    }

    /**
     * Clean up resources when watchdog is no longer needed
     */
    public void destroy() {
        stopAllAlarms();
        releaseWakeLock();

        if (!isNull(alarmHandler)) {
            alarmHandler.removeCallbacksAndMessages(null);
            alarmHandler = null;
        }

        Log.i(TAG, "AnchorWatchdog destroyed");
    }

    private Notification buildForegroundNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 1, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, ANCHOR_WATCHDOG_CHANNEL)
                .setContentTitle("Anchor Watchdog Running")
                .setContentText("Monitoring boat position")
                .setSmallIcon(R.drawable.ic_anchor_watchdog)
                .setContentIntent(pendingIntent)
                .build();
    }

    @Override
    public void onCreate() {
        super.onCreate();

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

        if (isAlarmActive) {
            stopAllAlarms();
            isAlarmActive = false;
            clearAlarmNotification();
        }

        // Start foreground service
        startForeground(2, buildForegroundNotification());

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
