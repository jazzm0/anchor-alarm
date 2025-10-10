# Anchor Alarm Enhancement Roadmap

## Executive Summary

### Project Overview

The Anchor Alarm enhancement project aims to significantly improve location accuracy, reliability,
and user experience through advanced GNSS processing, intelligent filtering, and enhanced UI
components.

### Key Objectives

- **Accuracy Improvement**: 3-5m → 1-2m typical accuracy
- **Reliability Enhancement**: 95% → 99%+ uptime
- **Performance Optimization**: 70-80% reduction in GPS jitter
- **User Experience**: Enhanced real-time feedback and monitoring

### Implementation Priority Matrix

| Phase                     | Priority | Impact | Effort | Timeline  |
|---------------------------|----------|--------|--------|-----------|
| Phase 2 (Filtering)       | **HIGH** | High   | Medium | 2-3 weeks |
| Phase 4 (Provider Fusion) | **HIGH** | High   | Low    | 1-2 weeks |
| Phase 5 (Enhanced UI)     | Medium   | Medium | Low    | 1-2 weeks |
| Phase 3 (Dual-Frequency)  | Low      | High   | High   | 2-3 weeks |

---

## Current State Analysis

### ✅ Phase 1: Multi-GNSS Foundation (COMPLETED)

- **Status**: Fully implemented and tested
- **Components**:
    - `GNSSConstellationMonitor` class with constellation categorization
    - Multi-constellation support (GPS, GLONASS, Galileo, BeiDou, QZSS, IRNSS, SBAS)
    - Real-time satellite counting and signal quality monitoring
    - Dynamic signal quality icons (0-4 signal strength levels)
- **Achievements**:
    - Enhanced satellite visibility and monitoring
    - Real-time signal quality feedback
    - Improved UI with evenly spaced status containers

### Current Architecture

```
com.anchoralarm.location/
├── GNSSConstellationMonitor.java ✅
```

---

## Phase 2: Location Filtering & Smoothing ✅ 80% COMPLETE

**Priority: HIGH | Timeline: 2-3 weeks | Expected Impact: 70-80% jitter reduction**

### Objectives ✅ MOSTLY COMPLETE

- ✅ Implement Kalman filtering for GPS noise reduction (COMPLETE)
- ✅ Add weighted averaging for improved accuracy (COMPLETE)
- ✅ Create outlier detection for GPS jump rejection (COMPLETE)
- 🔄 Implement adaptive update intervals based on conditions (OPTIONAL)

### Implementation Steps

#### Step 1: Create LocationFilter Package Structure

```bash
mkdir -p app/src/main/java/com/anchoralarm/location/filter
```

**Deliverables:**

- Package structure for filtering components
- Base interfaces and abstract classes

#### Step 2: Implement Kalman Filter Class

**File**: `app/src/main/java/com/anchoralarm/location/filter/KalmanLocationFilter.java`

**Key Features:**

- State prediction and correction cycles
- Adaptive noise covariance based on GPS accuracy
- Velocity estimation and smoothing
- Position uncertainty tracking

**Class Structure:**

```java
public class KalmanLocationFilter {
    private Matrix stateVector;        // [lat, lon, vel_lat, vel_lon]
    private Matrix covarianceMatrix;   // Uncertainty matrix
    private Matrix processNoise;       // Motion model noise
    private Matrix measurementNoise;   // GPS measurement noise

    public Location filter(Location newLocation, float accuracy);

    public void reset();

    public float getPredictedAccuracy();
}
```

#### ✅ Step 3: Create Weighted Averaging Smoother (COMPLETED)

**File**: `app/src/main/java/com/anchoralarm/location/filter/WeightedAveragingSmoother.java`

**Key Features:** ✅

- ✅ Signal strength weighted averaging
- ✅ Time-decay factors for historical positions  
- ✅ Accuracy-based weight calculation
- ✅ Configurable smoothing window (default: 5 locations)

**Implementation Details:** ✅

- ✅ Weight calculation: `weight = accuracyWeight * signalQuality`
- ✅ Time decay: `weight *= exp(-timeDelta / TIME_DECAY_CONSTANT)`
- ✅ Window size: Configurable circular buffer
- ✅ Integrated into LocationService processing pipeline

#### Step 4: Implement Outlier Detection

**File**: `app/src/main/java/com/anchoralarm/location/filter/OutlierDetector.java`

**Detection Algorithms:**

- **Speed-based rejection**: Reject positions requiring >50 knots movement
- **Accuracy-based filtering**: Reject positions with accuracy >50m
- **Geometric validation**: Check position consistency with expected movement
- **Statistical outlier detection**: Z-score based anomaly detection

