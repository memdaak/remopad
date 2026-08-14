package com.remopad.client;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.List;

public final class MainActivity extends Activity {
    private static final int REQUEST_BLUETOOTH = 100;
    private static final int REQUEST_BLUETOOTH_DISCOVERABLE = 101;
    private static final String PREFS_NAME = "remopad";
    private static final String PREF_HOST = "host";
    private static final String PREF_PORT = "port";
    private static final String PREF_KEY = "pairing_key_v2";
    private static final String PREF_POINTER = "pointer_sensitivity";
    private static final String PREF_SCROLL = "scroll_sensitivity";
    private static final String PREF_BLUETOOTH_MODE = "bluetooth_mode";

    private static final int COLOR_BACKGROUND = Color.rgb(0, 0, 0);
    private static final int COLOR_TEXT = Color.rgb(235, 238, 243);
    private static final int COLOR_MUTED = Color.rgb(142, 151, 164);
    private static final int COLOR_CONNECTED = Color.rgb(34, 197, 94);
    private static final int COLOR_DISCONNECTED = Color.rgb(239, 68, 68);

    private SharedPreferences preferences;
    private UdpTransport udpTransport;
    private BluetoothHidTransport bluetoothTransport;
    private TrackpadView trackpadView;
    private KeyboardInputView keyboardInput;
    private EditText hostInput;
    private EditText portInput;
    private EditText keyInput;
    private TextView statusText;
    private View statusDot;
    private Button connectionButton;
    private Button modeButton;
    private Button orientationButton;
    private Button discoverButton;
    private SeekBar pointerSeek;
    private SeekBar scrollSeek;
    private LinearLayout settingsPanel;
    private LinearLayout wifiSettings;
    private boolean bluetoothMode;
    private boolean bluetoothPairingPending;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(COLOR_BACKGROUND);
        getWindow().setNavigationBarColor(COLOR_BACKGROUND);
        applyImmersiveMode();

        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        bluetoothMode = preferences.getBoolean(PREF_BLUETOOTH_MODE, false);
        buildInterface();

        udpTransport = new UdpTransport(this::onTransportStatusChanged);
        bluetoothTransport = new BluetoothHidTransport(
                this, this::onTransportStatusChanged);

        trackpadView.setGestureListener(new TrackpadView.GestureListener() {
            @Override public void onMove(float dx, float dy) { sendMove(dx, dy); }
            @Override public void onScroll(float dx, float dy) { sendScroll(dx, dy); }
            @Override public void onButton(int button, boolean isDown) {
                sendButton(button, isDown);
            }
            @Override public void onClick(int button) { sendClick(button); }
        });

        keyboardInput.setKeyboardListener(new KeyboardInputView.Listener() {
            @Override public void onText(String text) { sendText(text); }
            @Override public void onKeyPress(int key) { sendKeyPress(key); }
        });

        connectionButton.setOnClickListener(view -> toggleConnection());
        modeButton.setOnClickListener(view -> toggleTransportMode());
        orientationButton.setOnClickListener(view -> toggleOrientation());
        discoverButton.setOnClickListener(view -> discoverOrPair());
        configureSensitivityControls();
        updateModeInterface();
        updateOrientationButton();

        if (!bluetoothMode) {
            getWindow().getDecorView().postDelayed(this::startDiscovery, 500);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateOrientationButton();
        applyImmersiveMode();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersiveMode();
        if (bluetoothMode && bluetoothTransport != null
                && bluetoothTransport.isActive()
                && !bluetoothTransport.isConnected()) {
            bluetoothTransport.connect();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyImmersiveMode();
        }
    }

