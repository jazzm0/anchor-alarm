package com.anchoralarm.location;

import android.location.Location;

/**
 * Interface for components that want to receive location updates from LocationService
 */
public interface LocationUpdateListener {

    /**
     * Called when a new filtered location is available
     *
     * @param filteredLocation The filtered GPS location
     * @param gnssData         Current GNSS constellation data
     */
    void onLocationUpdate(Location filteredLocation, GNSSConstellationMonitor gnssData);

    /**
     * Called when GPS provider status changes
     *
     * @param enabled True if GPS is enabled, false otherwise
     */
    void onProviderStatusChange(boolean enabled);
}
