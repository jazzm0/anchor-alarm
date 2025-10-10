package com.anchoralarm.location.filter;

import android.location.Location;
import android.util.Log;

import com.anchoralarm.location.GNSSConstellationMonitor;

import java.util.Locale;

/**
 * Weighted Averaging Smoother for post-Kalman location smoothing.
 * Uses signal quality and accuracy-based weighting with time decay.
 * Operates as a lightweight complement to the existing Kalman filter.
 */

public class WeightedAveragingSmoother {

    private static final String TAG = "WeightedSmoother";

    // Configuration
    private static final int DEFAULT_WINDOW_SIZE = 5;
    private static final double TIME_DECAY_CONSTANT = 5.0;
    private static final float MIN_WEIGHT = 0.1f;
    private static final float MAX_WEIGHT = 10.0f;
    private static final boolean ENABLED = true; // Easy disable for testing

    private final CircularLocationBuffer buffer;
    private final int windowSize;
    private boolean initialized = false;
    private int totalUpdates = 0;

    public WeightedAveragingSmoother() {
        this(DEFAULT_WINDOW_SIZE);
    }

    public WeightedAveragingSmoother(int windowSize) {
        this.windowSize = Math.max(1, Math.min(windowSize, 20)); // Reasonable bounds
        this.buffer = new CircularLocationBuffer(this.windowSize);
        Log.d(TAG, "WeightedAveragingSmoother created with window size: " + this.windowSize);
    }

    /**
     * Smooth the Kalman-filtered location using weighted averaging
     *
     * @param kalmanFiltered Location output from Kalman filter
     * @param gnssData       Current GNSS constellation data for signal quality
     * @return Smoothed location
     */
    public Location smooth(Location kalmanFiltered, GNSSConstellationMonitor gnssData) {
        if (!ENABLED || kalmanFiltered == null) {
            return kalmanFiltered;
        }

        long currentTime = System.currentTimeMillis();
        float weight = calculateWeight(kalmanFiltered, gnssData);

        WeightedLocation weightedLocation = new WeightedLocation(
                kalmanFiltered, weight, currentTime
        );

        buffer.add(weightedLocation);
        totalUpdates++;

        if (!initialized && buffer.size() >= Math.min(3, windowSize)) {
            initialized = true;
            Log.d(TAG, "Smoother initialized with " + buffer.size() + " samples");
        }

        // For first few samples, return Kalman output directly
        if (!initialized) {
            return kalmanFiltered;
        }

        return computeWeightedAverage(kalmanFiltered);
    }

    /**
     * Calculate weight based on accuracy, signal quality, and time decay
     */
    private float calculateWeight(Location location, GNSSConstellationMonitor gnssData) {
        float accuracy = location.hasAccuracy() ? location.getAccuracy() : 10.0f;
        float accuracyWeight = 1.0f / Math.max(accuracy, 1.0f);

        float signalQuality = 0.5f;
        if (gnssData != null) {
            signalQuality = gnssData.getOverallSignalQuality() / 100.0f;
            signalQuality = Math.max(0.1f, Math.min(1.0f, signalQuality));
        }

        float weight = accuracyWeight * signalQuality;

        weight = Math.max(MIN_WEIGHT, Math.min(MAX_WEIGHT, weight));

        if (totalUpdates < 10) {
            Log.d(TAG, String.format(Locale.US,
                    "Weight calc: acc=%.1fm -> %.3f, signal=%.0f%% -> %.3f,  final=%.3f",
                    accuracy, accuracyWeight, signalQuality * 100, signalQuality, weight));
        }

        return weight;
    }

