package com.anchoralarm.location;

import android.location.GnssStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Monitors and categorizes GNSS satellites by constellation type
 * Provides detailed information about GPS, GLONASS, Galileo, BeiDou, and QZSS satellites
 */
public class GnssConstellationMonitor {

    public enum ConstellationType {
        GPS("GPS", 0x1),
        GLONASS("GLO", 0x3),
        GALILEO("GAL", 0x6),
        BEIDOU("BDS", 0x5),
        QZSS("QZSS", 0x4),
        IRNSS("IRNSS", 0x7),
        SBAS("SBAS", 0x2),
        UNKNOWN("UNK", 0x0);

        private final String displayName;
        private final int gnssType;

        ConstellationType(String displayName, int gnssType) {
            this.displayName = displayName;
            this.gnssType = gnssType;
        }

        public String getDisplayName() {
            return displayName;
        }

        public int getGnssType() {
            return gnssType;
        }

        public static ConstellationType fromGnssType(int gnssType) {
            for (ConstellationType type : values()) {
                if (type.gnssType == gnssType) {
                    return type;
                }
            }
            return UNKNOWN;
        }
    }

    public record SatelliteInfo(int svid, ConstellationType constellation, float cn0DbHz,
                                float elevation, float azimuth, boolean usedInFix,
                                boolean hasEphemeris, boolean hasAlmanac) {
    }

    public static class ConstellationStats {
        public final ConstellationType type;
        public final int totalSatellites;
        public final int usedInFix;
        public final float averageCn0;
        public final float maxCn0;
        public final List<SatelliteInfo> satellites;

        public ConstellationStats(ConstellationType type, List<SatelliteInfo> satellites) {
            this.type = type;
            this.satellites = new ArrayList<>(satellites);
            this.totalSatellites = satellites.size();

            int used = 0;
            float totalCn0 = 0;
            float max = 0;

            for (SatelliteInfo sat : satellites) {
                if (sat.usedInFix) used++;
                totalCn0 += sat.cn0DbHz;
                if (sat.cn0DbHz > max) max = sat.cn0DbHz;
            }

            this.usedInFix = used;
            this.averageCn0 = totalSatellites > 0 ? totalCn0 / totalSatellites : 0;
            this.maxCn0 = max;
        }
    }

    private final Map<ConstellationType, List<SatelliteInfo>> constellationMap = new HashMap<>();
    private int totalSatellites = 0;
    private int totalUsedInFix = 0;

    /**
     * Process GNSS status and categorize satellites by constellation
     */
    public void processGnssStatus(GnssStatus status) {
        constellationMap.clear();
        totalSatellites = status.getSatelliteCount();
        totalUsedInFix = 0;

        // Initialize constellation lists
        for (ConstellationType type : ConstellationType.values()) {
            constellationMap.put(type, new ArrayList<>());
        }

        // Process each satellite
        for (int i = 0; i < totalSatellites; i++) {
            int svid = status.getSvid(i);
            int constellationTypeInt = status.getConstellationType(i);
            ConstellationType constellation = ConstellationType.fromGnssType(constellationTypeInt);

            float cn0DbHz = status.getCn0DbHz(i);
            float elevation = status.getElevationDegrees(i);
            float azimuth = status.getAzimuthDegrees(i);
            boolean usedInFix = status.usedInFix(i);
            boolean hasEphemeris = status.hasEphemerisData(i);
            boolean hasAlmanac = status.hasAlmanacData(i);

            if (usedInFix) {
                totalUsedInFix++;
            }

            SatelliteInfo satInfo = new SatelliteInfo(svid, constellation, cn0DbHz,
                    elevation, azimuth, usedInFix, hasEphemeris, hasAlmanac);

            constellationMap.get(constellation).add(satInfo);
        }
    }

    /**
     * Get statistics for a specific constellation
     */
    public ConstellationStats getConstellationStats(ConstellationType type) {
        List<SatelliteInfo> satellites = constellationMap.get(type);
        if (satellites == null) {
            satellites = new ArrayList<>();
        }
        return new ConstellationStats(type, satellites);
    }

    /**
     * Get statistics for all constellations
     */
    public Map<ConstellationType, ConstellationStats> getAllConstellationStats() {
        Map<ConstellationType, ConstellationStats> stats = new HashMap<>();
        for (ConstellationType type : ConstellationType.values()) {
            ConstellationStats stat = getConstellationStats(type);
            if (stat.totalSatellites > 0) {
                stats.put(type, stat);
            }
        }
        return stats;
    }

    /**
     * Get total number of satellites visible
     */
    public int getTotalSatellites() {
        return totalSatellites;
    }

    /**
     * Get total number of satellites used in position fix
     */
    public int getTotalUsedInFix() {
        return totalUsedInFix;
    }

    /**
     * Get the strongest constellation by signal strength
     */
    public ConstellationType getStrongestConstellation() {
        ConstellationType strongest = ConstellationType.UNKNOWN;
        float maxAvgCn0 = 0;

        for (ConstellationType type : ConstellationType.values()) {
            ConstellationStats stats = getConstellationStats(type);
            if (stats.totalSatellites > 0 && stats.averageCn0 > maxAvgCn0) {
                maxAvgCn0 = stats.averageCn0;
                strongest = type;
            }
        }

        return strongest;
    }

    /**
     * Calculate overall signal quality (0-100)
     */
    public int getOverallSignalQuality() {
        if (totalSatellites == 0) return 0;

        float totalCn0 = 0;
        int count = 0;

        for (List<SatelliteInfo> satellites : constellationMap.values()) {
            for (SatelliteInfo sat : satellites) {
                if (sat.cn0DbHz > 0) {
                    totalCn0 += sat.cn0DbHz;
                    count++;
                }
            }
        }

        if (count == 0) return 0;

        float avgCn0 = totalCn0 / count;
        // Convert C/N0 (typically 20-50 dBHz) to 0-100 scale
        // 20 dBHz = poor, 35 dBHz = good, 45+ dBHz = excellent
        int quality = Math.round(((avgCn0 - 20) / 25) * 100);
        return Math.max(0, Math.min(100, quality));
    }
}
