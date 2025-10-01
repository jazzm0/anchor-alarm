package com.anchoralarm;

import static android.graphics.Typeface.BOLD;
import static java.lang.Math.min;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;

import java.util.Locale;

public class SwoyRadiusView extends View implements SensorEventListener {
    private Paint circlePaint;
    private Paint fillPaint;
    private Paint anchorPaint;
    private Paint boatPaint;
    private Paint textPaint;
    private Paint northIndicatorPaint;
    private Paint northTextPaint;

    private Location anchorLocation;
    private Location currentLocation;
    private float driftRadius;
    private float locationAccuracy;

    private float boatX = 0.5f; // Relative position (0-1)
    private float boatY = 0.5f; // Relative position (0-1)

    // Compass/Orientation fields
    private SensorManager sensorManager;
    private Sensor magnetometer;
    private Sensor accelerometer;
    private final float[] lastAccelerometer = new float[3];
    private final float[] lastMagnetometer = new float[3];
    private boolean lastAccelerometerSet = false;
    private boolean lastMagnetometerSet = false;
    private final float[] rotationMatrix = new float[9];
    private final float[] orientation = new float[3];
    private float currentAzimuth = 0f; // Current compass bearing in radians

    public SwoyRadiusView(Context context) {
        super(context);
        init();
    }

    public SwoyRadiusView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SwoyRadiusView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        // Circle paint (dashed red border)
        circlePaint = new Paint();
        circlePaint.setColor(Color.parseColor("#34BF59"));
        circlePaint.setStyle(Paint.Style.STROKE);
        circlePaint.setStrokeWidth(6f);
        circlePaint.setPathEffect(new DashPathEffect(new float[]{16f, 8f}, 0));
        circlePaint.setAntiAlias(true);

        // Fill paint (semi-transparent red)
        fillPaint = new Paint();
        fillPaint.setColor(Color.parseColor("#1AFF5722"));
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setAntiAlias(true);

        // Anchor paint (blue)
        anchorPaint = new Paint();
        anchorPaint.setColor(Color.parseColor("#2196F3"));
        anchorPaint.setStyle(Paint.Style.FILL);
        anchorPaint.setAntiAlias(true);

        // Boat paint (green)
        boatPaint = new Paint();
        boatPaint.setColor(Color.parseColor("#4CAF50")); // This is already opaque
        boatPaint.setStyle(Paint.Style.STROKE);
        boatPaint.setStrokeWidth(4f);
        boatPaint.setAntiAlias(true);

