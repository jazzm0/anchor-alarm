package com.anchoralarm.model;

public class LocationTrack {
    private final long timestamp;
    private final double latitude;
    private final double longitude;
    private final float accuracy;
    private final float distanceFromAnchor;

    public LocationTrack(long timestamp, double latitude, double longitude, float accuracy, float distanceFromAnchor) {
        this.timestamp = timestamp;
        this.latitude = latitude;
        this.longitude = longitude;
        this.accuracy = accuracy;
        this.distanceFromAnchor = distanceFromAnchor;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public float getAccuracy() {
        return accuracy;
    }

    public float getDistanceFromAnchor() {
        return distanceFromAnchor;
    }

    @Override
    public String toString() {
        return String.format("LocationTrack{timestamp=%d, lat=%.6f, lon=%.6f, accuracy=%.1f, distance=%.1f}",
                timestamp, latitude, longitude, accuracy, distanceFromAnchor);
    }
}
