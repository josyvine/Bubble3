package com.app.bubble;

import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent; // FIX: Added missing import
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

public class TwoLineOverlayService extends Service {

    private WindowManager windowManager;
    
    // Window 1: The Lines (Full Screen)
    private View linesView;
    private WindowManager.LayoutParams linesParams;
    private View lineTop, lineBottom;
    private ImageView handleTop, handleBottom;
    private TextView helperText;
    
    // Window 2: The Controls (Bottom Only)
    private View controlsView;
    private WindowManager.LayoutParams controlsParams;
    private Button btnAction;
    private ImageView btnClose;

    private int currentState = 0; // 0=Set Green, 1=Scrolling, 2=Set Red
    private int screenHeight;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        screenHeight = getResources().getDisplayMetrics().heightPixels;

        setupWindows();
    }

    private void setupWindows() {
        int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ?
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                WindowManager.LayoutParams.TYPE_PHONE;

        LayoutInflater inflater = LayoutInflater.from(this);

        // --- SETUP LINES WINDOW (Full Screen) ---
        linesView = inflater.inflate(R.layout.layout_two_line_overlay, null);
        // Hide controls in this window, we only want lines
        linesView.findViewById(R.id.btn_action).setVisibility(View.GONE);
        linesView.findViewById(R.id.btn_close).setVisibility(View.GONE);
        
        lineTop = linesView.findViewById(R.id.line_top);
        handleTop = linesView.findViewById(R.id.handle_top);
        lineBottom = linesView.findViewById(R.id.line_bottom);
        handleBottom = linesView.findViewById(R.id.handle_bottom);
        helperText = linesView.findViewById(R.id.helper_text);

        linesParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | 
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, 
                PixelFormat.TRANSLUCENT
        );
        windowManager.addView(linesView, linesParams);

        // --- SETUP CONTROLS WINDOW (Bottom) ---
        controlsView = inflater.inflate(R.layout.layout_two_line_overlay, null);
        // Hide lines in this window, we only want buttons
        controlsView.findViewById(R.id.line_top).setVisibility(View.GONE);
        controlsView.findViewById(R.id.handle_top).setVisibility(View.GONE);
        controlsView.findViewById(R.id.line_bottom).setVisibility(View.GONE);
        controlsView.findViewById(R.id.handle_bottom).setVisibility(View.GONE);
        controlsView.findViewById(R.id.helper_text).setVisibility(View.GONE);

        btnAction = controlsView.findViewById(R.id.btn_action);
        btnClose = controlsView.findViewById(R.id.btn_close);

        controlsParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, // Always touchable
                PixelFormat.TRANSLUCENT
        );
        controlsParams.gravity = Gravity.BOTTOM | Gravity.END;
        controlsParams.y = 50; // Margin bottom
        windowManager.addView(controlsView, controlsParams);

        setupTouchListeners();
        setupControlLogic();
    }

    private void setupControlLogic() {
        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Ensure scrolling stops if we close mid-way
                GlobalScrollService.stopScroll();
                // Signal to stop burst if running
                FloatingTranslatorService service = FloatingTranslatorService.getInstance();
                if (service != null) service.stopBurstCapture();
                stopSelf();
            }
        });

        btnAction.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (currentState == 0) {
                    // STATE: GREEN LINE SET -> START SCROLLING
                    currentState = 1;
                    
                    // 1. Lock Green Line visuals
                    handleTop.setVisibility(View.GONE);
                    helperText.setText("Scroll to end of text.\nClick STOP when done.");
                    
                    // 2. Change Action Button
                    btnAction.setText("STOP SCROLL");
                    // 0xFFFF0000 is Red
                    btnAction.setBackgroundColor(0xFFFF0000); 

                    // 3. Make Lines Window "Pass Through" so user can scroll browser
                    linesParams.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | 
                                      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                    windowManager.updateViewLayout(linesView, linesParams);

                    // 4. Start Burst Capture
                    FloatingTranslatorService service = FloatingTranslatorService.getInstance();
                    if (service != null) service.startBurstCapture();

                } else if (currentState == 1) {
                    // STATE: SCROLLING DONE -> SHOW RED LINE
                    currentState = 2;

                    // 1. Stop Burst Capture
                    FloatingTranslatorService service = FloatingTranslatorService.getInstance();
                    if (service != null) service.stopBurstCapture();

                    // 2. Make Lines Window Touchable again
                    linesParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | 
                                      WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
                    windowManager.updateViewLayout(linesView, linesParams);

                    // 3. Show Red Line
                    lineBottom.setVisibility(View.VISIBLE);
                    handleBottom.setVisibility(View.VISIBLE);
                    
                    // 4. Update UI
                    helperText.setText("Place Red Line at END of text.");
                    btnAction.setText("COPY");
                    // 0xFF4CAF50 is Green
                    btnAction.setBackgroundColor(0xFF4CAF50); 

                } else {
                    // STATE: RED LINE SET -> COPY
                    finishCopy();
                }
            }
        });
    }

    private void finishCopy() {
        // Calculate coordinates
        int[] topLocation = new int[2];
        lineTop.getLocationOnScreen(topLocation);
        int[] bottomLocation = new int[2];
        lineBottom.getLocationOnScreen(bottomLocation);

        int topY = topLocation[1];
        int bottomY = bottomLocation[1];

        if (topY >= bottomY) {
            Toast.makeText(this, "Top line must be above bottom line", Toast.LENGTH_SHORT).show();
            return;
        }

        // We use a full-screen rect. The stitching logic in FloatingTranslatorService
        // will use these Y coordinates to crop the top of the first image 
        // and the bottom of the last image.
        Rect selectionRect = new Rect(0, topY, getResources().getDisplayMetrics().widthPixels, bottomY);

        Intent intent = new Intent(this, FloatingTranslatorService.class);
        intent.putExtra("RECT", selectionRect);
        intent.putExtra("COPY_TO_CLIPBOARD", true);
        startService(intent);

        Toast.makeText(this, "Processing...", Toast.LENGTH_SHORT).show();
        stopSelf();
    }

    private void setupTouchListeners() {
        View.OnTouchListener moveListener = new View.OnTouchListener() {
            float dY, initialY;
            @Override
            public boolean onTouch(View view, MotionEvent event) {
                // If in Scroll Mode (1), ignore touches (though FLAG_NOT_TOUCHABLE handles this)
                if (currentState == 1) return false;

                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        dY = view.getY() - event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float newY = event.getRawY() + dY;
                        if (newY < 0) newY = 0;
                        if (newY > screenHeight - view.getHeight()) newY = screenHeight - view.getHeight();
                        view.setY(newY);
                        return true;
                }
                return false;
            }
        };

        lineTop.setOnTouchListener(moveListener);
        lineBottom.setOnTouchListener(moveListener);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (linesView != null) windowManager.removeView(linesView);
        if (controlsView != null) windowManager.removeView(controlsView);
    }
}