package com.app.bubble;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * This service is responsible for performing global scroll gestures
 * to allow "Unlimited Copy" functionality across multiple screens.
 */
public class GlobalScrollService extends AccessibilityService {

    private static GlobalScrollService sInstance;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        Log.d("GlobalScrollService", "Service Connected");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        sInstance = null;
        Log.d("GlobalScrollService", "Service Unbound");
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // We do not need to listen to specific events for this feature,
        // but the method is required to be overridden.
    }

    @Override
    public void onInterrupt() {
        // Required method override.
    }

    /**
     * Returns the current instance of the service.
     */
    public static GlobalScrollService getInstance() {
        return sInstance;
    }

    /**
     * Performs a vertical swipe (scroll) gesture on the screen.
     * @return true if the gesture was dispatched, false otherwise.
     */
    public static boolean performGlobalScroll() {
        if (sInstance == null) {
            return false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            DisplayMetrics metrics = sInstance.getResources().getDisplayMetrics();
            int width = metrics.widthPixels;
            int height = metrics.heightPixels;

            // Start from lower part of the screen (75% height)
            float startX = width / 2.0f;
            float startY = height * 0.75f;

            // End at upper part of the screen (25% height)
            float endX = width / 2.0f;
            float endY = height * 0.25f;

            Path path = new Path();
            path.moveTo(startX, startY);
            path.lineTo(endX, endY);

            GestureDescription.Builder builder = new GestureDescription.Builder();
            // Create a stroke that takes 500ms to complete
            GestureDescription gesture = builder
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, 500))
                    .build();

            return sInstance.dispatchGesture(gesture, new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    super.onCompleted(gestureDescription);
                    Log.d("GlobalScrollService", "Scroll Gesture Completed");
                }

                @Override
                public void onCancelled(GestureDescription gestureDescription) {
                    super.onCancelled(gestureDescription);
                    Log.d("GlobalScrollService", "Scroll Gesture Cancelled");
                }
            }, null);
        } else {
            // Fallback for very old devices (below Android 7.0), though the app minSdk is 21.
            // Gestures are not supported via AccessibilityService below API 24.
            return false;
        }
    }
}