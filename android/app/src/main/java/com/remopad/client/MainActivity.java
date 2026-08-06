package com.remopad.client;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;

public final class MainActivity extends Activity {
    private static final String PREFS_NAME = "remopad";
    private static final String PREF_HOST = "host";
    private static final String PREF_PORT = "port";
    private static final String PREF_CODE = "pairing_code";
    private static final String PREF_POINTER = "pointer_sensitivity";
    private static final String PREF_SCROLL = "scroll_sensitivity";

    private SharedPreferences preferences;
    private UdpTransport transport;
    private TrackpadView trackpadView;
    private EditText hostInput;
    private EditText portInput;
    private EditText codeInput;
    private TextView statusText;
    private Button connectionButton;
    private SeekBar pointerSeek;
    private SeekBar scrollSeek;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        buildInterface();

        transport = new UdpTransport((message, connected) -> {
            statusText.setText(message);
            statusText.setTextColor(connected
                    ? Color.rgb(22, 130, 74)
                    : Color.rgb(98, 105, 116));
            connectionButton.setText(transport.isActive() ? "断开" : "连接");
        });

        trackpadView.setGestureListener(new TrackpadView.GestureListener() {
            @Override
            public void onMove(float dx, float dy) {
                transport.sendMove(dx, dy);
            }

            @Override
            public void onScroll(float dx, float dy) {
                transport.sendScroll(dx, dy);
            }

            @Override
            public void onButton(int button, boolean isDown) {
                transport.sendButton(button, isDown);
            }

            @Override
            public void onClick(int button) {
                transport.sendClick(button);
            }
        });

