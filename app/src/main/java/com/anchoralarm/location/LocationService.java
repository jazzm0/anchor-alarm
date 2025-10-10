package com.anchoralarm.location;

import static java.util.Objects.isNull;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.anchoralarm.MainActivity;
import com.anchoralarm.R;
import com.anchoralarm.location.filter.KalmanLocationFilter;
import com.anchoralarm.location.filter.OutlierDetector;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

public class LocationService extends Service {

    public static final String LOCATION_SERVICE_CHANNEL = "LOCATION_SERVICE_CHANNEL";
    private static final String TAG = "LocationService";

    private static final int FOREGROUND_NOTIFICATION_ID = 1;
    private static final long LOCATION_UPDATE_MIN_TIME_MS = 1000L; // consider increasing
    private static final float LOCATION_UPDATE_MIN_DISTANCE_M = 0f; // consider > 0 to save battery

    // Core
    private LocationManager locationManager;
    private LocationListener locationListener;
    private GNSSConstellationMonitor constellationMonitor;
    private GnssStatus.Callback gnssStatusCallback;

    // Filtering
    private final KalmanLocationFilter kalmanLocationFilter = new KalmanLocationFilter();
    private final OutlierDetector outlierDetector = new OutlierDetector();

    private final List<LocationUpdateListener> listeners = new ArrayList<>();
    private Location previousRawLocation;
    private Location lastFilteredLocation;

    private boolean trackingStarted = false;

    public class LocationServiceBinder extends Binder {
        public LocationService getService() {
            return LocationService.this;
        }
    }

    private final IBinder binder = new LocationServiceBinder();

    @Override
    public void onCreate() {
        super.onCreate();
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        constellationMonitor = new GNSSConstellationMonitor();
        setupGNSSStatusCallback();
        resetFilters();
        Log.i(TAG, "Created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startAsForeground();
        ensureLocationUpdatesStarted();
        return START_STICKY;
    }

    private void startAsForeground() {
        Notification notification = buildForegroundNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(FOREGROUND_NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(FOREGROUND_NOTIFICATION_ID, notification);
        }
    }

    private Notification buildForegroundNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, LOCATION_SERVICE_CHANNEL)
                .setContentTitle("Location Service Running")
                .setContentText("Monitoring boat position")
                .setSmallIcon(R.drawable.ic_location_accuracy)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    private void ensureLocationUpdatesStarted() {
        if (trackingStarted) return;
        if (locationManager == null) {
            Log.e(TAG, "LocationManager null; stopping");
            stopSelf();
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Missing ACCESS_FINE_LOCATION; stopping");
            stopSelf();
            return;
        }
        startLocationListener();
        registerGnssStatus();
        trackingStarted = true;
        Log.i(TAG, "Location tracking started");
    }

    private void startLocationListener() {
        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(@NonNull Location current) {
                if (previousRawLocation != null &&
                        outlierDetector.isOutlier(current, previousRawLocation,
                                current.getTime() - previousRawLocation.getTime())) {
                    Log.d(TAG, "Outlier rejected");
                    return;
                }
                previousRawLocation = current;
                lastFilteredLocation = kalmanLocationFilter.filter(current, current.getAccuracy());
                notifyLocationUpdate(lastFilteredLocation, constellationMonitor);
                Log.d(TAG, "Filtered lat=" + lastFilteredLocation.getLatitude() +
                        " lon=" + lastFilteredLocation.getLongitude() +
                        " acc=" + lastFilteredLocation.getAccuracy());
            }

            @Override
            public void onProviderDisabled(@NonNull String provider) {
                Log.w(TAG, "Provider disabled: " + provider);
                notifyProviderStatusChange(false);
            }

            @Override
            public void onProviderEnabled(@NonNull String provider) {
                Log.i(TAG, "Provider enabled: " + provider);
                notifyProviderStatusChange(true);
            }
        };

        try {
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    LOCATION_UPDATE_MIN_TIME_MS,
                    LOCATION_UPDATE_MIN_DISTANCE_M,
                    locationListener
            );

            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        10_000L,
                        0f,
                        locationListener
                );
            }
        } catch (SecurityException se) {
            Log.e(TAG, "SecurityException requesting updates", se);
            stopSelf();
        }
    }

    private void registerGnssStatus() {
        if (gnssStatusCallback == null || locationManager == null) return;
        try {
            boolean registered;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Executor mainExec = ContextCompat.getMainExecutor(this);
                registered = locationManager.registerGnssStatusCallback(mainExec, gnssStatusCallback);
            } else {
                registered = locationManager.registerGnssStatusCallback(gnssStatusCallback);
            }
            Log.i(TAG, "GNSS status registered=" + registered);
        } catch (SecurityException se) {
            Log.e(TAG, "SecurityException registering GNSS", se);
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

    private void resetFilters() {
        outlierDetector.reset();
        kalmanLocationFilter.reset();
        previousRawLocation = null;
        lastFilteredLocation = null;
    }

    private void stopLocationUpdates() {
        if (locationManager != null && locationListener != null) {
            locationManager.removeUpdates(locationListener);
        }
        locationListener = null;
        trackingStarted = false;
    }

    public void addListener(LocationUpdateListener listener) {
        synchronized (listeners) {
            if (!listeners.contains(listener)) listeners.add(listener);
        }
    }

    public void removeListener(LocationUpdateListener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    private void notifyLocationUpdate(Location filtered, GNSSConstellationMonitor gnssData) {
        synchronized (listeners) {
            for (LocationUpdateListener l : listeners) {
                try {
                    l.onLocationUpdate(filtered, gnssData);
                } catch (Exception e) {
                    Log.e(TAG, "Listener error " + l.getClass().getSimpleName(), e);
                }
            }
        }
    }

    private void notifyProviderStatusChange(boolean enabled) {
        synchronized (listeners) {
            for (LocationUpdateListener l : listeners) {
                try {
                    l.onProviderStatusChange(enabled);
                } catch (Exception e) {
                    Log.e(TAG, "Listener error " + l.getClass().getSimpleName(), e);
                }
            }
        }
    }

    public GNSSConstellationMonitor getConstellationMonitor() {
        return constellationMonitor;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        stopLocationUpdates();
        if (locationManager != null && gnssStatusCallback != null) {
            try {
                locationManager.unregisterGnssStatusCallback(gnssStatusCallback);
            } catch (Exception ignored) {
            }
        }
        synchronized (listeners) {
            listeners.clear();
        }
        stopForeground(true);
        super.onDestroy();
        Log.i(TAG, "Destroyed");
    }
}
