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
    private static final long DRAG_HOLD_MS = 360;

    private final SparseArray<TouchPoint> pointers = new SparseArray<>();
    private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private float touchSlop;

    private GestureListener gestureListener;
    private float pointerSensitivity = 1f;
    private float scrollSensitivity = 1f;
    private long sessionStartedAt;
    private float sessionMovement;
    private int maximumPointerCount;
    private boolean dragging;

    private final Runnable longPressRunnable = () -> {
        if (pointers.size() == 1
                && maximumPointerCount == 1
                && sessionMovement < touchSlop
                && !dragging) {
            dragging = true;
            if (gestureListener != null) {
                gestureListener.onButton(1, true);
            }
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        }
    };

    TrackpadView(Context context) {
        this(context, null);
    }

    TrackpadView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        setBackgroundColor(Color.rgb(250, 250, 251));
        setFocusable(true);
        setClickable(true);

        titlePaint.setColor(Color.rgb(35, 39, 47));
        titlePaint.setTextSize(18f * density);
        titlePaint.setFakeBoldText(true);

        hintPaint.setColor(Color.rgb(105, 112, 124));
        hintPaint.setTextSize(12f * density);
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

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left = 18f * density;
        canvas.drawText("触控区域", left, 36f * density, titlePaint);
        canvas.drawText("一指移动和轻点 · 长按拖动 · 双指滚动和右键",
                left, 58f * density, hintPaint);
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
                scheduleLongPress();
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                addPointer(event, actionIndex);
                maximumPointerCount = Math.max(maximumPointerCount, pointers.size());
                cancelPendingLongPress();
                return true;

            case MotionEvent.ACTION_MOVE:
                handleMove(event);
                return true;

            case MotionEvent.ACTION_POINTER_UP:
                pointers.remove(event.getPointerId(actionIndex));
                cancelPendingLongPress();
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
        cancelPendingLongPress();
        if (dragging && gestureListener != null) {
            gestureListener.onButton(1, false);
        }
        dragging = false;
        pointers.clear();
        super.onDetachedFromWindow();
    }

    private void startSession() {
        cancelPendingLongPress();
        pointers.clear();
        sessionStartedAt = SystemClock.uptimeMillis();
        sessionMovement = 0f;
        maximumPointerCount = 0;
        dragging = false;
    }

    private void addPointer(MotionEvent event, int index) {
        int id = event.getPointerId(index);
        float x = event.getX(index);
        float y = event.getY(index);
        pointers.put(id, new TouchPoint(x, y));
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

        if (updated == 0) {
            return;
        }

        if (sessionMovement > touchSlop) {
            cancelPendingLongPress();
        }

        float averageDx = totalDx / updated / density;
        float averageDy = totalDy / updated / density;

        if (gestureListener == null) {
            return;
        }

        if (pointers.size() == 1 && maximumPointerCount == 1) {
            gestureListener.onMove(averageDx * pointerSensitivity,
                    averageDy * pointerSensitivity);
        } else if (pointers.size() == 2 && maximumPointerCount == 2) {
            gestureListener.onScroll(averageDx * scrollSensitivity,
                    averageDy * scrollSensitivity);
        }
    }

    private void finishSession(boolean cancelled) {
        cancelPendingLongPress();

        if (dragging) {
            if (gestureListener != null) {
                gestureListener.onButton(1, false);
            }
        } else if (!cancelled) {
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

        dragging = false;
        sessionMovement = 0f;
        maximumPointerCount = 0;
    }

    private void scheduleLongPress() {
        cancelPendingLongPress();
        postDelayed(longPressRunnable, DRAG_HOLD_MS);
    }

    private void cancelPendingLongPress() {
        removeCallbacks(longPressRunnable);
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
