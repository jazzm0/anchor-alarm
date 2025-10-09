package com.anchoralarm.location.filter;

import static java.util.Objects.isNull;

import android.location.Location;
import android.util.Log;

import java.util.Locale;

public class OutlierDetector {

    private static final String TAG = "OutlierDetector";

    // Speed thresholds
    private static final double MAX_SPEED_KNOTS = 50.0;
    private static final double MAX_SPEED_MPS = MAX_SPEED_KNOTS * 0.514444;
    private static final double REASONABLE_SPEED_MPS = 10.0;

    // Accuracy thresholds
    private static final float MAX_ACCURACY_METERS = 50.0f;   // Hard reject above this
    private static final float PREFERRED_ACCURACY = 10.0f;    // Soft threshold
    private static final int MAX_CONSECUTIVE_POOR_ACCURACY = 3;

    // Geometric validation
    private static final double MAX_ACCELERATION_MPS2 = 5.0;
    private static final double MIN_TIME_DELTA_SECONDS = 0.5;
    private static final double MAX_TIME_DELTA_SECONDS = 300.0;

    private final Object lock = new Object();
    private long lastValidTime;
    private Location lastValidLocation;
    private OutlierReason lastOutlierReason;

    // State for adaptive logic
    private int consecutivePoorAccuracy;

    public OutlierDetector() {
        reset();
    }

    public void reset() {
        synchronized (lock) {
            lastOutlierReason = OutlierReason.NONE;
            lastValidLocation = null;
            lastValidTime = 0L;
            consecutivePoorAccuracy = 0;
            Log.d(TAG, "Outlier detector reset");
        }
    }

    public boolean isOutlier(Location current, Location previous, long timeDeltaMs) {
        synchronized (lock) {

            if (isNull(current)) {
                lastOutlierReason = OutlierReason.NULL_LOCATION;
                return true;
            }

            if (isNull(previous)) {
                lastOutlierReason = OutlierReason.NONE;
                updateBaseline(current);
                return false;
            }

            double timeDeltaSeconds = timeDeltaMs / 1000.0;
            if (!isValidTimeDelta(timeDeltaSeconds)) {
                lastOutlierReason = OutlierReason.INVALID_TIME_DELTA;
                return true;
            }

            if (!isAccuracyAcceptable(current)) {
                lastOutlierReason = OutlierReason.POOR_ACCURACY;
                return true;
            }

            if (!isSpeedReasonable(current, previous, timeDeltaSeconds)) {
                lastOutlierReason = OutlierReason.EXCESSIVE_SPEED;
                return true;
            }

            if (!isGeometricallyConsistent(current, previous, timeDeltaSeconds)) {
                lastOutlierReason = OutlierReason.GEOMETRIC_INCONSISTENCY;
                return true;
            }

            lastOutlierReason = OutlierReason.NONE;
            updateBaseline(current);
            return false;
        }
    }

    public OutlierReason getLastOutlierReason() {
        synchronized (lock) {
            return lastOutlierReason;
        }
    }

    private boolean isValidTimeDelta(double dt) {
        return dt >= MIN_TIME_DELTA_SECONDS && dt <= MAX_TIME_DELTA_SECONDS;
    }

    private boolean isAccuracyAcceptable(Location location) {
        if (!location.hasAccuracy()) {
            Log.w(TAG, "Location missing accuracy; allowing conservatively");
            return true;
        }

        float acc = location.getAccuracy();

        if (acc > MAX_ACCURACY_METERS) {
            Log.d(TAG, String.format(Locale.US,
                    "Reject: accuracy %.1fm > %.1fm", acc, MAX_ACCURACY_METERS));
            return false;
        }

        if (acc > PREFERRED_ACCURACY) {
            consecutivePoorAccuracy++;
            Log.d(TAG, String.format(Locale.US,
                    "Poor accuracy %.1fm (streak %d)", acc, consecutivePoorAccuracy));
            if (consecutivePoorAccuracy >= MAX_CONSECUTIVE_POOR_ACCURACY) {
                Log.d(TAG, "Reject: exceeded consecutive poor accuracy limit");
                return false;
            }
            return true; // Allow a few in a row
        }

        // Good accuracy resets streak
        consecutivePoorAccuracy = 0;
        return true;
    }

    private boolean isSpeedReasonable(Location current, Location previous, double dt) {
        double distance = current.distanceTo(previous);
        double speed = distance / dt;

        if (speed > MAX_SPEED_MPS) {
            Log.d(TAG, String.format(Locale.US,
                    "Reject: speed %.1f m/s (%.1f kt) > %.1f kt",
                    speed, speed / 0.514444, MAX_SPEED_KNOTS));
            return false;
        }

        if (speed > REASONABLE_SPEED_MPS) {
            float curAcc = current.hasAccuracy() ? current.getAccuracy() : Float.MAX_VALUE;
            float prevAcc = previous.hasAccuracy() ? previous.getAccuracy() : Float.MAX_VALUE;
            float combined = Math.max(curAcc, prevAcc);
            if (combined > PREFERRED_ACCURACY) {
                Log.d(TAG, String.format(Locale.US,
                        "Reject: high speed %.1f m/s with poor acc %.1fm", speed, combined));
                return false;
            }
        }
        return true;
    }

    private boolean isGeometricallyConsistent(Location current, Location previous, double dtCurrent) {
        if (lastValidLocation != null && lastValidTime > 0) {
            double dtPrev = (previous.getTime() - lastValidTime) / 1000.0;
            if (dtPrev > MIN_TIME_DELTA_SECONDS && dtPrev < MAX_TIME_DELTA_SECONDS) {
                double prevDist = previous.distanceTo(lastValidLocation);
                double currDist = current.distanceTo(previous);
                double prevSpeed = prevDist / dtPrev;
                double currSpeed = currDist / dtCurrent;
                double acceleration = Math.abs(currSpeed - prevSpeed) / dtCurrent;
                if (acceleration > MAX_ACCELERATION_MPS2) {
                    Log.d(TAG, String.format(Locale.US,
                            "Reject: acceleration %.2f m/s^2 > %.2f",
                            acceleration, MAX_ACCELERATION_MPS2));
                    return false;
                }
            }
        }
        return true;
    }

    private void updateBaseline(Location location) {
        if (isNull(location)) return;
        lastValidLocation = new Location(location);
        lastValidTime = location.getTime();
        // Reset poor accuracy streak only if accuracy is good
        if (location.hasAccuracy() && location.getAccuracy() <= PREFERRED_ACCURACY) {
            consecutivePoorAccuracy = 0;
        }
    }

    public enum OutlierReason {
        NONE("No outlier detected"),
        NULL_LOCATION("Location is null"),
        INVALID_TIME_DELTA("Invalid time delta"),
        POOR_ACCURACY("GPS accuracy too poor"),
        EXCESSIVE_SPEED("Speed exceeds maximum threshold"),
        GEOMETRIC_INCONSISTENCY("Position geometrically inconsistent");

        private final String description;

        OutlierReason(String d) {
            this.description = d;
        }

        public String getDescription() {
            return description;
        }

        @Override
        public String toString() {
            return description;
        }
    }
}
