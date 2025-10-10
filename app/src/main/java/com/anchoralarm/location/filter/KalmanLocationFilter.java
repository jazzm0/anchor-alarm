package com.anchoralarm.location.filter;

import android.location.Location;
import android.util.Log;

import java.util.Locale;

/**
 * Kalman Filter for GPS smoothing using a 2D constant-velocity model in a local tangent plane (meters).
 * State: [north (m), east (m), vel_north (m/s), vel_east (m/s)]
 */
public class KalmanLocationFilter {

    private static final String TAG = "KalmanLocationFilter";

    // State indices (internally meters; original names kept for compatibility)
    private static final int STATE_LAT = 0;      // northing (m)
    private static final int STATE_LON = 1;      // easting (m)
    private static final int STATE_VEL_LAT = 2;  // north velocity (m/s)
    private static final int STATE_VEL_LON = 3;  // east velocity (m/s)
    private static final int STATE_SIZE = 4;

    // Configuration
    private static final double ACCELERATION_NOISE = 1.0;   // m/s^2 process noise spectral density
    private static final double MIN_ACCURACY = 1.0;         // m
    private static final double MAX_ACCURACY = 100.0;       // m
    private static final double MAX_TIME_GAP_S = 30.0;      // s
    private static final double VELOCITY_DAMPING = 0.0;     // optional small damping (e.g. 0.02); 0 = none
    private static final double EARTH_RADIUS = 6378137.0;   // meters (WGS84)

    // State & covariance
    private final double[] x = new double[STATE_SIZE];          // state vector
    private final double[][] P = new double[STATE_SIZE][STATE_SIZE]; // covariance matrix

    // Reused buffers to avoid allocations
    private final double[][] K = new double[STATE_SIZE][2];     // Kalman gain
    private final double[][] Pcopy = new double[STATE_SIZE][STATE_SIZE];
    private final double[] innovation = new double[2];

    // Origin (radians)
    private boolean originSet = false;
    private double originLatRad;
    private double originLonRad;
    private double cosOriginLat;

    // Timing & stats
    private long lastUpdateTimeMs = 0L;
    private boolean initialized = false;
    private int updateCount = 0;
    private double totalAccuracyImprovement = 0.0;

    public KalmanLocationFilter() {
        reset();
    }

    public void reset() {
        initialized = false;
        originSet = false;
        lastUpdateTimeMs = 0L;
        updateCount = 0;
        totalAccuracyImprovement = 0.0;
        for (int i = 0; i < STATE_SIZE; i++) {
            x[i] = 0.0;
            for (int j = 0; j < STATE_SIZE; j++) {
                P[i][j] = (i == j) ? (i < 2 ? 1e4 : 1e2) : 0.0; // large pos, moderate vel uncertainty
            }
        }
        Log.d(TAG, "Kalman filter reset");
    }

    public Location filter(Location raw, float reportedAccuracyMeters) {
        if (raw == null) return null;

        long now = System.currentTimeMillis();
        float acc = (reportedAccuracyMeters > 0) ? reportedAccuracyMeters : raw.getAccuracy();
        acc = clamp(acc, (float) MIN_ACCURACY, (float) MAX_ACCURACY);

        if (!initialized) {
            initializeOrigin(raw);
            initializeState(raw, acc, now);
            return buildLocation(raw, acc);
        }

        double dt = (now - lastUpdateTimeMs) / 1000.0;
        if (dt <= 0 || dt > MAX_TIME_GAP_S) {
            Log.w(TAG, "Time gap " + dt + "s -> reinitialize");
            reset();
            initializeOrigin(raw);
            initializeState(raw, acc, now);
            return buildLocation(raw, acc);
        }

        predict(dt);
        update(raw, acc);
        lastUpdateTimeMs = now;
        updateCount++;

        float filteredAcc = getPredictedAccuracy();
        if (acc > filteredAcc) {
            totalAccuracyImprovement += (acc - filteredAcc);
        }
        return buildLocation(raw, filteredAcc);
    }

    private void initializeOrigin(Location loc) {
        if (originSet) return;
        originLatRad = Math.toRadians(loc.getLatitude());
        originLonRad = Math.toRadians(loc.getLongitude());
        cosOriginLat = Math.cos(originLatRad);
        originSet = true;
    }