        // Text paint
        textPaint = new Paint();
        textPaint.setColor(Color.parseColor("#333333"));
        textPaint.setTextSize(30f);
        textPaint.setAntiAlias(true);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, BOLD));
        textPaint.setTextAlign(Paint.Align.CENTER);

        // North indicator paint (red)
        northIndicatorPaint = new Paint();
        northIndicatorPaint.setColor(Color.parseColor("#FF5722"));
        northIndicatorPaint.setStyle(Paint.Style.FILL);
        northIndicatorPaint.setStrokeWidth(8f);
        northIndicatorPaint.setAntiAlias(true);

        // Draw north indicator triangle
        northTextPaint = new Paint();
        northTextPaint.setColor(Color.parseColor("#FF5722"));
        northTextPaint.setTextSize(24f);
        northTextPaint.setAntiAlias(true);
        northTextPaint.setTypeface(Typeface.create(Typeface.DEFAULT, BOLD));
        northTextPaint.setTextAlign(Paint.Align.CENTER);

        // Initialize compass sensors
        sensorManager = (SensorManager) getContext().getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }
    }

    public void updatePositions(Location anchor, Location current, float radius, float accuracy) {
        this.anchorLocation = anchor;
        this.currentLocation = current;
        this.driftRadius = radius;
        this.locationAccuracy = accuracy;

        if (anchor != null && current != null && radius > 0) {
            // Calculate relative position of boat to anchor
            float distance = anchor.distanceTo(current);

            if (distance > 0) {
                // Calculate bearing from anchor to boat (true bearing in radians)
                double trueBearing = Math.toRadians(anchor.bearingTo(current));

                // Adjust bearing to compensate for device orientation (subtract device azimuth)
                double adjustedBearing = trueBearing - currentAzimuth;

                // Scale the distance relative to the drift radius
                // If boat is at drift radius, it should be at circle edge (0.45 from center)
                float normalizedDistance = min(distance / radius, 1.0f) * 0.45f;

                // Convert polar to cartesian coordinates with north orientation compensation
                // Center is at (0.5, 0.5), so we add the offset
                boatX = 0.5f + (float) (normalizedDistance * Math.sin(adjustedBearing));
                boatY = 0.5f - (float) (normalizedDistance * Math.cos(adjustedBearing)); // Subtract because Y increases downward
            } else {
                // Boat is at anchor position
                boatX = 0.5f;
                boatY = 0.5f;
            }
        }

        invalidate(); // Trigger redraw
    }

    // Sensor event handling for compass
    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, lastAccelerometer, 0, event.values.length);
            lastAccelerometerSet = true;
        } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, lastMagnetometer, 0, event.values.length);
            lastMagnetometerSet = true;
        }

        if (lastAccelerometerSet && lastMagnetometerSet) {
            boolean success = SensorManager.getRotationMatrix(rotationMatrix, null, lastAccelerometer, lastMagnetometer);
            if (success) {
                SensorManager.getOrientation(rotationMatrix, orientation);
                // Apply low-pass filter to smooth compass readings
                float alpha = 0.1f; // Smoothing factor
                currentAzimuth = alpha * orientation[0] + (1 - alpha) * currentAzimuth;
                invalidate(); // Trigger redraw with updated orientation
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Handle sensor accuracy changes if needed
    }

    // Start compass sensor listening
    public void startCompass() {
        if (sensorManager != null && magnetometer != null && accelerometer != null) {
            sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_GAME);
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    // Stop compass sensor listening
    public void stopCompass() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startCompass();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopCompass();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);

        int width = getWidth();
        int height = getHeight();
        int size = min(width, height);
        float centerX = width / 2f;
        float centerY = height / 2f;
        float radius = (size - 40f) / 2f; // Leave 20px margin on each side

        // Draw filled circle
        canvas.drawCircle(centerX, centerY, radius, fillPaint);

        // Draw dashed circle border
        canvas.drawCircle(centerX, centerY, radius, circlePaint);

        // Draw north indicator (red triangle pointing to true north)
        // North is always at the top of the screen in our coordinate system
        float northY = centerY - radius - 30f; // Position above the circle


        // Draw "N" text for north indicator
        canvas.drawText("N", centerX, northY, northTextPaint);

        // Draw arrow pointing north
        float arrowSize = 15f;
        canvas.drawLine(centerX, northY + 10, centerX, northY + 25, northIndicatorPaint);
        canvas.drawLine(centerX, northY + 10, centerX - arrowSize / 2, northY + 20, northIndicatorPaint);
        canvas.drawLine(centerX, northY + 10, centerX + arrowSize / 2, northY + 20, northIndicatorPaint);

        // Draw anchor at center
        canvas.drawCircle(centerX, centerY, 16f, anchorPaint);
        canvas.drawCircle(centerX, centerY, 8f, new Paint() {{
            setColor(Color.WHITE);
            setStyle(Paint.Style.FILL);
            setAntiAlias(true);
        }});

        // Draw boat position if locations are available
        if (anchorLocation != null && currentLocation != null) {
            float boatPixelX = width * boatX;
            float boatPixelY = height * boatY;

            float boatRadius = min(radius / 2, locationAccuracy * 2);
            canvas.drawCircle(boatPixelX, boatPixelY, boatRadius, boatPaint);
            canvas.drawCircle(boatPixelX, boatPixelY, 6f, new Paint() {{
                setColor(Color.YELLOW);
                setStyle(Paint.Style.FILL);
                setAntiAlias(true);
            }});

            // Draw line from anchor to boat
            Paint linePaint = new Paint();
            linePaint.setColor(Color.parseColor("#666666"));
            linePaint.setStrokeWidth(2f);
            linePaint.setAntiAlias(true);
            canvas.drawLine(centerX, centerY, boatPixelX, boatPixelY, linePaint);

            // Draw distance text
            if (driftRadius > 0) {
                float distance = anchorLocation.distanceTo(currentLocation);
                String distanceText = String.format(Locale.ENGLISH, "%.1fm", distance);
                canvas.drawText(distanceText, boatPixelX, boatPixelY + 30, textPaint);
            }
        }
    }
}
