package com.remopad.client;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

final class TrackpadView extends View {
    interface GestureListener {
        void onMove(float dx, float dy);
        void onScroll(float dx, float dy);
        void onButton(int button, boolean isDown);
        void onClick(int button);
    }

    private static final long TAP_TIMEOUT_MS = 260;

    private final SparseArray<TouchPoint> pointers = new SparseArray<>();
    private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private final float touchSlop;

    private GestureListener gestureListener;
    private float pointerSensitivity = 1f;
    private float scrollSensitivity = 1f;
    private long sessionStartedAt;
    private float sessionMovement;
    private int maximumPointerCount;
    private boolean dragging;

    TrackpadView(Context context) {
        this(context, null);
    }

    TrackpadView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        setBackgroundColor(Color.BLACK);
        setFocusable(true);
        setClickable(true);

        titlePaint.setColor(Color.rgb(67, 73, 82));
        titlePaint.setTextSize(18f * density);
        titlePaint.setFakeBoldText(true);
        titlePaint.setTextAlign(Paint.Align.CENTER);

        hintPaint.setColor(Color.rgb(50, 55, 63));
        hintPaint.setTextSize(12f * density);
        hintPaint.setTextAlign(Paint.Align.CENTER);
    }

    void setGestureListener(GestureListener listener) {
        gestureListener = listener;
    }

    void setPointerSensitivity(float sensitivity) {
        pointerSensitivity = sensitivity;
    }

    void setScrollSensitivity(float sensitivity) {
        scrollSensitivity = sensitivity;
    }

    void resetGestureState() {
        stopThreeFingerDrag();
        pointers.clear();
        sessionMovement = 0f;
        maximumPointerCount = 0;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float centerX = getWidth() / 2f;
        float centerY = getHeight() / 2f;
        canvas.drawText("触控区域", centerX, centerY - 4f * density, titlePaint);
        canvas.drawText("一指移动和轻点 · 双指滚动和右键 · 三指拖动",
                centerX, centerY + 22f * density, hintPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int actionIndex = event.getActionIndex();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                startSession();
                addPointer(event, actionIndex);
                maximumPointerCount = 1;
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                addPointer(event, actionIndex);
                maximumPointerCount = Math.max(maximumPointerCount, pointers.size());
                if (pointers.size() == 3 && maximumPointerCount == 3) {
                    startThreeFingerDrag();
                } else if (dragging) {
                    stopThreeFingerDrag();
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                handleMove(event);
                return true;

            case MotionEvent.ACTION_POINTER_UP:
                pointers.remove(event.getPointerId(actionIndex));
                if (dragging) {
                    stopThreeFingerDrag();
                }
                return true;

            case MotionEvent.ACTION_UP:
                pointers.remove(event.getPointerId(actionIndex));
                finishSession(false);
                return true;

            case MotionEvent.ACTION_CANCEL:
                pointers.clear();
                finishSession(true);
                return true;

            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override
    protected void onDetachedFromWindow() {
        resetGestureState();
        super.onDetachedFromWindow();
    }

    private void startSession() {
        resetGestureState();
        sessionStartedAt = SystemClock.uptimeMillis();
    }

    private void addPointer(MotionEvent event, int index) {
        int id = event.getPointerId(index);
        float x = event.getX(index);
        float y = event.getY(index);
        pointers.put(id, new TouchPoint(x, y));
    }

    private void startThreeFingerDrag() {
        if (dragging) {
            return;
        }
        dragging = true;
        if (gestureListener != null) {
            gestureListener.onButton(1, true);
        }
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
    }

    private void stopThreeFingerDrag() {
        if (!dragging) {
            return;
        }
        dragging = false;
        if (gestureListener != null) {
            gestureListener.onButton(1, false);
        }
    }

    private void handleMove(MotionEvent event) {
        float totalDx = 0f;
        float totalDy = 0f;
        int updated = 0;

        for (int index = 0; index < event.getPointerCount(); index++) {
            int id = event.getPointerId(index);
            TouchPoint point = pointers.get(id);
            if (point == null) {
                continue;
            }

            float x = event.getX(index);
            float y = event.getY(index);
            totalDx += x - point.lastX;
            totalDy += y - point.lastY;

            float distanceX = x - point.startX;
            float distanceY = y - point.startY;
            sessionMovement = Math.max(sessionMovement,
                    (float) Math.hypot(distanceX, distanceY));

            point.lastX = x;
            point.lastY = y;
            updated++;
        }

        if (updated == 0 || gestureListener == null) {
            return;
        }

        float averageDx = totalDx / updated / density;
        float averageDy = totalDy / updated / density;

        if (dragging && pointers.size() == 3) {
            gestureListener.onMove(averageDx * pointerSensitivity,
                    averageDy * pointerSensitivity);
        } else if (pointers.size() == 1 && maximumPointerCount == 1) {
            gestureListener.onMove(averageDx * pointerSensitivity,
                    averageDy * pointerSensitivity);
        } else if (pointers.size() == 2 && maximumPointerCount == 2) {
            gestureListener.onScroll(averageDx * scrollSensitivity,
                    averageDy * scrollSensitivity);
        }
    }

    private void finishSession(boolean cancelled) {
        stopThreeFingerDrag();

        if (!cancelled) {
            long duration = SystemClock.uptimeMillis() - sessionStartedAt;
            if (duration <= TAP_TIMEOUT_MS && sessionMovement < touchSlop) {
                if (maximumPointerCount == 1) {
                    performClick();
                    if (gestureListener != null) {
                        gestureListener.onClick(1);
                    }
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                } else if (maximumPointerCount == 2) {
                    if (gestureListener != null) {
                        gestureListener.onClick(2);
                    }
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                }
            }
        }

        sessionMovement = 0f;
        maximumPointerCount = 0;
    }

    private static final class TouchPoint {
        final float startX;
        final float startY;
        float lastX;
        float lastY;

        TouchPoint(float x, float y) {
            startX = x;
            startY = y;
            lastX = x;
            lastY = y;
        }
    }
}