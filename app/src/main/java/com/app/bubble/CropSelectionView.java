package com.app.bubble;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;

public class CropSelectionView extends View {

    private Paint paint;
    private Paint borderPaint;
    private float startX, startY, endX, endY;
    private RectF selectionRect = new RectF();

    private Handler autoCloseHandler = new Handler(Looper.getMainLooper());
    private Runnable autoCloseRunnable;
    private long timeoutDuration; 
    
    private int screenHeight;
    private static final int SCROLL_THRESHOLD = 150; // Pixels from bottom to trigger scroll

    public CropSelectionView(Context context) {
        super(context);
        init();
    }

    private void init() {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        screenHeight = metrics.heightPixels;

        paint = new Paint();
        paint.setColor(Color.argb(100, 0, 100, 255)); // Transparent blue
        paint.setStyle(Paint.Style.FILL);

        borderPaint = new Paint();
        borderPaint.setColor(Color.BLUE);
        borderPaint.setStrokeWidth(5f);
        borderPaint.setStyle(Paint.Style.STROKE);

        // Read settings
        SharedPreferences prefs = getContext().getSharedPreferences(
            SettingsActivity.PREFS_NAME, 
            Context.MODE_PRIVATE
        );
        timeoutDuration = prefs.getLong(SettingsActivity.KEY_TIMER_DURATION, 5000L);

        autoCloseRunnable = new Runnable() {
            @Override
            public void run() {
                // Ensure we stop scrolling if the timer kills the view
                GlobalScrollService.stopScroll();
                
                // Tell the service that selection is finished
                try {
                    FloatingTranslatorService service = (FloatingTranslatorService) getContext();
                    Rect finalRect = new Rect(
                        (int) selectionRect.left,
                        (int) selectionRect.top,
                        (int) selectionRect.right,
                        (int) selectionRect.bottom
                    );
                    service.onCropFinished(finalRect);
                } catch (ClassCastException e) {
                    e.printStackTrace();
                }
            }
        };
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        selectionRect.set(
            Math.min(startX, endX),
            Math.min(startY, endY),
            Math.max(startX, endX),
            Math.max(startY, endY)
        );
        // Draw the blue fill
        canvas.drawRect(selectionRect, paint);
        // Draw the border
        canvas.drawRect(selectionRect, borderPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                startX = event.getRawX();
                startY = event.getRawY();
                endX = startX;
                endY = startY;
                resetAutoCloseTimer();
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                endX = event.getRawX();
                endY = event.getRawY();
                
                // *** DRAG-TO-SCROLL LOGIC ***
                // Check if finger is at the bottom edge of the screen
                if (endY >= screenHeight - SCROLL_THRESHOLD) {
                    // Trigger continuous smooth scrolling
                    GlobalScrollService.startSmoothScroll();
                } else {
                    // Stop scrolling if finger moves away from edge
                    GlobalScrollService.stopScroll();
                }

                resetAutoCloseTimer();
                invalidate();
                return true;

            case MotionEvent.ACTION_UP:
                // Stop scrolling immediately when finger lifts
                GlobalScrollService.stopScroll();
                return true;
        }
        return false;
    }

    private void resetAutoCloseTimer() {
        autoCloseHandler.removeCallbacks(autoCloseRunnable);
        autoCloseHandler.postDelayed(autoCloseRunnable, timeoutDuration);
    }
    
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // Safety check to ensure scrolling stops if view is removed
        GlobalScrollService.stopScroll();
    }
}