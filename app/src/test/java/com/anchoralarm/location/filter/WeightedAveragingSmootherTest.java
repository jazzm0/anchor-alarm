// File: app/src/test/java/com/anchoralarm/location/filter/WeightedAveragingSmootherTest.java
package com.anchoralarm.location.filter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.location.Location;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class WeightedAveragingSmootherTest {

    private WeightedAveragingSmoother smoother;
    private DummyGNSSMonitor gnssMonitor;

    @Before
    public void setUp() {
        smoother = new WeightedAveragingSmoother(5);
        gnssMonitor = new DummyGNSSMonitor(80); // 80% signal quality
    }

    @Test
    public void testInitialState() {
        WeightedAveragingSmoother.SmootherStatistics stats = smoother.getStatistics();
        assertFalse(stats.initialized());
        assertEquals(0, stats.bufferSize());
        assertEquals(0, stats.totalUpdates());
        assertEquals(0.0f, stats.lastWeight(), 0.001f);
    }

    @Test
    public void testSmoothReturnsInputBeforeInit() {
        Location l = loc(1, 2, 5f);
        Location out = smoother.smooth(l, gnssMonitor);
        assertEquals(l, out);
    }

    @Test
    public void testSmootherInitializesAfterMinSamples() {
        for (int i = 0; i < 3; i++) {
            smoother.smooth(loc(1 + i, 2, 5f), gnssMonitor);
        }
        assertTrue(smoother.getStatistics().initialized());
    }

    @Test
    public void testWeightedAverageCalculation() {
        for (int i = 0; i < 5; i++) {
            smoother.smooth(loc(10 + i, 20, 5f), gnssMonitor);
        }
        Location out = smoother.smooth(loc(15, 20, 5f), gnssMonitor);
        assertTrue(out.getLatitude() > 10);
        assertTrue(out.getLatitude() < 16);
        assertEquals(20, out.getLongitude(), 1e-6);
    }

    @Test
    public void testWeightCalculationAccuracyAndSignal() {
        Location l = loc(0, 0, 2f);
        float w1 = smoother.smooth(l, gnssMonitor).getAccuracy();
        gnssMonitor.setSignalQuality(20);
        float w2 = smoother.smooth(l, gnssMonitor).getAccuracy();
        assertTrue(w1 >= w2); // Lower signal should reduce weight/accuracy
    }

    @Test
    public void testBufferWrapsAndMostRecent() {
        for (int i = 0; i < 10; i++) {
            smoother.smooth(loc(i, 0, 5f), gnssMonitor);
        }
        WeightedAveragingSmoother.SmootherStatistics stats = smoother.getStatistics();
        assertEquals(5, stats.bufferSize());
        assertEquals(10, stats.totalUpdates());
    }

    @Test
    public void testResetClearsState() {
        for (int i = 0; i < 5; i++) {
            smoother.smooth(loc(i, 0, 5f), gnssMonitor);
        }
        smoother.reset();
        WeightedAveragingSmoother.SmootherStatistics stats = smoother.getStatistics();
        assertFalse(stats.initialized());
        assertEquals(0, stats.bufferSize());
        assertEquals(0, stats.totalUpdates());
    }

    @Test
    public void testNullLocationReturnsNull() {
        Location out = smoother.smooth(null, gnssMonitor);
        assertNull(out);
    }

    // Helper classes and methods
    private static Location loc(double lat, double lon, float acc) {
        Location l = new Location("test");
        l.setLatitude(lat);
        l.setLongitude(lon);
        l.setAccuracy(acc);
        l.setTime(System.currentTimeMillis());
        return l;
    }

    private static class DummyGNSSMonitor extends com.anchoralarm.location.GNSSConstellationMonitor {
        private int quality;

        public DummyGNSSMonitor(int q) {
            quality = q;
        }

        public void setSignalQuality(int q) {
            quality = q;
        }

        @Override
        public int getOverallSignalQuality() {
            return quality;
        }
    }
}