    private void initializeState(Location loc, float accuracy, long nowMs) {
        double[] ne = toLocalNE(loc.getLatitude(), loc.getLongitude());
        x[STATE_LAT] = ne[0]; // north
        x[STATE_LON] = ne[1]; // east
        x[STATE_VEL_LAT] = 0.0;
        x[STATE_VEL_LON] = 0.0;

        double var = accuracy * accuracy;
        P[STATE_LAT][STATE_LAT] = var;
        P[STATE_LON][STATE_LON] = var;
        P[STATE_VEL_LAT][STATE_VEL_LAT] = 25.0; // (5 m/s)^2 initial vel uncertainty
        P[STATE_VEL_LON][STATE_VEL_LON] = 25.0;

        lastUpdateTimeMs = nowMs;
        initialized = true;
        Log.d(TAG, String.format("Initialized at lat=%.6f lon=%.6f acc=%.1fm",
                loc.getLatitude(), loc.getLongitude(), accuracy));
    }

    private void predict(double dt) {
        // Apply velocity damping if configured
        double velDamp = (VELOCITY_DAMPING > 0) ? Math.max(0.0, 1.0 - VELOCITY_DAMPING * dt) : 1.0;

        // State prediction
        x[STATE_LAT] += x[STATE_VEL_LAT] * dt;
        x[STATE_LON] += x[STATE_VEL_LON] * dt;
        x[STATE_VEL_LAT] *= velDamp;
        x[STATE_VEL_LON] *= velDamp;

        // Copy P -> Pcopy (original before prediction)
        for (int i = 0; i < STATE_SIZE; i++) {
            System.arraycopy(P[i], 0, Pcopy[i], 0, STATE_SIZE);
        }

        // Predict covariance for constant velocity (manual expansion)
        // Using original Pcopy values
        double dt2 = dt * dt;
        double dt3 = dt2 * dt;
        double dt4 = dt2 * dt2;
        double q = ACCELERATION_NOISE;

        // For readability assign indices
        int NX = STATE_LAT;
        int EX = STATE_LON;
        int NV = STATE_VEL_LAT;
        int EV = STATE_VEL_LON;

        // Position/velocity cross-terms
        // Northing
        P[NX][NX] = Pcopy[NX][NX] + dt * (Pcopy[NX][NV] + Pcopy[NV][NX]) + dt2 * Pcopy[NV][NV];
        P[NX][NV] = Pcopy[NX][NV] + dt * Pcopy[NV][NV];
        P[NV][NX] = P[NX][NV];
        P[NV][NV] = Pcopy[NV][NV];

        // Easting
        P[EX][EX] = Pcopy[EX][EX] + dt * (Pcopy[EX][EV] + Pcopy[EV][EX]) + dt2 * Pcopy[EV][EV];
        P[EX][EV] = Pcopy[EX][EV] + dt * Pcopy[EV][EV];
        P[EV][EX] = P[EX][EV];
        P[EV][EV] = Pcopy[EV][EV];

        // Mixed north/east blocks
        P[NX][EX] = Pcopy[NX][EX] + dt * (Pcopy[NX][EV] + Pcopy[NV][EX]) + dt2 * Pcopy[NV][EV];
        P[EX][NX] = P[NX][EX];

        P[NX][EV] = Pcopy[NX][EV] + dt * Pcopy[NV][EV];
        P[EV][NX] = P[NX][EV];

        P[EX][NV] = Pcopy[EX][NV] + dt * Pcopy[EV][NV];
        P[NV][EX] = P[EX][NV];

        // Velocity cross (NV,EV) unchanged
        // Add process noise Q blocks (north & east)
        double q11 = dt4 * q / 4.0;
        double q12 = dt3 * q / 2.0;
        double q22 = dt2 * q;

        // North block
        P[NX][NX] += q11;
        P[NX][NV] += q12;
        P[NV][NX] += q12;
        P[NV][NV] += q22;

        // East block
        P[EX][EX] += q11;
        P[EX][EV] += q12;
        P[EV][EX] += q12;
        P[EV][EV] += q22;

        // (Optional) ensure symmetry (small numerical corrections)
        symmetrize(P);
    }

