package com.anchoralarm.model;

public record LocationTrack(long timestamp, double latitude, double longitude, float accuracy) {

    @Override
    public String toString() {
        return String.format("LocationTrack{timestamp=%d, lat=%.6f, lon=%.6f, accuracy=%.1f}",
                timestamp, latitude, longitude, accuracy);
    }
}
