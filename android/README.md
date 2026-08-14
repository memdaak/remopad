# RemoPad Android 0.4

轻量原生 Android 客户端：纯 Java、系统 View，无 Kotlin、Compose、AndroidX 或第三方库。

## 设备要求

- Wi-Fi 模式：Android 6.0（API 23）或更高版本
- Bluetooth HID：Android 9（API 28）或更高版本，并取决于厂商系统支持
- 构建：Android Studio、JDK 17 或更高版本、Android SDK 37

## 构建

用 Android Studio 打开本目录，连接已启用 USB 调试的设备并运行 `app`。也可以执行：

```powershell
.\gradlew.bat :app:assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`。

## Wi-Fi 联调

先在仓库根目录启动：

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\windows\RemoPadReceiver.ps1
```

应用会广播发现请求并自动填写电脑 IP。首次连接还需录入接收器显示的 64 位十六进制安全配对密钥。每次连接通过 P-256 临时 ECDH 和长期配对密钥建立独立会话密钥，随后使用 AES-256-CBC 与 HMAC-SHA256 保护输入。移动和滚动使用低延迟 UDP，其中相对增量会在发送线程繁忙时累积而不是覆盖；点击、按键和文本使用 ACK、去重和重传。心跳用于自动重连及延迟统计，重连会建立新的临时会话。

0.4 客户端的 Wi-Fi 模式只兼容 0.4 接收器。Bluetooth HID 模式不使用该网络协议。

## Bluetooth HID

如果手机以前已作为普通设备与 Windows 配对，请先在两端删除旧配对。选择“蓝牙”模式并点击“HID 配对”，允许 300 秒可发现，然后保持 RemoPad 在前台并从 Windows“添加设备 > 蓝牙”选择手机。应用会在进入可发现状态前注册鼠标/键盘 HID 服务。配对完成后点击“连接”；如果保存了多台电脑，选择当前 Windows 主机，应用会记住该选择。该模式直接使用 Android `BluetoothHidDevice`，Windows 不需要运行 RemoPad 接收器。

不要在 HID 配对过程中切到 Android 系统蓝牙设置：Android 会在应用离开前台后自动注销 HID 应用，Windows 随后只能发现普通手机类别。

蓝牙鼠标支持移动、左右键、垂直滚动和水平平移。蓝牙键盘当前映射常用英文字符；需要中文等 Unicode 文本时使用 Wi-Fi 模式。

## 手势与按钮

- 一指移动、轻点左键
- 双指滚动、轻点右键；快速拖动会获得有限加速度
- 三指拖动
- “横屏/竖屏”切换方向，启动默认竖屏
- “键盘”调出系统软键盘，平时保持隐藏