**Class Methods:**

```java
public class OutlierDetector {
    public boolean isOutlier(Location current, Location previous, long timeDelta);

    public OutlierReason getOutlierReason();

    public void updateBaselineMetrics(Location location);
}
```

#### Step 5: Create Adaptive Update Controller

**File**: `app/src/main/java/com/anchoralarm/location/filter/AdaptiveUpdateController.java`

**Update Strategies:**

- **High accuracy mode**: 1-second updates when accuracy <5m
- **Standard mode**: 2-second updates when accuracy 5-15m
- **Power saving mode**: 5-second updates when accuracy >15m
- **Motion-based adaptation**: Faster updates when moving

#### Step 6: Integrate Filtering with LocationService

**Modifications to**: `LocationService.java`

**Integration Points:**

```java
private KalmanLocationFilter kalmanFilter;
private LocationSmoother smoother;
private OutlierDetector outlierDetector;
private AdaptiveUpdateController updateController;

@Override
public void onLocationChanged(Location location) {
    // 1. Outlier detection
    if (outlierDetector.isOutlier(location, lastLocation, timeDelta)) {
        return; // Reject outlier
    }

    // 2. Kalman filtering
    Location filteredLocation = kalmanFilter.filter(location, location.getAccuracy());

    // 3. Weighted smoothing
    Location smoothedLocation = smoother.smooth(filteredLocation);

    // 4. Update adaptive controller
    updateController.updateStrategy(smoothedLocation);

    // 5. Process final location
    processLocation(smoothedLocation);
}
```

#### Step 7: Add Filter Configuration

**File**: `app/src/main/java/com/anchoralarm/location/filter/FilterConfig.java`

**Configuration Parameters:**

```java
public class FilterConfig {
    public static final float KALMAN_PROCESS_NOISE = 0.1f;
    public static final float KALMAN_MEASUREMENT_NOISE = 1.0f;
    public static final int SMOOTHING_WINDOW_SIZE = 7;
    public static final float OUTLIER_SPEED_THRESHOLD = 25.0f; // m/s
    public static final float OUTLIER_ACCURACY_THRESHOLD = 50.0f; // meters
}
```

#### Step 8: Create Filter Status Monitoring

**File**: `app/src/main/java/com/anchoralarm/location/filter/FilterStatusMonitor.java`

**Monitoring Metrics:**

- Filter effectiveness percentage
- Outlier rejection count
- Average accuracy improvement
- Processing latency metrics

#### Step 9: Unit Testing for Filtering Components

**Test Files:**

- `KalmanLocationFilterTest.java`
- `LocationSmootherTest.java`
- `OutlierDetectorTest.java`

**Test Scenarios:**

- Synthetic GPS jitter simulation
- Outlier injection testing
- Filter convergence validation
- Performance benchmarking

#### Step 10: Integration Testing

**Test Scenarios:**

- Real-world GPS data replay
- Anchor alarm accuracy validation
- Battery impact assessment
- Memory usage profiling

---

## Phase 3: Dual-Frequency GNSS Support

**Priority: LOW | Timeline: 2-3 weeks | Expected Impact: High (for newer devices)**

### Objectives

- Implement raw GNSS measurements processing (Android 7.0+)
- Add dual-frequency positioning support (Android 8.0+)
- Create advanced accuracy calculations
- Implement ionospheric correction

### Prerequisites

- Android API level 24+ for raw GNSS measurements
- Android API level 26+ for dual-frequency support
- Device hardware support for L5/E5 signals

### Implementation Steps

#### Step 1: Raw GNSS Measurements Collection

**File**: `app/src/main/java/com/anchoralarm/location/gnss/RawGnssProcessor.java`

**Key Features:**

- Raw pseudorange measurements
- Carrier phase observations
- Doppler frequency measurements
- Signal-to-noise ratio tracking

#### Step 2: Dual-Frequency Signal Processing

**File**: `app/src/main/java/com/anchoralarm/location/gnss/DualFrequencyProcessor.java`

**Capabilities:**

- L1/L5 GPS frequency combination
- E1/E5 Galileo frequency combination
- Ionospheric delay correction
- Code-carrier smoothing

#### Step 3: Advanced Positioning Algorithms

**File**: `app/src/main/java/com/anchoralarm/location/gnss/AdvancedPositioning.java`

**Algorithms:**

- Weighted least squares positioning
- Real-time kinematic (RTK) basics
- Precise point positioning (PPP)
- Multi-frequency combination

#### Step 4: Integration with Location Service

**Modifications**: Enhanced `LocationService.java` for raw GNSS support

---

## Phase 4: Provider Fusion & Reliability