        connectionButton.setOnClickListener(view -> toggleConnection());
        configureSensitivityControls();
    }

    @Override
    protected void onPause() {
        saveSettings();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (transport != null) {
            transport.disconnect();
        }
        super.onDestroy();
    }

    private void buildInterface() {
        int outerPadding = dp(12);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(outerPadding, dp(8), outerPadding, outerPadding);
        root.setBackgroundColor(Color.rgb(241, 243, 245));

        TextView title = new TextView(this);
        title.setText("RemoPad");
        title.setTextSize(24f);
        title.setTextColor(Color.rgb(27, 31, 38));
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        statusText = new TextView(this);
        statusText.setText("未连接");
        statusText.setTextSize(12f);
        statusText.setTextColor(Color.rgb(98, 105, 116));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.bottomMargin = dp(6);
        root.addView(statusText, statusParams);

        LinearLayout connectionRow = new LinearLayout(this);
        connectionRow.setOrientation(LinearLayout.HORIZONTAL);
        connectionRow.setGravity(Gravity.CENTER_VERTICAL);

        hostInput = createInput("电脑 IPv4", InputType.TYPE_CLASS_TEXT);
        hostInput.setText(preferences.getString(PREF_HOST, "192.168.1.100"));
        LinearLayout.LayoutParams hostParams = new LinearLayout.LayoutParams(
                0, dp(48), 1f);
        hostParams.rightMargin = dp(6);
        connectionRow.addView(hostInput, hostParams);

        portInput = createInput("端口", InputType.TYPE_CLASS_NUMBER);
        portInput.setText(preferences.getString(PREF_PORT,
                Integer.toString(Protocol.DEFAULT_PORT)));
        LinearLayout.LayoutParams portParams = new LinearLayout.LayoutParams(dp(82), dp(48));
        portParams.rightMargin = dp(6);
        connectionRow.addView(portInput, portParams);

        codeInput = createInput("配对码", InputType.TYPE_CLASS_NUMBER);
        codeInput.setText(preferences.getString(PREF_CODE,
                Integer.toString(Protocol.DEFAULT_PAIRING_CODE)));
        LinearLayout.LayoutParams codeParams = new LinearLayout.LayoutParams(dp(105), dp(48));
        codeParams.rightMargin = dp(6);
        connectionRow.addView(codeInput, codeParams);

        connectionButton = new Button(this);
        connectionButton.setText("连接");
        connectionButton.setAllCaps(false);
        connectionRow.addView(connectionButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));

        root.addView(connectionRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout sensitivityRow = new LinearLayout(this);
        sensitivityRow.setOrientation(LinearLayout.HORIZONTAL);
        sensitivityRow.setGravity(Gravity.CENTER_VERTICAL);

        sensitivityRow.addView(createSmallLabel("指针"));
        pointerSeek = createSensitivitySeek(
                preferences.getFloat(PREF_POINTER, 1f));
        sensitivityRow.addView(pointerSeek,
                new LinearLayout.LayoutParams(0, dp(40), 1f));

        sensitivityRow.addView(createSmallLabel("滚动"));
        scrollSeek = createSensitivitySeek(
                preferences.getFloat(PREF_SCROLL, 1f));
        sensitivityRow.addView(scrollSeek,
                new LinearLayout.LayoutParams(0, dp(40), 1f));

        root.addView(sensitivityRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        trackpadView = new TrackpadView(this);
        LinearLayout.LayoutParams trackpadParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        trackpadParams.topMargin = dp(6);
        root.addView(trackpadView, trackpadParams);

        setContentView(root);
    }

    private EditText createInput(String hint, int inputType) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setTextSize(14f);
        input.setSingleLine(true);
        input.setSelectAllOnFocus(true);
        input.setInputType(inputType);
        input.setPadding(dp(10), 0, dp(10), 0);
        return input;
    }

    private TextView createSmallLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(12f);
        label.setTextColor(Color.rgb(73, 79, 89));
        label.setPadding(dp(4), 0, 0, 0);
        return label;
    }

    private SeekBar createSensitivitySeek(float value) {
        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(210);
        seekBar.setProgress(Math.round((clampSensitivity(value) - 0.4f) * 100f));
        return seekBar;
    }

    private void configureSensitivityControls() {
        SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float sensitivity = 0.4f + progress / 100f;
                if (seekBar == pointerSeek) {
                    trackpadView.setPointerSensitivity(sensitivity);
                } else if (seekBar == scrollSeek) {
                    trackpadView.setScrollSensitivity(sensitivity);
                }
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) { saveSettings(); }
        };

        pointerSeek.setOnSeekBarChangeListener(listener);
        scrollSeek.setOnSeekBarChangeListener(listener);
        trackpadView.setPointerSensitivity(sensitivityFromSeek(pointerSeek));
        trackpadView.setScrollSensitivity(sensitivityFromSeek(scrollSeek));
    }

    private void toggleConnection() {
        if (transport.isActive()) {
            transport.disconnect();
            return;
        }

        String host = hostInput.getText().toString().trim();
        Integer port = parseNumber(portInput.getText().toString());
        Integer code = parseNumber(codeInput.getText().toString());

        if (host.isEmpty() || port == null || port < 1 || port > 65535
                || code == null || code < 0 || code > 999_999) {
            statusText.setText("请检查 IP、端口和六位配对码");
            statusText.setTextColor(Color.rgb(185, 56, 44));
            return;
        }

        saveSettings();
        hideKeyboard();
        transport.connect(host, port, code);
    }

    private void saveSettings() {
        if (preferences == null || hostInput == null) {
            return;
        }
        preferences.edit()
                .putString(PREF_HOST, hostInput.getText().toString().trim())
                .putString(PREF_PORT, portInput.getText().toString().trim())
                .putString(PREF_CODE, codeInput.getText().toString().trim())
                .putFloat(PREF_POINTER, sensitivityFromSeek(pointerSeek))
                .putFloat(PREF_SCROLL, sensitivityFromSeek(scrollSeek))
                .apply();
    }

    private float sensitivityFromSeek(SeekBar seekBar) {
        return 0.4f + seekBar.getProgress() / 100f;
    }

    private static float clampSensitivity(float value) {
        return Math.max(0.4f, Math.min(2.5f, value));
    }

    private static Integer parseNumber(String value) {
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void hideKeyboard() {
        View focused = getCurrentFocus();
        if (focused == null) {
            return;
        }
        InputMethodManager manager =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null) {
            manager.hideSoftInputFromWindow(focused.getWindowToken(), 0);
        }
        focused.clearFocus();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
