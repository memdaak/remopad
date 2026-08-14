package com.remopad.client;

import android.annotation.TargetApi;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@TargetApi(Build.VERSION_CODES.P)
final class BluetoothHidTransport {
    private static final String TAG = "RemoPadHid";
    private static final String PREFS_NAME = "remopad";
    private static final String PREF_HOST_ADDRESS = "bluetooth_host_address";

    interface Listener {
        void onStatusChanged(String message, boolean connected, int latencyMs);
    }

    static final class HostOption {
        final String address;
        final String name;

        HostOption(String address, String name) {
            this.address = address;
            this.name = name;
        }
    }

    private static final int REPORT_MOUSE = 1;
    private static final int REPORT_KEYBOARD = 2;

    private static final byte[] REPORT_DESCRIPTOR = new byte[] {
            0x05, 0x01, 0x09, 0x02, (byte) 0xA1, 0x01, (byte) 0x85, 0x01,
            0x09, 0x01, (byte) 0xA1, 0x00,
            0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01,
            (byte) 0x95, 0x03, 0x75, 0x01, (byte) 0x81, 0x02,
            (byte) 0x95, 0x01, 0x75, 0x05, (byte) 0x81, 0x01,
            0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38,
            0x15, (byte) 0x81, 0x25, 0x7F, 0x75, 0x08, (byte) 0x95, 0x03,
            (byte) 0x81, 0x06,
            0x05, 0x0C, 0x0A, 0x38, 0x02, 0x15, (byte) 0x81, 0x25, 0x7F,
            0x75, 0x08, (byte) 0x95, 0x01, (byte) 0x81, 0x06,
            (byte) 0xC0, (byte) 0xC0,

            0x05, 0x01, 0x09, 0x06, (byte) 0xA1, 0x01, (byte) 0x85, 0x02,
            0x05, 0x07, 0x19, (byte) 0xE0, 0x29, (byte) 0xE7,
            0x15, 0x00, 0x25, 0x01, 0x75, 0x01, (byte) 0x95, 0x08,
            (byte) 0x81, 0x02,
            (byte) 0x95, 0x01, 0x75, 0x08, (byte) 0x81, 0x01,
            0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x15, 0x00, 0x25, 0x65,
            0x75, 0x08, (byte) 0x95, 0x06, (byte) 0x81, 0x00,
            (byte) 0xC0
    };

    private final Context context;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Executor callbackExecutor;
    private final ExecutorService keyboardExecutor =
            Executors.newSingleThreadExecutor();

    private BluetoothAdapter adapter;
    private BluetoothHidDevice hidDevice;
    private BluetoothDevice hostDevice;
    private boolean reconnectEnabled;
    private boolean registered;
    private boolean connected;
    private int mouseButtons;
    private int reconnectAttempts;
    private Runnable registeredAction;
    private float moveResidualX;
    private float moveResidualY;
    private float scrollResidualX;
    private float scrollResidualY;

    BluetoothHidTransport(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        callbackExecutor = command -> mainHandler.post(command);
    }

    boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P;
    }

    boolean isActive() {
        return reconnectEnabled;
    }

    boolean isConnected() {
        return connected;
    }

    boolean isRegistered() {
        return registered;
    }

    List<HostOption> getBondedHosts() {
        BluetoothAdapter currentAdapter = getAdapter();
        List<HostOption> hosts = new ArrayList<>();
        if (currentAdapter == null) {
            return hosts;
        }
        try {
            for (BluetoothDevice device : currentAdapter.getBondedDevices()) {
                BluetoothClass deviceClass = device.getBluetoothClass();
                if (deviceClass != null && deviceClass.getMajorDeviceClass()
                        == BluetoothClass.Device.Major.COMPUTER) {
                    hosts.add(new HostOption(device.getAddress(),
                            safeDeviceName(device)));
                }
            }
        } catch (SecurityException ignored) {}
        Collections.sort(hosts, new Comparator<HostOption>() {
            @Override
            public int compare(HostOption left, HostOption right) {
                return left.name.compareToIgnoreCase(right.name);
            }
        });
        return hosts;
    }

    String getPreferredHostAddress() {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_HOST_ADDRESS, "");
    }

    void connectToHost(String address) {
        BluetoothAdapter currentAdapter = getAdapter();
        if (currentAdapter == null || address == null || address.isEmpty()) {
            postStatus("未找到蓝牙适配器", false);
            return;
        }
        try {
            hostDevice = currentAdapter.getRemoteDevice(address);
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putString(PREF_HOST_ADDRESS, address).apply();
            reconnectAttempts = 0;
            Log.i(TAG, "Selected HID host: " + safeDeviceName(hostDevice)
                    + " / " + address);
            connect();
        } catch (IllegalArgumentException | SecurityException error) {
            postStatus("选择的蓝牙电脑无效", false);
        }
    }

    void prepareForPairing(Runnable onRegistered) {
        registeredAction = onRegistered;
        connect();
        if (registered && hidDevice != null) {
            runRegisteredAction();
        }
    }

    void connect() {
        if (!isSupported()) {
            postStatus("蓝牙 HID 需要 Android 9 或更高版本", false);
            return;
        }
        BluetoothManager manager =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            postStatus("请先打开手机蓝牙", false);
            return;
        }

        reconnectEnabled = true;
        connected = false;
        postStatus("正在注册蓝牙鼠标/键盘…", false);
        if (hidDevice != null) {
            if (registered) {
                connectBondedHost();
            } else {
                registerHidApp();
            }
            return;
        }
        try {
            if (!adapter.getProfileProxy(context, profileListener,
                    BluetoothProfile.HID_DEVICE)) {
                reconnectEnabled = false;
                postStatus("手机系统不支持 Bluetooth HID", false);
            }
        } catch (SecurityException error) {
            reconnectEnabled = false;
            postStatus("需要附近设备权限", false);
        }
    }

    void disconnect() {
        reconnectEnabled = false;
        connected = false;
        reconnectAttempts = 0;
        registered = false;
        registeredAction = null;
        mouseButtons = 0;
        try {
            if (hidDevice != null && hostDevice != null) {
                hidDevice.sendReport(hostDevice, REPORT_MOUSE,
                        new byte[] {0, 0, 0, 0, 0});
                hidDevice.disconnect(hostDevice);
            }
            if (hidDevice != null) {
                hidDevice.unregisterApp();
            }
            if (adapter != null && hidDevice != null) {
                adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice);
            }
        } catch (SecurityException ignored) {
            // Permission may have been revoked while connected.
        }
        hidDevice = null;
        hostDevice = null;
        postStatus("未连接", false);
    }

    void sendMove(float dx, float dy) {
        if (!connected) {
            return;
        }
        moveResidualX += dx;
        moveResidualY += dy;
        int moveX = (int) moveResidualX;
        int moveY = (int) moveResidualY;
        moveResidualX -= moveX;
        moveResidualY -= moveY;
        while (moveX != 0 || moveY != 0) {
            int chunkX = clampReport(moveX);
            int chunkY = clampReport(moveY);
            sendMouseReport(chunkX, chunkY, 0, 0);
            moveX -= chunkX;
            moveY -= chunkY;
        }
    }

    void sendScroll(float dx, float dy) {
        if (!connected) {
            return;
        }
        scrollResidualX += dx;
        scrollResidualY += -dy;
        int horizontal = (int) scrollResidualX;
        int vertical = (int) scrollResidualY;
        scrollResidualX -= horizontal;
        scrollResidualY -= vertical;
        while (horizontal != 0 || vertical != 0) {
            int pan = clampReport(horizontal);
            int wheel = clampReport(vertical);
            sendMouseReport(0, 0, wheel, pan);
            horizontal -= pan;
            vertical -= wheel;
        }
    }

    void sendButton(int button, boolean isDown) {
        if (!connected) {
            return;
        }
        int mask = button == 1 ? 1 : button == 2 ? 2 : 0;
        if (mask == 0) {
            return;
        }
        if (isDown) {
            mouseButtons |= mask;
        } else {
            mouseButtons &= ~mask;
        }
        sendMouseReport(0, 0, 0, 0);
    }

    void sendClick(int button) {
        sendButton(button, true);
        sendButton(button, false);
    }

    void sendText(String text) {
        if (!connected || text == null || text.isEmpty()) {
            return;
        }
        keyboardExecutor.execute(() -> {
            boolean unsupported = false;
            for (int index = 0; index < text.length(); index++) {
                KeyStroke stroke = KeyStroke.fromCharacter(text.charAt(index));
                if (stroke == null) {
                    unsupported = true;
                    continue;
                }
                sendKeyboardStroke(stroke.modifier, stroke.usage);
            }
            if (unsupported) {
                postStatus("蓝牙键盘暂只支持常用英文字符", true);
            }
        });
    }

    void sendKeyPress(int key) {
        if (!connected) {
            return;
        }
        final int usage;
        switch (key) {
            case Protocol.KEY_BACKSPACE: usage = 42; break;
            case Protocol.KEY_ENTER: usage = 40; break;
            case Protocol.KEY_TAB: usage = 43; break;
            case Protocol.KEY_ESCAPE: usage = 41; break;
            default: return;
        }
        keyboardExecutor.execute(() -> sendKeyboardStroke(0, usage));
    }

    private final BluetoothProfile.ServiceListener profileListener =
            new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    if (!reconnectEnabled || profile != BluetoothProfile.HID_DEVICE) {
                        return;
                    }
                    hidDevice = (BluetoothHidDevice) proxy;
                    Log.i(TAG, "HID_DEVICE profile proxy connected");
                    registerHidApp();
                }

                @Override
                public void onServiceDisconnected(int profile) {
                    Log.w(TAG, "HID_DEVICE profile proxy disconnected");
                    hidDevice = null;
                    registered = false;
                    connected = false;
                    if (reconnectEnabled) {
                        postStatus("蓝牙服务断开，正在恢复…", false);
                        mainHandler.postDelayed(BluetoothHidTransport.this::connect,
                                1200);
                    }
                }
            };

    private final BluetoothHidDevice.Callback hidCallback =
            new BluetoothHidDevice.Callback() {
                @Override
                public void onAppStatusChanged(BluetoothDevice pluggedDevice,
                                               boolean registered) {
                    Log.i(TAG, "HID app registration changed: " + registered
                            + ", pluggedHost=" + safeDeviceName(pluggedDevice));
                    BluetoothHidTransport.this.registered = registered;
                    if (!registered || !reconnectEnabled) {
                        connected = false;
                        return;
                    }
                    hostDevice = pluggedDevice;
                    postStatus("蓝牙 HID 已注册，可作为鼠标和键盘", false);
                    runRegisteredAction();
                    connectBondedHost();
                }

                @Override
                public void onConnectionStateChanged(BluetoothDevice device,
                                                     int state) {
                    Log.i(TAG, "HID host state=" + state + ", device="
                            + safeDeviceName(device));
                    if (state == BluetoothProfile.STATE_CONNECTED) {
                        hostDevice = device;
                        connected = true;
                        reconnectAttempts = 0;
                        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                .edit().putString(PREF_HOST_ADDRESS,
                                        device.getAddress()).apply();
                        mouseButtons = 0;
                        postStatus("蓝牙已连接 " + safeDeviceName(device), true);
                    } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                        connected = false;
                        if (reconnectEnabled && hidDevice != null
                                && hostDevice != null) {
                            reconnectAttempts++;
                            long delay = Math.min(30_000L,
                                    1500L << Math.min(reconnectAttempts - 1, 4));
                            postStatus("蓝牙连接失败，" + (delay / 1000)
                                    + " 秒后重试…", false);
                            mainHandler.postDelayed(() -> {
                                try {
                                    if (reconnectEnabled && hidDevice != null) {
                                        hidDevice.connect(hostDevice);
                                    }
                                } catch (SecurityException ignored) {}
                            }, delay);
                        }
                    }
                }
            };

    private void registerHidApp() {
        BluetoothHidDevice profile = hidDevice;
        if (!reconnectEnabled || profile == null) {
            return;
        }
        if (registered) {
            runRegisteredAction();
            connectBondedHost();
            return;
        }
        BluetoothHidDeviceAppSdpSettings settings =
                new BluetoothHidDeviceAppSdpSettings(
                        "RemoPad", "Remote trackpad and keyboard",
                        "RemoPad", BluetoothHidDevice.SUBCLASS1_COMBO,
                        REPORT_DESCRIPTOR);
        try {
            if (!profile.registerApp(settings, null, null,
                    callbackExecutor, hidCallback)) {
                postStatus("Bluetooth HID 注册失败", false);
                Log.e(TAG, "registerApp command was rejected");
            } else {
                Log.i(TAG, "registerApp command accepted; awaiting callback");
            }
        } catch (SecurityException error) {
            postStatus("需要附近设备权限", false);
            Log.e(TAG, "registerApp missing permission", error);
        }
    }

    private void runRegisteredAction() {
        Runnable action = registeredAction;
        registeredAction = null;
        if (action != null) {
            mainHandler.post(action);
        }
    }

    private void connectBondedHost() {
        if (!reconnectEnabled || !registered || hidDevice == null || connected) {
            return;
        }
        if (hostDevice == null) {
            hostDevice = findBondedComputer();
        }
        if (hostDevice == null) {
            if (getBondedHosts().size() > 1) {
                postStatus("检测到多台电脑，请点连接并选择目标", false);
            } else {
                postStatus("请先与 Windows 完成 HID 配对", false);
                mainHandler.postDelayed(this::connectBondedHost, 2000);
            }
            return;
        }
        postStatus("正在连接蓝牙电脑…", false);
        try {
            int state = hidDevice.getConnectionState(hostDevice);
            if (state == BluetoothProfile.STATE_CONNECTED) {
                connected = true;
                postStatus("蓝牙已连接 " + safeDeviceName(hostDevice), true);
            } else if (state != BluetoothProfile.STATE_CONNECTING
                    && !hidDevice.connect(hostDevice)) {
                postStatus("电脑拒绝 HID 连接，请删除旧配对后重新配对", false);
                Log.w(TAG, "connect command rejected for "
                        + safeDeviceName(hostDevice));
            }
        } catch (SecurityException error) {
            postStatus("需要附近设备权限", false);
        }
    }

    private BluetoothDevice findBondedComputer() {
        try {
            Set<BluetoothDevice> devices = adapter.getBondedDevices();
            String preferredAddress = getPreferredHostAddress();
            BluetoothDevice onlyComputer = null;
            int computerCount = 0;
            for (BluetoothDevice device : devices) {
                BluetoothClass deviceClass = device.getBluetoothClass();
                if (deviceClass != null && deviceClass.getMajorDeviceClass()
                        == BluetoothClass.Device.Major.COMPUTER) {
                    if (device.getAddress().equalsIgnoreCase(preferredAddress)) {
                        return device;
                    }
                    onlyComputer = device;
                    computerCount++;
                }
            }
            if (computerCount == 1) {
                return onlyComputer;
            }
            if (devices.size() == 1) {
                return devices.iterator().next();
            }
        } catch (SecurityException ignored) {}
        return null;
    }

    private BluetoothAdapter getAdapter() {
        if (adapter != null) {
            return adapter;
        }
        BluetoothManager manager =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
        return adapter;
    }

    private void sendMouseReport(int dx, int dy, int wheel, int pan) {
        BluetoothHidDevice profile = hidDevice;
        BluetoothDevice device = hostDevice;
        if (!connected || profile == null || device == null) {
            return;
        }
        byte[] report = new byte[] {
                (byte) mouseButtons,
                (byte) dx,
                (byte) dy,
                (byte) wheel,
                (byte) pan
        };
        try {
            if (!profile.sendReport(device, REPORT_MOUSE, report)) {
                postStatus("蓝牙鼠标报告发送失败", true);
            }
        } catch (SecurityException error) {
            postStatus("附近设备权限已被撤销", false);
        }
    }

    private void sendKeyboardStroke(int modifier, int usage) {
        BluetoothHidDevice profile = hidDevice;
        BluetoothDevice device = hostDevice;
        if (!connected || profile == null || device == null) {
            return;
        }
        try {
            profile.sendReport(device, REPORT_KEYBOARD, new byte[] {
                    (byte) modifier, 0, (byte) usage, 0, 0, 0, 0, 0
            });
            profile.sendReport(device, REPORT_KEYBOARD,
                    new byte[] {0, 0, 0, 0, 0, 0, 0, 0});
        } catch (SecurityException error) {
            postStatus("附近设备权限已被撤销", false);
        }
    }

    private String safeDeviceName(BluetoothDevice device) {
        if (device == null) {
            return "无";
        }
        try {
            String name = device.getName();
            return name == null || name.trim().isEmpty() ? "Windows" : name;
        } catch (SecurityException ignored) {
            return "Windows";
        }
    }

    private void postStatus(String message, boolean isConnected) {
        Log.i(TAG, message + " connected=" + isConnected);
        mainHandler.post(() -> listener.onStatusChanged(message, isConnected, -1));
    }

    private static int clampReport(int value) {
        return Math.max(-127, Math.min(127, value));
    }

    private static final class KeyStroke {
        final int modifier;
        final int usage;

        KeyStroke(int modifier, int usage) {
            this.modifier = modifier;
            this.usage = usage;
        }

        static KeyStroke fromCharacter(char value) {
            if (value >= 'a' && value <= 'z') {
                return new KeyStroke(0, 4 + value - 'a');
            }
            if (value >= 'A' && value <= 'Z') {
                return new KeyStroke(2, 4 + value - 'A');
            }
            if (value >= '1' && value <= '9') {
                return new KeyStroke(0, 30 + value - '1');
            }
            if (value == '0') {
                return new KeyStroke(0, 39);
            }
            switch (value) {
                case ' ': return new KeyStroke(0, 44);
                case '-': return new KeyStroke(0, 45);
                case '_': return new KeyStroke(2, 45);
                case '=': return new KeyStroke(0, 46);
                case '+': return new KeyStroke(2, 46);
                case '[': return new KeyStroke(0, 47);
                case '{': return new KeyStroke(2, 47);
                case ']': return new KeyStroke(0, 48);
                case '}': return new KeyStroke(2, 48);
                case '\\': return new KeyStroke(0, 49);
                case '|': return new KeyStroke(2, 49);
                case ';': return new KeyStroke(0, 51);
                case ':': return new KeyStroke(2, 51);
                case '\'': return new KeyStroke(0, 52);
                case '"': return new KeyStroke(2, 52);
                case '`': return new KeyStroke(0, 53);
                case '~': return new KeyStroke(2, 53);
                case ',': return new KeyStroke(0, 54);
                case '<': return new KeyStroke(2, 54);
                case '.': return new KeyStroke(0, 55);
                case '>': return new KeyStroke(2, 55);
                case '/': return new KeyStroke(0, 56);
                case '?': return new KeyStroke(2, 56);
                case '!': return new KeyStroke(2, 30);
                case '@': return new KeyStroke(2, 31);
                case '#': return new KeyStroke(2, 32);
                case '$': return new KeyStroke(2, 33);
                case '%': return new KeyStroke(2, 34);
                case '^': return new KeyStroke(2, 35);
                case '&': return new KeyStroke(2, 36);
                case '*': return new KeyStroke(2, 37);
                case '(': return new KeyStroke(2, 38);
                case ')': return new KeyStroke(2, 39);
                default: return null;
            }
        }
    }
}