    @Override
    protected void onPause() {
        saveSettings();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (trackpadView != null) {
            trackpadView.resetGestureState();
        }
        if (udpTransport != null) {
            udpTransport.disconnect();
        }
        if (bluetoothTransport != null) {
            bluetoothTransport.disconnect();
        }
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_BLUETOOTH) {
            return;
        }
        boolean granted = grantResults.length > 0;
        for (int result : grantResults) {
            granted &= result == PackageManager.PERMISSION_GRANTED;
        }
        if (granted) {
            startBluetoothAction();
        } else {
            bluetoothPairingPending = false;
            onTransportStatusChanged("未授予附近设备权限", false, -1);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_BLUETOOTH_DISCOVERABLE) {
            return;
        }
        bluetoothPairingPending = false;
        if (resultCode > 0) {
            onTransportStatusChanged(
                    "HID 已就绪：现在从 Windows 添加蓝牙设备", false, -1);
        } else {
            onTransportStatusChanged("已取消蓝牙可发现", false, -1);
        }
    }

    private void buildInterface() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(COLOR_BACKGROUND);

        trackpadView = new TrackpadView(this);
        root.addView(trackpadView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        keyboardInput = new KeyboardInputView(this);
        FrameLayout.LayoutParams keyboardParams = new FrameLayout.LayoutParams(1, 1);
        keyboardParams.gravity = Gravity.BOTTOM | Gravity.START;
        root.addView(keyboardInput, keyboardParams);

        LinearLayout overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setPadding(dp(10), dp(6), dp(10), dp(7));
        GradientDrawable overlayBackground = new GradientDrawable();
        overlayBackground.setColor(Color.argb(238, 17, 20, 24));
        overlayBackground.setCornerRadius(dp(12));
        overlay.setBackground(overlayBackground);

        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("RemoPad");
        title.setTextSize(17f);
        title.setTextColor(COLOR_TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        headerRow.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        statusDot = new View(this);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(10), dp(10));
        dotParams.leftMargin = dp(8);
        dotParams.rightMargin = dp(5);
        headerRow.addView(statusDot, dotParams);
        updateStatusDot(false);

        statusText = new TextView(this);
        statusText.setText("未连接");
        statusText.setTextSize(11f);
        statusText.setTextColor(COLOR_DISCONNECTED);
        statusText.setSingleLine(true);
        statusText.setEllipsize(TextUtils.TruncateAt.END);
        headerRow.addView(statusText, new LinearLayout.LayoutParams(
                0, dp(36), 1f));

        connectionButton = createCompactButton("连接", 66);
        headerRow.addView(connectionButton,
                new LinearLayout.LayoutParams(dp(66), dp(36)));
        overlay.addView(headerRow);

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);

        modeButton = createCompactButton("Wi-Fi", 0);
        actionRow.addView(modeButton, weightedButtonParams());
        orientationButton = createCompactButton("横屏", 0);
        actionRow.addView(orientationButton, weightedButtonParams());
        Button keyboardButton = createCompactButton("键盘", 0);
        keyboardButton.setOnClickListener(view -> showVirtualKeyboard());
        actionRow.addView(keyboardButton, weightedButtonParams());
        discoverButton = createCompactButton("发现", 0);
        actionRow.addView(discoverButton, weightedButtonParams());
        overlay.addView(actionRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));

        settingsPanel = new LinearLayout(this);
        settingsPanel.setOrientation(LinearLayout.VERTICAL);
        wifiSettings = new LinearLayout(this);
        wifiSettings.setOrientation(LinearLayout.VERTICAL);

        LinearLayout connectionRow = new LinearLayout(this);
        connectionRow.setOrientation(LinearLayout.HORIZONTAL);
        connectionRow.setGravity(Gravity.CENTER_VERTICAL);

        hostInput = createInput("电脑 IPv4", InputType.TYPE_CLASS_TEXT);
        hostInput.setText(preferences.getString(PREF_HOST, ""));
        LinearLayout.LayoutParams hostParams = new LinearLayout.LayoutParams(0, dp(40), 1f);
        hostParams.rightMargin = dp(6);
        connectionRow.addView(hostInput, hostParams);

        portInput = createInput("端口", InputType.TYPE_CLASS_NUMBER);
        portInput.setText(preferences.getString(PREF_PORT,
                Integer.toString(Protocol.DEFAULT_PORT)));
        connectionRow.addView(portInput,
                new LinearLayout.LayoutParams(dp(82), dp(40)));
        wifiSettings.addView(connectionRow);

        keyInput = createInput("安全配对密钥（Windows 接收器显示的 64 位十六进制）",
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        keyInput.setText(preferences.getString(PREF_KEY, ""));
        wifiSettings.addView(keyInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
        settingsPanel.addView(wifiSettings);

        LinearLayout sensitivityRow = new LinearLayout(this);
        sensitivityRow.setOrientation(LinearLayout.HORIZONTAL);
        sensitivityRow.setGravity(Gravity.CENTER_VERTICAL);
        sensitivityRow.addView(createSmallLabel("指针"));
        pointerSeek = createSensitivitySeek(
                preferences.getFloat(PREF_POINTER, 1f));
        sensitivityRow.addView(pointerSeek,
                new LinearLayout.LayoutParams(0, dp(32), 1f));
        sensitivityRow.addView(createSmallLabel("滚动"));
        scrollSeek = createSensitivitySeek(
                preferences.getFloat(PREF_SCROLL, 1f));
        sensitivityRow.addView(scrollSeek,
                new LinearLayout.LayoutParams(0, dp(32), 1f));
        settingsPanel.addView(sensitivityRow);
        overlay.addView(settingsPanel);

        FrameLayout.LayoutParams overlayParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        overlayParams.leftMargin = dp(7);
        overlayParams.topMargin = dp(5);
        overlayParams.rightMargin = dp(7);
        root.addView(overlay, overlayParams);
        setContentView(root);
    }

    private Button createCompactButton(String text, int width) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(12f);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(5), 0, dp(5), 0);
        button.setBackgroundTintList(
                ColorStateList.valueOf(Color.rgb(37, 99, 235)));
        return button;
    }

    private LinearLayout.LayoutParams weightedButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(34), 1f);
        params.setMargins(dp(2), dp(2), dp(2), dp(2));
        return params;
    }

    private EditText createInput(String hint, int inputType) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setHintTextColor(Color.rgb(105, 114, 126));
        input.setTextColor(COLOR_TEXT);
        input.setTextSize(12f);
        input.setSingleLine(true);
        input.setSelectAllOnFocus(true);
        input.setInputType(inputType);
        input.setPadding(dp(9), 0, dp(9), 0);
        input.setBackgroundTintList(
                ColorStateList.valueOf(Color.rgb(65, 73, 84)));
        return input;
    }

    private TextView createSmallLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(11f);
        label.setTextColor(COLOR_MUTED);
        label.setPadding(dp(4), 0, 0, 0);
        return label;
    }

    private SeekBar createSensitivitySeek(float value) {
        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(210);
        seekBar.setProgress(Math.round((clampSensitivity(value) - 0.4f) * 100f));
        seekBar.setProgressTintList(
                ColorStateList.valueOf(Color.rgb(59, 130, 246)));
        seekBar.setThumbTintList(
                ColorStateList.valueOf(Color.rgb(147, 197, 253)));
        return seekBar;
    }

    private void configureSensitivityControls() {
        SeekBar.OnSeekBarChangeListener listener =
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress,
                                                  boolean fromUser) {
                        float sensitivity = 0.4f + progress / 100f;
                        if (seekBar == pointerSeek) {
                            trackpadView.setPointerSensitivity(sensitivity);
                        } else if (seekBar == scrollSeek) {
                            trackpadView.setScrollSensitivity(sensitivity);
                        }
                    }
                    @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                    @Override public void onStopTrackingTouch(SeekBar seekBar) {
                        saveSettings();
                    }
                };
        pointerSeek.setOnSeekBarChangeListener(listener);
        scrollSeek.setOnSeekBarChangeListener(listener);
        trackpadView.setPointerSensitivity(sensitivityFromSeek(pointerSeek));
        trackpadView.setScrollSensitivity(sensitivityFromSeek(scrollSeek));
    }

    private void toggleConnection() {
        if (activeTransportIsActive()) {
            trackpadView.resetGestureState();
            disconnectBoth();
            return;
        }
        if (bluetoothMode) {
            connectBluetooth();
        } else {
            connectWifi();
        }
    }

    private void connectWifi() {
        String host = hostInput.getText().toString().trim();
        Integer port = parseNumber(portInput.getText().toString());
        String key = keyInput.getText().toString().trim();
        if (host.isEmpty() || port == null || port < 1 || port > 65535) {
            onTransportStatusChanged("请填写或自动发现电脑", false, -1);
            return;
        }
        if (!Protocol.isValidPairingKey(key)) {
            onTransportStatusChanged("请输入 Windows 显示的安全配对密钥", false, -1);
            return;
        }
        saveSettings();
        hideKeyboard();
        udpTransport.connect(host, port, key);
    }

    private void connectBluetooth() {
        bluetoothPairingPending = false;
        beginBluetoothAction();
    }

    private void pairBluetooth() {
        bluetoothPairingPending = true;
        beginBluetoothAction();
    }

    private void beginBluetoothAction() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            boolean connectGranted = checkSelfPermission(
                    Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
            boolean advertiseGranted = checkSelfPermission(
                    Manifest.permission.BLUETOOTH_ADVERTISE)
                    == PackageManager.PERMISSION_GRANTED;
            if (!connectGranted || !advertiseGranted) {
                requestPermissions(new String[] {
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_ADVERTISE
                }, REQUEST_BLUETOOTH);
                return;
            }
        }
        startBluetoothAction();
    }

    private void startBluetoothAction() {
        if (bluetoothPairingPending) {
            bluetoothTransport.prepareForPairing(
                    this::requestBluetoothDiscoverable);
        } else {
            selectBluetoothHostAndConnect();
        }
    }

    private void selectBluetoothHostAndConnect() {
        List<BluetoothHidTransport.HostOption> hosts =
                bluetoothTransport.getBondedHosts();
        String preferred = bluetoothTransport.getPreferredHostAddress();
        for (BluetoothHidTransport.HostOption host : hosts) {
            if (host.address.equalsIgnoreCase(preferred)) {
                bluetoothTransport.connectToHost(host.address);
                return;
            }
        }
        if (hosts.size() == 1) {
            bluetoothTransport.connectToHost(hosts.get(0).address);
            return;
        }
        if (hosts.isEmpty()) {
            bluetoothTransport.connect();
            return;
        }
        String[] labels = new String[hosts.size()];
        for (int index = 0; index < hosts.size(); index++) {
            BluetoothHidTransport.HostOption host = hosts.get(index);
            labels[index] = host.name + "  ·  " + host.address;
        }
        new AlertDialog.Builder(this)
                .setTitle("选择 Windows 电脑")
                .setItems(labels, (dialog, index) ->
                        bluetoothTransport.connectToHost(
                                hosts.get(index).address))
                .setNegativeButton("取消", null)
                .show();
    }

    private void requestBluetoothDiscoverable() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
                != PackageManager.PERMISSION_GRANTED) {
            bluetoothPairingPending = false;
            onTransportStatusChanged("需要附近设备权限", false, -1);
            return;
        }
        try {
            Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            intent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
            startActivityForResult(intent, REQUEST_BLUETOOTH_DISCOVERABLE);
        } catch (Exception error) {
            bluetoothPairingPending = false;
            onTransportStatusChanged("无法开启蓝牙可发现模式", false, -1);
        }
    }

    private void toggleTransportMode() {
        trackpadView.resetGestureState();
        disconnectBoth();
        bluetoothMode = !bluetoothMode;
        saveSettings();
        updateModeInterface();
        if (!bluetoothMode) {
            startDiscovery();
        }
    }

    private void updateModeInterface() {
        modeButton.setText(bluetoothMode ? "蓝牙" : "Wi-Fi");
        discoverButton.setText(bluetoothMode ? "HID 配对" : "发现");
        wifiSettings.setVisibility(bluetoothMode ? View.GONE : View.VISIBLE);
    }

    private void discoverOrPair() {
        if (bluetoothMode) {
            pairBluetooth();
        } else {
            startDiscovery();
        }
    }

    private void startDiscovery() {
        if (udpTransport == null || bluetoothMode || activeTransportIsActive()) {
            return;
        }
        onTransportStatusChanged("正在自动发现电脑…", false, -1);
        udpTransport.discover(Protocol.DEFAULT_PORT,
                new UdpTransport.DiscoveryListener() {
                    @Override
                    public void onReceiverFound(String host, int port, String name) {
                        hostInput.setText(host);
                        portInput.setText(Integer.toString(port));
                        saveSettings();
                        onTransportStatusChanged("已发现 " + name, false, -1);
                    }

                    @Override
                    public void onDiscoveryFinished(boolean found) {
                        if (!found && !activeTransportIsActive()) {
                            onTransportStatusChanged("未发现电脑，可手动填写 IP",
                                    false, -1);
                        }
                    }
                });
    }

    private void toggleOrientation() {
        boolean portrait = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_PORTRAIT;
        setRequestedOrientation(portrait
                ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    }

    private void updateOrientationButton() {
        if (orientationButton == null) {
            return;
        }
        boolean portrait = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_PORTRAIT;
        orientationButton.setText(portrait ? "横屏" : "竖屏");
    }

    private void showVirtualKeyboard() {
        keyboardInput.setVisibility(View.VISIBLE);
        keyboardInput.requestFocus();
        keyboardInput.postDelayed(() -> {
            InputMethodManager manager = (InputMethodManager)
                    getSystemService(Context.INPUT_METHOD_SERVICE);
            if (manager != null) {
                manager.showSoftInput(keyboardInput,
                        InputMethodManager.SHOW_IMPLICIT);
            }
        }, 80);
    }

    private void onTransportStatusChanged(String message, boolean connected,
                                          int latencyMs) {
        statusText.setText(latencyMs >= 0
                ? message + " · " + latencyMs + " ms" : message);
        statusText.setTextColor(connected
                ? COLOR_CONNECTED : COLOR_DISCONNECTED);
        updateStatusDot(connected);
        connectionButton.setText(activeTransportIsActive() ? "断开" : "连接");
        settingsPanel.setVisibility(connected ? View.GONE : View.VISIBLE);
    }

    private boolean activeTransportIsActive() {
        if (bluetoothMode) {
            return bluetoothTransport != null && bluetoothTransport.isActive();
        }
        return udpTransport != null && udpTransport.isActive();
    }

    private void disconnectBoth() {
        if (udpTransport != null) {
            udpTransport.disconnect();
        }
        if (bluetoothTransport != null) {
            bluetoothTransport.disconnect();
        }
    }

    private void sendMove(float dx, float dy) {
        if (bluetoothMode) bluetoothTransport.sendMove(dx, dy);
        else udpTransport.sendMove(dx, dy);
    }

    private void sendScroll(float dx, float dy) {
        if (bluetoothMode) bluetoothTransport.sendScroll(dx, dy);
        else udpTransport.sendScroll(dx, dy);
    }

    private void sendButton(int button, boolean isDown) {
        if (bluetoothMode) bluetoothTransport.sendButton(button, isDown);
        else udpTransport.sendButton(button, isDown);
    }

    private void sendClick(int button) {
        if (bluetoothMode) bluetoothTransport.sendClick(button);
        else udpTransport.sendClick(button);
    }

    private void sendText(String text) {
        if (bluetoothMode) bluetoothTransport.sendText(text);
        else udpTransport.sendText(text);
    }

    private void sendKeyPress(int key) {
        if (bluetoothMode) bluetoothTransport.sendKeyPress(key);
        else udpTransport.sendKeyPress(key);
    }

    private void updateStatusDot(boolean connected) {
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(connected ? COLOR_CONNECTED : COLOR_DISCONNECTED);
        statusDot.setBackground(dot);
    }

    private void applyImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void saveSettings() {
        if (preferences == null || hostInput == null) {
            return;
        }
        preferences.edit()
                .putString(PREF_HOST, hostInput.getText().toString().trim())
                .putString(PREF_PORT, portInput.getText().toString().trim())
                .putString(PREF_KEY, keyInput.getText().toString().trim())
                .putFloat(PREF_POINTER, sensitivityFromSeek(pointerSeek))
                .putFloat(PREF_SCROLL, sensitivityFromSeek(scrollSeek))
                .putBoolean(PREF_BLUETOOTH_MODE, bluetoothMode)
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
        InputMethodManager manager = (InputMethodManager)
                getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null) {
            manager.hideSoftInputFromWindow(focused.getWindowToken(), 0);
        }
        focused.clearFocus();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
