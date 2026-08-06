# RemoPad Android MVP

这是 RemoPad 的轻量原生 Android 客户端，复用根目录中的 `RMP1` UDP 协议和 Windows 接收端。

## 设备要求

- Android 6.0（API 23）或更高版本
- Windows 与 Android 位于同一局域网
- 构建需要 Android Studio、JDK 17 或更高版本，以及 Android SDK 37

项目使用纯 Java 和系统 View，不依赖 Kotlin、Compose、AndroidX 或第三方库。Release 构建启用 R8 和资源压缩。

## Android Studio 构建

1. 用 Android Studio 打开本 `android` 目录。
2. 等待 Gradle Sync 完成；安装提示的 Android SDK 36。
3. 连接开启 USB 调试的 Android 设备。
4. 选择 `app` 并点击 Run。

也可以在配置好 JDK 和 Android SDK 后运行：

```powershell
.\gradlew.bat :app:assembleDebug
```

生成的 APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 联调

先在仓库根目录启动 Windows 接收端：

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\windows\RemoPadReceiver.ps1 -PairingCode 246810
```

然后在 Android 应用中填写接收端显示的 IPv4 地址、端口 `51234` 和相同配对码。

手势：

- 一指移动：移动鼠标
- 一指轻点：左键
- 一指停留约 360ms 后移动：拖动
- 双指移动：滚动
- 双指轻点：右键

初版配对码不是加密认证，只适合受信任的家庭或办公局域网。
