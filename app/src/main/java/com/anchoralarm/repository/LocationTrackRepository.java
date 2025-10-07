package com.anchoralarm.repository;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;

import com.anchoralarm.model.LocationTrack;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class LocationTrackRepository {
    private static final String PREFS_NAME = "LocationTracks";
    private static final String KEY_TRACKS = "tracks";
    private static final int MAX_TRACKS = 1000; // Limit to prevent storage bloat

    private final SharedPreferences prefs;

    public LocationTrackRepository(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void addLocationTrack(Location location, Location anchorLocation) {
        if (location == null || anchorLocation == null) return;

        float distance = location.distanceTo(anchorLocation);
        float accuracy = location.hasAccuracy() ? location.getAccuracy() : 0.0f;
        
        LocationTrack track = new LocationTrack(
                System.currentTimeMillis(),
                location.getLatitude(),
                location.getLongitude(),
                accuracy,
                distance
        );

        List<LocationTrack> tracks = getAllTracks();
        tracks.add(track);

        // Keep only the most recent tracks to prevent storage bloat
        if (tracks.size() > MAX_TRACKS) {
            tracks = tracks.subList(tracks.size() - MAX_TRACKS, tracks.size());
        }

        saveTracks(tracks);
    }

    public List<LocationTrack> getAllTracks() {
        String tracksJson = prefs.getString(KEY_TRACKS, "[]");
        List<LocationTrack> tracks = new ArrayList<>();

        try {
            JSONArray jsonArray = new JSONArray(tracksJson);
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject jsonTrack = jsonArray.getJSONObject(i);
                LocationTrack track = new LocationTrack(
                        jsonTrack.getLong("timestamp"),
                        jsonTrack.getDouble("latitude"),
                        jsonTrack.getDouble("longitude"),
                        (float) jsonTrack.getDouble("accuracy"),
                        (float) jsonTrack.getDouble("distanceFromAnchor")
                );
                tracks.add(track);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }

        return tracks;
    }

    public List<LocationTrack> getRecentTracks(int count) {
        List<LocationTrack> allTracks = getAllTracks();
        if (allTracks.size() <= count) {
            return allTracks;
        }
        return allTracks.subList(allTracks.size() - count, allTracks.size());
    }

    public void clearTracks() {
        prefs.edit().remove(KEY_TRACKS).apply();
    }

    public int getTrackCount() {
        return getAllTracks().size();
    }

    private void saveTracks(List<LocationTrack> tracks) {
        JSONArray jsonArray = new JSONArray();
        
        for (LocationTrack track : tracks) {
            try {
                JSONObject jsonTrack = new JSONObject();
                jsonTrack.put("timestamp", track.getTimestamp());
                jsonTrack.put("latitude", track.getLatitude());
                jsonTrack.put("longitude", track.getLongitude());
                jsonTrack.put("accuracy", track.getAccuracy());
                jsonTrack.put("distanceFromAnchor", track.getDistanceFromAnchor());
                jsonArray.put(jsonTrack);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        prefs.edit().putString(KEY_TRACKS, jsonArray.toString()).apply();
    }
}