    private void update(Location meas, float accuracy) {
        double[] ne = toLocalNE(meas.getLatitude(), meas.getLongitude());
        double measNorth = ne[0];
        double measEast = ne[1];

        // Innovation (z - Hx); H observes north & east directly
        innovation[0] = measNorth - x[STATE_LAT];
        innovation[1] = measEast - x[STATE_LON];

        double Rvar = accuracy * accuracy;

        // Innovation covariance S = HPH^T + R (2x2)
        double S00 = P[STATE_LAT][STATE_LAT] + Rvar;
        double S01 = P[STATE_LAT][STATE_LON];
        double S10 = P[STATE_LON][STATE_LAT];
        double S11 = P[STATE_LON][STATE_LON] + Rvar;

        double det = S00 * S11 - S01 * S10;
        if (Math.abs(det) < 1e-12) {
            Log.w(TAG, "Singular innovation covariance, skipping update");
            return;
        }
        double invDet = 1.0 / det;

        // Inverse S
        double iS00 = S11 * invDet;
        double iS01 = -S01 * invDet;
        double iS10 = -S10 * invDet;
        double iS11 = S00 * invDet;

        // Save P to Pcopy for covariance update
        for (int i = 0; i < STATE_SIZE; i++) {
            System.arraycopy(P[i], 0, Pcopy[i], 0, STATE_SIZE);
        }

        // Kalman Gain K = P H^T S^-1
        // Columns correspond to north, east
        for (int i = 0; i < STATE_SIZE; i++) {
            double Pi0 = Pcopy[i][STATE_LAT];
            double Pi1 = Pcopy[i][STATE_LON];
            K[i][0] = Pi0 * iS00 + Pi1 * iS10;
            K[i][1] = Pi0 * iS01 + Pi1 * iS11;
        }

        // State update x = x + K * innovation
        double in0 = innovation[0];
        double in1 = innovation[1];
        for (int i = 0; i < STATE_SIZE; i++) {
            x[i] += K[i][0] * in0 + K[i][1] * in1;
        }

        // Covariance update: P = P - K * (H * Pcopy)
        // H * Pcopy are rows 0 & 1 of Pcopy
        for (int i = 0; i < STATE_SIZE; i++) {
            double Ki0 = K[i][0];
            double Ki1 = K[i][1];
            for (int j = 0; j < STATE_SIZE; j++) {
                P[i][j] = Pcopy[i][j] - Ki0 * Pcopy[STATE_LAT][j] - Ki1 * Pcopy[STATE_LON][j];
            }
        }

        symmetrize(P);
    }

    private Location buildLocation(Location base, float accuracy) {
        Location out = new Location(base);
        double[] latLon = toLatLon(x[STATE_LAT], x[STATE_LON]);
        out.setLatitude(latLon[0]);
        out.setLongitude(latLon[1]);
        out.setAccuracy(accuracy);

        double speed = Math.hypot(x[STATE_VEL_LAT], x[STATE_VEL_LON]);
        out.setSpeed((float) speed);
        return out;
    }

    private double[] toLocalNE(double latDeg, double lonDeg) {
        double latRad = Math.toRadians(latDeg);
        double lonRad = Math.toRadians(lonDeg);
        double dLat = latRad - originLatRad;
        double dLon = lonRad - originLonRad;
        double north = dLat * EARTH_RADIUS;
        double east = dLon * EARTH_RADIUS * cosOriginLat;
        return new double[]{north, east};
    }

    private double[] toLatLon(double north, double east) {
        double latRad = originLatRad + north / EARTH_RADIUS;
        double lonRad = originLonRad + (cosOriginLat == 0 ? 0 : east / (EARTH_RADIUS * cosOriginLat));
        return new double[]{Math.toDegrees(latRad), Math.toDegrees(lonRad)};
    }

    private void symmetrize(double[][] M) {
        for (int i = 0; i < STATE_SIZE; i++) {
            for (int j = i + 1; j < STATE_SIZE; j++) {
                double v = 0.5 * (M[i][j] + M[j][i]);
                M[i][j] = v;
                M[j][i] = v;
            }
        }
    }

    private float clamp(float v, float min, float max) {
        return v < min ? min : (Math.min(v, max));
    }

    public float getPredictedAccuracy() {
        if (!initialized) return Float.MAX_VALUE;
        double varNorth = P[STATE_LAT][STATE_LAT];
        double varEast = P[STATE_LON][STATE_LON];
        return (float) Math.sqrt(Math.max(varNorth, varEast));
    }

    public float getEstimatedSpeed() {
        if (!initialized) return 0f;
        return (float) Math.hypot(x[STATE_VEL_LAT], x[STATE_VEL_LON]);
    }

    public FilterStatistics getStatistics() {
        double avgImprovement = updateCount > 0 ? (totalAccuracyImprovement / updateCount) : 0.0;
        return new FilterStatistics(
                initialized,
                updateCount,
                (float) avgImprovement,
                getPredictedAccuracy(),
                getEstimatedSpeed()
        );
    }

    public record FilterStatistics(boolean isInitialized, int updateCount,
                                   float averageAccuracyImprovement, float currentAccuracy,
                                   float estimatedSpeed) {

        @Override
        public String toString() {
            return String.format(Locale.ENGLISH, "FilterStats{init=%b, updates=%d, avgImprovement=%.2fm, accuracy=%.2fm, speed=%.2fm/s}",
                    isInitialized, updateCount, averageAccuracyImprovement, currentAccuracy, estimatedSpeed);
        }
    }
}