    /**
     * Compute weighted average of locations in the buffer
     */
    private Location computeWeightedAverage(Location fallback) {
        if (buffer.size() == 0) {
            return fallback;
        }

        long currentTime = System.currentTimeMillis();
        double totalWeight = 0.0;
        double weightedLat = 0.0;
        double weightedLon = 0.0;
        double weightedAlt = 0.0;
        boolean hasAltitude = false;

        // Calculate weighted average with time decay
        for (int i = 0; i < buffer.size(); i++) {
            WeightedLocation wl = buffer.get(i);
            if (wl == null) continue;

            // Apply time decay
            double timeDelta = (currentTime - wl.timestamp()) / 1000.0; // seconds
            double timeDecay = Math.exp(-timeDelta / TIME_DECAY_CONSTANT);
            double effectiveWeight = wl.weight() * timeDecay;

            totalWeight += effectiveWeight;
            weightedLat += wl.location().getLatitude() * effectiveWeight;
            weightedLon += wl.location().getLongitude() * effectiveWeight;

            if (wl.location().hasAltitude()) {
                weightedAlt += wl.location().getAltitude() * effectiveWeight;
                hasAltitude = true;
            }
        }

        if (totalWeight < 0.001) {
            Log.w(TAG, "Total weight too small, returning fallback");
            return fallback;
        }

        // Create smoothed location
        Location smoothed = new Location(fallback);
        smoothed.setLatitude(weightedLat / totalWeight);
        smoothed.setLongitude(weightedLon / totalWeight);

        if (hasAltitude) {
            smoothed.setAltitude(weightedAlt / totalWeight);
        }

        // Use the accuracy of the most recent (highest weighted) location
        WeightedLocation mostRecent = buffer.getMostRecent();
        if (mostRecent != null) {
            smoothed.setAccuracy(mostRecent.location().getAccuracy());
        }

        return smoothed;
    }

    /**
     * Reset the smoother (e.g., when filters are reset)
     */
    public void reset() {
        buffer.clear();
        initialized = false;
        totalUpdates = 0;
        Log.d(TAG, "Smoother reset");
    }

    /**
     * Get smoother statistics for monitoring
     */
    public SmootherStatistics getStatistics() {
        return new SmootherStatistics(
                initialized,
                buffer.size(),
                totalUpdates,
                buffer.size() > 0 ? buffer.getMostRecent().weight() : 0.0f
        );
    }

    /**
     * Statistics record for monitoring smoother performance
     */
    public record SmootherStatistics(
            boolean initialized,
            int bufferSize,
            int totalUpdates,
            float lastWeight
    ) {
        @Override
        public String toString() {
            return String.format(Locale.ENGLISH,
                    "SmootherStats{init=%b, buffer=%d/%d, updates=%d, lastWeight=%.3f}",
                    initialized, bufferSize, DEFAULT_WINDOW_SIZE, totalUpdates, lastWeight);
        }
    }

    /**
     * Record to hold location with its calculated weight and timestamp
     */
    public record WeightedLocation(Location location, float weight, long timestamp) {
        public WeightedLocation {
            if (location == null) {
                throw new IllegalArgumentException("Location cannot be null");
            }
        }
    }

    /**
     * Simple circular buffer for weighted locations
     */
    private static class CircularLocationBuffer {
        private final WeightedLocation[] buffer;
        private final int capacity;
        private int size = 0;
        private int head = 0;

        public CircularLocationBuffer(int capacity) {
            this.capacity = capacity;
            this.buffer = new WeightedLocation[capacity];
        }

        public void add(WeightedLocation location) {
            buffer[head] = location;
            head = (head + 1) % capacity;
            if (size < capacity) {
                size++;
            }
        }

        public WeightedLocation get(int index) {
            if (index >= size) return null;
            int actualIndex = (head - size + index + capacity) % capacity;
            return buffer[actualIndex];
        }

        public WeightedLocation getMostRecent() {
            if (size == 0) return null;
            int mostRecentIndex = (head - 1 + capacity) % capacity;
            return buffer[mostRecentIndex];
        }

        public int size() {
            return size;
        }

        public void clear() {
            size = 0;
            head = 0;
            for (int i = 0; i < capacity; i++) {
                buffer[i] = null;
            }
        }
    }
}