**Priority: HIGH | Timeline: 1-2 weeks | Expected Impact: 99%+ reliability**

### Objectives

- Create unified location provider management
- Implement seamless provider switching
- Add network-assisted GPS support
- Enhance background reliability

### Implementation Steps

#### Step 1: Create Fused Location Provider

**File**: `app/src/main/java/com/anchoralarm/location/fusion/FusedLocationProvider.java`

**Provider Management:**

- GPS provider (primary)
- Network provider (backup)
- Passive provider (power saving)
- Fused location provider (Google Play Services)

**Key Features:**

```java
public class FusedLocationProvider {
    private LocationProvider primaryProvider;
    private LocationProvider backupProvider;
    private ProviderHealthMonitor healthMonitor;

    public void startLocationUpdates();

    public void switchProvider(LocationProvider newProvider);

    public Location getBestAvailableLocation();

    public ProviderStatus getProviderHealth();
}
```

#### Step 2: Provider Health Monitoring

**File**: `app/src/main/java/com/anchoralarm/location/fusion/ProviderHealthMonitor.java`

**Health Metrics:**

- Fix acquisition time
- Accuracy consistency
- Update frequency stability
- Provider availability

#### Step 3: Seamless Provider Switching

**Switching Logic:**

- Primary failure detection (<50% fix rate)
- Automatic backup activation
- Smooth transition algorithms
- User notification of provider changes

#### Step 4: Network-Assisted GPS (A-GPS)

**File**: `app/src/main/java/com/anchoralarm/location/fusion/AgpsHelper.java`

**Features:**

- Ephemeris data download
- Almanac updates
- Time synchronization
- Initial position assistance

#### Step 5: Background Operation Optimization

**Enhancements to**: `LocationService.java`

**Optimizations:**

- Doze mode handling
- App standby compatibility
- Foreground service optimization
- Battery usage minimization

---

## Phase 5: Enhanced UI Components

**Priority: MEDIUM | Timeline: 1-2 weeks | Expected Impact: Improved UX**

### Objectives

- Add constellation breakdown display
- Implement real-time accuracy metrics
- Create comprehensive filter status indicators
- Enhance signal quality visualization

### Implementation Steps

#### Step 1: Constellation Breakdown Widget

**File**: `app/src/main/java/com/anchoralarm/ui/ConstellationBreakdownView.java`

**Features:**

- Per-constellation satellite counts
- Signal strength visualization
- Color-coded constellation status
- Expandable detailed view

**UI Layout:**

```
GPS: 8/12 ████████░░░░ 85%
GLO: 6/8  ██████░░     75%
GAL: 4/6  ████░░       67%
BDS: 5/7  █████░░      71%
```

#### Step 2: Real-time Accuracy Metrics Display

**File**: `app/src/main/java/com/anchoralarm/ui/AccuracyMetricsView.java`

**Metrics Display:**

- Current accuracy (filtered vs raw)
- Accuracy trend (improving/degrading)
- Horizontal dilution of precision (HDOP)
- Position uncertainty ellipse

#### Step 3: Filter Status Indicators

**File**: `app/src/main/java/com/anchoralarm/ui/FilterStatusView.java`

**Status Indicators:**

- Kalman filter status (converged/converging)
- Outlier rejection count
- Smoothing effectiveness
- Filter performance metrics

#### Step 4: Enhanced Signal Quality Visualization

**Enhancements to existing signal quality display:**

**New Features:**

- Signal strength history graph
- Constellation-specific signal strength
- Signal quality prediction
- Atmospheric conditions impact

#### Step 5: Performance Dashboard

**File**: `app/src/main/java/com/anchoralarm/ui/PerformanceDashboardActivity.java`

**Dashboard Components:**

- Location accuracy history
- Battery usage statistics
- Provider switching history
- Filter performance metrics

---

## Performance Metrics & Success Criteria

### Accuracy Metrics

| Metric               | Current  | Target           | Measurement Method              |
|----------------------|----------|------------------|---------------------------------|
| Typical Accuracy     | 3-5m     | 1-2m             | 95th percentile over 1 hour     |
| GPS Jitter           | High     | 70-80% reduction | Standard deviation of positions |
| Fix Acquisition      | 30-60s   | <15s             | Time to first accurate fix      |
| Accuracy Consistency | Variable | ±20% deviation   | Accuracy stability over time    |

### Reliability Metrics

| Metric               | Current | Target         | Measurement Method            |
|----------------------|---------|----------------|-------------------------------|
| Service Uptime       | 95%     | 99%+           | 24-hour continuous monitoring |
| Provider Switching   | Manual  | Automatic      | Seamless transition count     |
| Background Operation | Limited | Optimized      | Doze mode compatibility       |
| Battery Impact       | High    | <5% additional | Battery usage statistics      |

