package com.anchoralarm.location.filter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.location.Location;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.lang.reflect.Field;

@RunWith(RobolectricTestRunner.class)
public class KalmanLocationFilterTest {

    private KalmanLocationFilter filter;

    @Before
    public void setUp() {
        filter = new KalmanLocationFilter();
    }

    @Test
    public void testNullLocationReturnsNull() {
        assertNull(filter.filter(null, 5f));
        assertFalse(filter.getStatistics().isInitialized());
    }

    @Test
    public void testInitializationAndAccuracyClampingMin() {
        Location l = loc(52.0, 13.0, 0.2f); // reported < MIN(1.0)
        Location out = filter.filter(l, 0.2f);
        assertNotNull(out);
        assertEquals(1.0f, out.getAccuracy(), 1e-6);
        assertTrue(filter.getStatistics().isInitialized());
    }

    @Test
    public void testInitializationAndAccuracyClampingMax() {
        filter.reset();
        Location l = loc(52.0, 13.0, 500f); // reported > MAX(100)
        Location out = filter.filter(l, 500f);
        assertEquals(100.0f, out.getAccuracy(), 1e-6);
    }

    @Test
    public void testSubsequentUpdateReducesVarianceForStaticPoint() throws Exception {
        filter.reset();
        Location l = loc(40.0, -74.0, 50f);
        filter.filter(l, 50f);
        float acc1 = filter.getPredictedAccuracy();
        // Perform several identical updates with controlled dt
        for (int i = 0; i < 10; i++) {
            advanceTime(filter, 1000);
            filter.filter(loc(40.0, -74.0, 50f), 50f);
        }
        float acc2 = filter.getPredictedAccuracy();
        assertTrue("Expected reduced or equal predicted accuracy", acc2 <= acc1 + 1e-3);
        assertTrue(acc2 <= 50f);
    }

    @Test
    public void testVelocityEstimationApproximate() throws Exception {
        filter.reset();
        double baseLat = 34.0;
        double baseLon = -118.0;
        Location l0 = loc(baseLat, baseLon, 5f);
        filter.filter(l0, 5f);

        double speedMps = 10.0; // target
        double dt = 1.0;
        double northMeters = speedMps * dt;
        double dLat = Math.toDegrees(northMeters / 6378137.0);

        for (int i = 0; i < 8; i++) {
            advanceTime(filter, (long) (dt * 1000));
            Location li = loc(baseLat + dLat * (i + 1), baseLon, 5f);
            filter.filter(li, 5f);
        }
        float estSpeed = filter.getEstimatedSpeed();
        assertTrue("Speed should be near 10 m/s, got " + estSpeed, Math.abs(estSpeed - 10.0) < 3.0);
    }

    @Test
    public void testStationarySpeedNearZero() throws Exception {
        filter.reset();
        Location l0 = loc(10.0, 20.0, 5f);
        filter.filter(l0, 5f);
        for (int i = 0; i < 5; i++) {
            advanceTime(filter, 1000);
            filter.filter(loc(10.0, 20.0, 5f), 5f);
        }
        assertTrue(filter.getEstimatedSpeed() < 0.5);
    }

    @Test
    public void testLargeTimeGapTriggersReinit() throws Exception {
        filter.reset();
        Location l0 = loc(51.0, 0.0, 10f);
        filter.filter(l0, 10f);
        int updatesBefore = filter.getStatistics().updateCount();

        // Simulate some normal updates
        advanceTime(filter, 1000);
        filter.filter(loc(51.00005, 0.0, 10f), 10f);

        // Force large gap (>30s)
        setField(filter, "lastUpdateTimeMs", System.currentTimeMillis() - 31000L);
        Location lFar = loc(48.0, 2.0, 8f);
        Location out = filter.filter(lFar, 8f);

        // After reinit: updateCount should be 0 (only initialization)
        assertEquals(0, filter.getStatistics().updateCount());
        assertEquals(8f, out.getAccuracy(), 1e-6);
        assertTrue(filter.getPredictedAccuracy() <= 8f);
        assertTrue(updatesBefore >= 0); // sanity
    }

    @Test
    public void testStatisticsAccuracyImprovementAccumulates() throws Exception {
        filter.reset();
        Location l0 = loc(30.0, -10.0, 40f);
        filter.filter(l0, 40f);
        for (int i = 0; i < 6; i++) {
            advanceTime(filter, 1000);
            filter.filter(loc(30.0 + 1e-5 * i, -10.0, 40f), 40f);
        }
        KalmanLocationFilter.FilterStatistics stats = filter.getStatistics();
        assertTrue(stats.updateCount() > 0);
        assertTrue(stats.averageAccuracyImprovement() >= 0f);
    }

    @Test
    public void testResetClearsState() {
        Location l0 = loc(45.0, 7.0, 15f);
        filter.filter(l0, 15f);
        assertTrue(filter.getStatistics().isInitialized());
        filter.reset();
        assertFalse(filter.getStatistics().isInitialized());
        assertEquals(Float.MAX_VALUE, filter.getPredictedAccuracy(), 0f);
        assertEquals(0f, filter.getEstimatedSpeed(), 0f);
    }

    @Test
    public void testReportedAccuracyParameterOverridesLocationAccuracy() {
        filter.reset();
        Location l = loc(12.0, 77.0, 25f);
        // Provide a different reported accuracy (clamped still in range)
        Location out = filter.filter(l, 8f);
        assertEquals(8f, out.getAccuracy(), 1e-6);
    }

    private static Location loc(double lat, double lon, float acc) {
        Location l = new Location("test");
        l.setLatitude(lat);
        l.setLongitude(lon);
        l.setAccuracy(acc);
        l.setTime(System.currentTimeMillis());
        return l;
    }

    private static void advanceTime(KalmanLocationFilter filter, long dtMs) throws Exception {
        long now = System.currentTimeMillis();
        setField(filter, "lastUpdateTimeMs", now - dtMs);
    }

    private static void setField(Object target, String name, long value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.setLong(target, value);
    }
}
