package com.anchoralarm;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.location.Location;
import android.util.AttributeSet;
import android.view.View;

public class SwoyRadiusView extends View {
    private Paint circlePaint;
    private Paint fillPaint;
    private Paint anchorPaint;
    private Paint boatPaint;
    private Paint textPaint;

    private Location anchorLocation;
    private Location currentLocation;
    private float driftRadius;

    private float boatX = 0.5f; // Relative position (0-1)
    private float boatY = 0.5f; // Relative position (0-1)

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
        boatPaint.setColor(Color.parseColor("#4CAF50"));
        boatPaint.setStyle(Paint.Style.FILL);
        boatPaint.setAntiAlias(true);

        // Text paint
        textPaint = new Paint();
        textPaint.setColor(Color.parseColor("#333333"));
        textPaint.setTextSize(11f);
        textPaint.setAntiAlias(true);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void updatePositions(Location anchor, Location current, float radius) {
        this.anchorLocation = anchor;
        this.currentLocation = current;
        this.driftRadius = radius;

        if (anchor != null && current != null && radius > 0) {
            // Calculate relative position of boat to anchor
            float distance = anchor.distanceTo(current);

            if (distance > 0) {
                // Calculate bearing from anchor to boat
                double bearing = Math.toRadians(anchor.bearingTo(current));

                // Scale the distance relative to the drift radius
                // If boat is at drift radius, it should be at circle edge (0.45 from center)
                float normalizedDistance = Math.min(distance / radius, 1.0f) * 0.45f;

                // Convert polar to cartesian coordinates
                // Center is at (0.5, 0.5), so we add the offset
                boatX = 0.5f + (float) (normalizedDistance * Math.sin(bearing));
                boatY = 0.5f - (float) (normalizedDistance * Math.cos(bearing)); // Subtract because Y increases downward
            } else {
                // Boat is at anchor position
                boatX = 0.5f;
                boatY = 0.5f;
            }
        }

        invalidate(); // Trigger redraw
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int width = getWidth();
        int height = getHeight();
        int size = Math.min(width, height);
        float centerX = width / 2f;
        float centerY = height / 2f;
        float radius = (size - 40f) / 2f; // Leave 20px margin on each side

        // Draw filled circle
        canvas.drawCircle(centerX, centerY, radius, fillPaint);

        // Draw dashed circle border
        canvas.drawCircle(centerX, centerY, radius, circlePaint);

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

            // Draw boat
            canvas.drawCircle(boatPixelX, boatPixelY, 12f, boatPaint);
            canvas.drawCircle(boatPixelX, boatPixelY, 6f, new Paint() {{
                setColor(Color.WHITE);
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
                String distanceText = String.format("%.1fm", distance);
                canvas.drawText(distanceText, boatPixelX, boatPixelY + 30, textPaint);
            }
        }
    }
}