### Performance Benchmarks

| Component         | Processing Time | Memory Usage | Success Criteria     |
|-------------------|-----------------|--------------|----------------------|
| Kalman Filter     | <5ms            | <1MB         | Real-time processing |
| Outlier Detection | <2ms            | <0.5MB       | 99% accuracy         |
| Provider Fusion   | <10ms           | <2MB         | Seamless switching   |
| UI Updates        | <16ms           | <5MB         | 60fps rendering      |

---

## Testing & Validation Procedures

### Unit Testing Requirements

- **Coverage Target**: >90% code coverage
- **Test Categories**: Algorithm validation, edge cases, performance
- **Mock Data**: Synthetic GPS traces, outlier injection

### Integration Testing

- **Real-world Testing**: Various geographic locations
- **Device Testing**: Different Android versions and hardware
- **Scenario Testing**: Boat anchoring, walking, driving

### Performance Testing

- **Stress Testing**: Extended operation (24+ hours)
- **Memory Profiling**: Leak detection and optimization
- **Battery Testing**: Power consumption measurement

### User Acceptance Testing

- **Accuracy Validation**: Side-by-side with reference GPS
- **Usability Testing**: UI/UX feedback collection
- **Reliability Testing**: Extended field trials

---

## Risk Assessment & Mitigation

### Technical Risks

| Risk                 | Probability | Impact | Mitigation Strategy                    |
|----------------------|-------------|--------|----------------------------------------|
| Device Compatibility | Medium      | High   | Graceful degradation, API level checks |
| Performance Impact   | Low         | Medium | Optimized algorithms, profiling        |
| Battery Drain        | Medium      | High   | Adaptive power management              |
| Accuracy Regression  | Low         | High   | Comprehensive testing, rollback plan   |

### Project Risks

| Risk                       | Probability | Impact | Mitigation Strategy            |
|----------------------------|-------------|--------|--------------------------------|
| Timeline Overrun           | Medium      | Medium | Phased delivery, MVP approach  |
| Resource Constraints       | Low         | Medium | Priority-based implementation  |
| Complexity Underestimation | Medium      | High   | Proof of concepts, prototyping |

---

## Implementation Timeline

### Phase 2: Location Filtering (Weeks 1-3)

- **Week 1**: Core filtering classes (Kalman, Smoother, Outlier)
- **Week 2**: Integration and configuration
- **Week 3**: Testing and validation

### Phase 4: Provider Fusion (Weeks 4-5)

- **Week 4**: Fused provider and health monitoring
- **Week 5**: Integration and testing

### Phase 5: Enhanced UI (Weeks 6-7)

- **Week 6**: UI components and widgets
- **Week 7**: Dashboard and final integration

### Phase 3: Dual-Frequency (Weeks 8-10)

- **Week 8**: Raw GNSS measurements
- **Week 9**: Dual-frequency processing
- **Week 10**: Advanced positioning algorithms

---

## Resource Requirements

### Development Resources

- **Senior Android Developer**: 40 hours/week
- **GNSS/GPS Specialist**: 20 hours/week (Phase 3)
- **UI/UX Designer**: 10 hours/week (Phase 5)
- **QA Engineer**: 15 hours/week (all phases)

### Hardware Requirements

- **Test Devices**: Multiple Android versions (7.0+)
- **GNSS Testing Equipment**: For accuracy validation
- **Reference GPS**: High-precision comparison unit

### Software Tools

- **Development**: Android Studio, Git
- **Testing**: JUnit, Espresso, GPS testing tools
- **Profiling**: Android Profiler, Memory Analyzer
- **Documentation**: Markdown, UML tools

---

## Conclusion

This roadmap provides a comprehensive path to significantly enhance the Anchor Alarm application's
accuracy, reliability, and user experience. The phased approach ensures manageable implementation
while delivering incremental value.

### Key Success Factors

1. **Prioritized Implementation**: Focus on high-impact, low-effort improvements first
2. **Comprehensive Testing**: Rigorous validation at each phase
3. **Performance Monitoring**: Continuous measurement and optimization
4. **User Feedback**: Regular validation with real-world usage

### Expected Outcomes

- **10x Accuracy Improvement**: From 3-5m to 1-2m typical accuracy
- **99%+ Reliability**: Robust background operation and provider switching
- **Enhanced User Experience**: Real-time feedback and professional-grade monitoring
- **Future-Proof Architecture**: Support for advanced GNSS features and devices

---

*This roadmap serves as the definitive guide for the Anchor Alarm enhancement project. Regular
updates and revisions should be made based on implementation progress and testing results.*
