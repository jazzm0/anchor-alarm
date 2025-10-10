package com.anchoralarm.location;

import static java.util.Objects.isNull;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
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

/**
 * Standalone LocationService that provides filtered GPS location data to registered listeners
 * Handles GPS tracking, filtering, and GNSS monitoring without any alarm functionality
 */
public class LocationService extends Service {

    public static final String LOCATION_SERVICE_CHANNEL = "LOCATION_SERVICE_CHANNEL";

    private static final String TAG = "LocationService";
    private static final int LOCATION_UPDATE_MIN_TIME = 1000;

    // Core location components
    private LocationManager locationManager;
    private LocationListener locationListener;
    private Location previousLocation;

    private GNSSConstellationMonitor constellationMonitor;
    private GnssStatus.Callback gnssStatusCallback;

    // Filtering components
    private final KalmanLocationFilter kalmanLocationFilter = new KalmanLocationFilter();
    private final OutlierDetector outlierDetector = new OutlierDetector();

    // Listener management
    private final List<LocationUpdateListener> listeners = new ArrayList<>();

    /**
     * Binder for client components to interact with the service
     */
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

        // Create and register the watchdog as a listener


        reset();
        setupGNSSStatusCallback();

        Log.i(TAG, "LocationService created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        reset();

        // Start foreground service
        startForeground(1, buildForegroundNotification());

        // Start location tracking
        startLocationUpdates();

        Log.i(TAG, "LocationService started! ");

        return START_STICKY;
    }

    private void reset() {
        outlierDetector.reset();
        kalmanLocationFilter.reset();
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

        return new NotificationCompat.Builder(this, LOCATION_SERVICE_CHANNEL)
                .setContentTitle("Location Service Running")
                .setContentText("Monitoring boat position")
                .setSmallIcon(R.drawable.ic_location_accuracy)
                .setContentIntent(pendingIntent)
                .build();
    }

    private void startLocationUpdates() {
        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(@NonNull Location currentLocation) {
                if (previousLocation != null &&
                        outlierDetector.isOutlier(currentLocation, previousLocation,
                                currentLocation.getTime() - previousLocation.getTime())) {
                    Log.d(TAG, "Location rejected as outlier");
                    return;
                }
                previousLocation = currentLocation;

                Location filteredLocation =
                        kalmanLocationFilter.filter(currentLocation, currentLocation.getAccuracy());

                Log.d(TAG, "Filter statistics" + kalmanLocationFilter.getStatistics());
                notifyLocationUpdate(filteredLocation, constellationMonitor);

                Log.d(TAG, "Location update: " + filteredLocation.getLatitude() + "," +
                        filteredLocation.getLongitude() + " accuracy=" + filteredLocation.getAccuracy());
            }

            @Override
            public void onProviderDisabled(@NonNull String provider) {
                Log.w(TAG, "GPS provider disabled");
                notifyProviderStatusChange(false);
            }

            @Override
            public void onProviderEnabled(@NonNull String provider) {
                Log.i(TAG, "GPS provider enabled");
                notifyProviderStatusChange(true);
            }
        };

        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {

            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    LOCATION_UPDATE_MIN_TIME,
                    0f,
                    locationListener
            );

            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        10000L,
                        0f,
                        locationListener
                );
            }

            // Updated: use Executor-based callback on API 30+ to avoid deprecated call
            if (gnssStatusCallback != null) {
                boolean registered = false;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Executor mainExec = ContextCompat.getMainExecutor(this);
                    registered = locationManager.registerGnssStatusCallback(mainExec, gnssStatusCallback);
                } else {
                    registered = locationManager.registerGnssStatusCallback(gnssStatusCallback);
                }
                Log.i(TAG, "GNSS status callback registered=" + registered);
            }

            Log.i(TAG, "Location updates started");
        } else {
            Log.e(TAG, "Location permission not granted");
        }
    }

    /**
     * Add a listener to receive location updates
     */
    public void addListener(LocationUpdateListener listener) {
        synchronized (listeners) {
            if (!listeners.contains(listener)) {
                listeners.add(listener);
                Log.i(TAG, "Added listener: " + listener.getClass().getSimpleName());
            }
        }
    }

    /**
     * Remove a listener
     */
    public void removeListener(LocationUpdateListener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
            Log.i(TAG, "Removed listener: " + listener.getClass().getSimpleName());
        }
    }

    /**
     * Notify all listeners of location updates
     */
    private void notifyLocationUpdate(Location filteredLocation, GNSSConstellationMonitor gnssData) {
        synchronized (listeners) {
            for (LocationUpdateListener listener : listeners) {
                try {
                    listener.onLocationUpdate(filteredLocation, gnssData);
                } catch (Exception e) {
                    Log.e(TAG, "Error notifying listener " + listener.getClass().getSimpleName(), e);
                }
            }
        }
    }

    /**
     * Notify all listeners of GPS provider status changes
     */
    private void notifyProviderStatusChange(boolean enabled) {
        synchronized (listeners) {
            for (LocationUpdateListener listener : listeners) {
                try {
                    listener.onProviderStatusChange(enabled);
                } catch (Exception e) {
                    Log.e(TAG, "Error notifying listener " + listener.getClass().getSimpleName(), e);
                }
            }
        }
    }

    /**
     * Get current GNSS constellation monitor data
     */
    public GNSSConstellationMonitor getConstellationMonitor() {
        return constellationMonitor;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        Log.i(TAG, "LocationService destroying");

        // Stop location updates
        if (!isNull(locationListener)) {
            locationManager.removeUpdates(locationListener);
            locationListener = null;
        }

        // Unregister GNSS callback
        if (!isNull(gnssStatusCallback)) {
            locationManager.unregisterGnssStatusCallback(gnssStatusCallback);
            gnssStatusCallback = null;
        }


        // Clear listeners
        synchronized (listeners) {
            listeners.clear();
        }

        Log.i(TAG, "LocationService destroyed");
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG, "LocationService bound");
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.i(TAG, "LocationService unbound");
        return super.onUnbind(intent);
    }
}
