package com.remopad.client;

import android.content.Context;
import android.graphics.Color;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;

final class KeyboardInputView extends android.widget.EditText {
    interface Listener {
        void onText(String text);
        void onKeyPress(int key);
    }

    private Listener listener;

    KeyboardInputView(Context context) {
        super(context);
        setSingleLine(false);
        setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        setBackgroundColor(Color.TRANSPARENT);
        setTextColor(Color.TRANSPARENT);
        setCursorVisible(false);
        setPadding(0, 0, 0, 0);
    }

    void setKeyboardListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        InputConnection target = super.onCreateInputConnection(outAttrs);
        if (target == null) {
            return null;
        }
        return new InputConnectionWrapper(target, true) {
            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                emitText(text);
                boolean result = super.commitText(text, newCursorPosition);
                post(KeyboardInputView.this::clearLocalBuffer);
                return result;
            }

            @Override
            public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                if (!hasComposingText()) {
                    emitRepeatedKey(Protocol.KEY_BACKSPACE,
                            Math.max(1, beforeLength));
                }
                return super.deleteSurroundingText(beforeLength, afterLength);
            }

            @Override
            public boolean deleteSurroundingTextInCodePoints(
                    int beforeLength, int afterLength) {
                if (!hasComposingText()) {
                    emitRepeatedKey(Protocol.KEY_BACKSPACE,
                            Math.max(1, beforeLength));
                }
                return super.deleteSurroundingTextInCodePoints(
                        beforeLength, afterLength);
            }

            @Override
            public boolean sendKeyEvent(KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN
                        && emitAndroidKey(event.getKeyCode())) {
                    return true;
                }
                return super.sendKeyEvent(event);
            }

            @Override
            public boolean performEditorAction(int actionCode) {
                if (listener != null) {
                    listener.onKeyPress(Protocol.KEY_ENTER);
                }
                return true;
            }
        };
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return emitAndroidKey(keyCode) || super.onKeyDown(keyCode, event);
    }

    private void emitText(CharSequence committed) {
        if (listener == null || committed == null || committed.length() == 0) {
            return;
        }
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < committed.length(); index++) {
            char value = committed.charAt(index);
            int specialKey = 0;
            if (value == '\n' || value == '\r') {
                specialKey = Protocol.KEY_ENTER;
            } else if (value == '\t') {
                specialKey = Protocol.KEY_TAB;
            }
            if (specialKey == 0) {
                text.append(value);
            } else {
                flushText(text);
                listener.onKeyPress(specialKey);
            }
        }
        flushText(text);
    }

    private void flushText(StringBuilder text) {
        if (text.length() > 0 && listener != null) {
            listener.onText(text.toString());
            text.setLength(0);
        }
    }

    private void emitRepeatedKey(int key, int count) {
        if (listener == null) {
            return;
        }
        for (int index = 0; index < Math.min(count, 32); index++) {
            listener.onKeyPress(key);
        }
    }

    private boolean emitAndroidKey(int keyCode) {
        if (listener == null) {
            return false;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DEL:
                listener.onKeyPress(Protocol.KEY_BACKSPACE);
                return true;
            case KeyEvent.KEYCODE_ENTER:
                listener.onKeyPress(Protocol.KEY_ENTER);
                return true;
            case KeyEvent.KEYCODE_TAB:
                listener.onKeyPress(Protocol.KEY_TAB);
                return true;
            case KeyEvent.KEYCODE_ESCAPE:
                listener.onKeyPress(Protocol.KEY_ESCAPE);
                return true;
            default:
                return false;
        }
    }

    private boolean hasComposingText() {
        return android.view.inputmethod.BaseInputConnection
                .getComposingSpanStart(getText()) >= 0;
    }

    private void clearLocalBuffer() {
        getText().clear();
    }
}