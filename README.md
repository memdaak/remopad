# RemoPad MVP

RemoPad 把 Android 手机或 iPad 变成 Windows 的局域网触摸板。当前初版只传输入事件，不传屏幕画面。

## 项目状态

| 组件 | 状态 |
| --- | --- |
| Android 客户端 | 已构建并在 Android 真机上完成初步联调 |
| Windows 接收端 | 已完成自检，并与 Android 真机联调 |
| iPadOS 客户端 | **实验性源码：尚未在 Xcode 编译、签名或通过 iPad 真机验证** |

当前建议先使用 Android 客户端测试。iPadOS 工程仅作为后续开发起点，不代表已经认证或可发布的 iOS 应用。

## 已实现

- 一指移动鼠标
- 轻点左键
- 长按后拖动
- 双指轻点右键
- 双指滚动（水平和垂直）
- 16 字节固定协议头、序号过滤和六位配对码
- Windows 端无需安装开发环境，直接用 Windows PowerShell 5.1 运行

## 目录

- `ios/RemoPad.xcodeproj`：iPadOS 原生 Swift 工程
- `android/`：Android 原生 Java 工程
- `windows/RemoPadReceiver.ps1`：Windows UDP 接收和输入注入程序
- `docs/protocol-v1.md`：网络协议说明

## 快速开始

### 1. Windows

在 PowerShell 中运行：

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\windows\RemoPadReceiver.ps1 -PairingCode 246810
```

记下程序显示的本机 IPv4 地址。如果 Windows 防火墙弹出提示，只允许“专用网络”。

可以先运行不注入鼠标的自检：

```powershell
.\windows\RemoPadReceiver.ps1 -SelfTest
```

### 2. Android

1. 用 Android Studio 打开 `android` 目录。
2. 等待 Gradle Sync 完成，连接已启用 USB 调试的 Android 设备。
3. 选择 `app` 并点击 Run。
4. 输入 Windows 接收端显示的 IPv4 地址、端口 `51234` 和相同配对码。

更详细的构建说明见 `android/README.md`。

### 3. iPad（尚未验证）

> 此工程尚未在 Xcode 编译、签名或通过 iPad 真机验证，需要有 Mac 和 Apple 开发环境后继续测试。

1. 在 Mac 上用 Xcode 打开 `ios/RemoPad.xcodeproj`。
2. 在 Signing & Capabilities 中选择自己的开发团队。
3. 连接 iPad 并运行。
4. 输入 Windows 的 IPv4 地址、端口 `51234` 和相同配对码。
5. 点击“连接”，状态变成“已连接”后使用下方触控区域。

iPad 和 Windows 必须位于同一局域网。部分路由器的访客网络会隔离设备，无法互相通信。

## 当前限制

- 初版使用 UDP；按键事件尚未增加确认重传。
- 没有自动发现和加密，六位码只用于阻止局域网内的误连接，不是强安全认证。
- Windows 端当前使用 `SendInput`，不是 Windows Precision Touchpad 设备。
- 当前客户端只支持 Wi-Fi/局域网 UDP 通信；蓝牙、自动重连和延迟统计将在后续版本加入。
- iPadOS 客户端尚未完成构建、签名和真机验证。
- Windows 11 原生合成触控板将在后续版本评估；当前输出表现为普通鼠标输入。

## 调整手感

Windows 端参数：

```powershell
.\windows\RemoPadReceiver.ps1 `
  -PairingCode 246810 `
  -PointerSensitivity 1.35 `
  -WheelScale 4.0
```

鼠标仍会受到 Windows“指针速度”和“提高指针精确度”设置影响。
