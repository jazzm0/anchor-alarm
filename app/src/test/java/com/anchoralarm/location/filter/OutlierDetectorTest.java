package com.anchoralarm.location.filter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.location.Location;

import com.anchoralarm.location.filter.OutlierDetector.OutlierReason;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class OutlierDetectorTest {

    private OutlierDetector detector;

    @Before
    public void setUp() {
        detector = new OutlierDetector();

    }

    @Test
    public void testNullCurrentLocation() {
        Location previous = Mockito.mock(Location.class);
        assertTrue(detector.isOutlier(null, previous, 1000));
        assertEquals(OutlierReason.NULL_LOCATION, detector.getLastOutlierReason());
    }

    @Test
    public void testNullPreviousLocation() {
        Location current = Mockito.mock(Location.class);
        assertFalse(detector.isOutlier(current, null, 1000));
        assertEquals(OutlierReason.NONE, detector.getLastOutlierReason());
    }

    @Test
    public void testInvalidTimeDelta() {
        Location current = Mockito.mock(Location.class);
        Location previous = Mockito.mock(Location.class);
        assertTrue(detector.isOutlier(current, previous, 100)); // 0.1s < MIN_TIME_DELTA
        assertEquals(OutlierReason.INVALID_TIME_DELTA, detector.getLastOutlierReason());
    }

    @Test
    public void testPoorAccuracyHardReject() {
        Location current = Mockito.mock(Location.class);
        Location previous = Mockito.mock(Location.class);
        Mockito.when(current.hasAccuracy()).thenReturn(true);
        Mockito.when(current.getAccuracy()).thenReturn(100.0f); // > MAX_ACCURACY_METERS
        assertTrue(detector.isOutlier(current, previous, 1000));
        assertEquals(OutlierReason.POOR_ACCURACY, detector.getLastOutlierReason());
    }

    @Test
    public void testPoorAccuracySoftRejectAfterStreak() {
        Location current = Mockito.mock(Location.class);
        Location previous = Mockito.mock(Location.class);
        Mockito.when(current.hasAccuracy()).thenReturn(true);
        Mockito.when(current.getAccuracy()).thenReturn(20.0f); // > PREFERRED_ACCURACY, < MAX_ACCURACY_METERS

        for (int i = 0; i < 2; i++) {
            assertFalse(detector.isOutlier(current, previous, 1000));
        }
        assertTrue(detector.isOutlier(current, previous, 1000));
        assertEquals(OutlierReason.POOR_ACCURACY, detector.getLastOutlierReason());
    }

    @Test
    public void testGoodAccuracyResetsStreak() {
        Location current = Mockito.mock(Location.class);
        Location previous = Mockito.mock(Location.class);
        Mockito.when(current.hasAccuracy()).thenReturn(true);
        Mockito.when(current.getAccuracy()).thenReturn(20.0f);

        for (int i = 0; i < 2; i++) {
            detector.isOutlier(current, previous, 1000);
        }

        Mockito.when(current.getAccuracy()).thenReturn(5.0f); // Good accuracy
        assertFalse(detector.isOutlier(current, previous, 1000));
    }

    @Test
    public void testExcessiveSpeed() {
        Location current = Mockito.mock(Location.class);
        Location previous = Mockito.mock(Location.class);
        Mockito.when(current.distanceTo(previous)).thenReturn(1000.0f); // Large distance
        Mockito.when(current.hasAccuracy()).thenReturn(true);
        Mockito.when(current.getAccuracy()).thenReturn(5.0f);

        assertTrue(detector.isOutlier(current, previous, 10 * 1000)); // 1000m/10s = 100m/s > MAX_SPEED_MPS
        assertEquals(OutlierReason.EXCESSIVE_SPEED, detector.getLastOutlierReason());
    }

    @Test
    public void testHighSpeedWithPoorAccuracy() {
        Location current = Mockito.mock(Location.class);
        Location previous = Mockito.mock(Location.class);
        Mockito.when(current.distanceTo(previous)).thenReturn(150.0f); // 15m/s
        Mockito.when(current.hasAccuracy()).thenReturn(true);
        Mockito.when(current.getAccuracy()).thenReturn(20.0f); // Poor accuracy
        Mockito.when(previous.hasAccuracy()).thenReturn(true);
        Mockito.when(previous.getAccuracy()).thenReturn(20.0f);

        assertTrue(detector.isOutlier(current, previous, 10 * 1000));
        assertEquals(OutlierReason.EXCESSIVE_SPEED, detector.getLastOutlierReason());
    }

    @Test
    public void testGeometricInconsistency() {
        Location previous = Mockito.mock(Location.class);
        Location current = Mockito.mock(Location.class);

        detector.reset();
        detector.isOutlier(previous, null, 1000);

        Mockito.when(current.distanceTo(previous)).thenReturn(100.0f);

        assertTrue(detector.isOutlier(current, previous, 1000));
        assertEquals(OutlierReason.EXCESSIVE_SPEED, detector.getLastOutlierReason());
    }

    @Test
    public void testReset() {
        Location current = Mockito.mock(Location.class);
        Location previous = Mockito.mock(Location.class);
        Mockito.when(current.hasAccuracy()).thenReturn(true);
        Mockito.when(current.getAccuracy()).thenReturn(100.0f);

        detector.isOutlier(current, previous, 1000);
        assertEquals(OutlierReason.POOR_ACCURACY, detector.getLastOutlierReason());

        detector.reset();
        assertEquals(OutlierReason.NONE, detector.getLastOutlierReason());
    }
}
