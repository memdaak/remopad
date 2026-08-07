# RemoPad

RemoPad 把 Android 手机变成 Windows 的轻量触摸板和键盘，只传输输入事件，不传输屏幕画面。Android 0.3.2 同时支持加密 Wi-Fi 和实验性 Bluetooth HID。

## 项目状态

| 组件 | 状态 |
| --- | --- |
| Android 客户端 | 0.3.2：已构建，等待完整真机体验回归 |
| Windows 接收端 | 0.3：加密协议、自检及 Android/Windows 互操作测试通过 |
| iPadOS 客户端 | **实验性 v1 源码：尚未编译、签名或真机验证，也尚未升级到 v2 加密协议** |

## 已实现

- 一指移动、可靠原子单击
- 双指滚动和右键
- 三指拖动
- 默认竖屏，按钮切换横屏
- 深色沉浸式界面和隐藏式虚拟键盘
- Wi-Fi 自动发现、自动重连和 RTT 延迟显示
- 可靠事件 ACK、去重与重传
- AES-256-CBC 加密和 HMAC-SHA256 认证
- Android 9+ 实验性 Bluetooth HID 鼠标/键盘
- Windows 端无需安装开发环境，使用 Windows PowerShell 5.1 运行

项目仍然只使用原生 Java、Android 系统 View 和 PowerShell，不包含第三方运行时依赖。

## 快速开始

### Windows（Wi-Fi 模式）

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\windows\RemoPadReceiver.ps1
```

首次运行会在 `%LOCALAPPDATA%\RemoPad\pairing-key.txt` 生成随机 256 位密钥，并在窗口中显示。密钥不会通过自动发现广播。

如需只做协议自检：

```powershell
.\windows\RemoPadReceiver.ps1 -SelfTest
```

### Android Wi-Fi

1. 用 Android Studio 打开 `android` 目录并运行 `app`。
2. 保持手机和 Windows 位于同一受信任局域网。
3. 启动 Windows 接收器，Android 通常会自动发现电脑。
4. 首次录入接收器显示的安全配对密钥，然后点击“连接”。
5. 右上角绿点和延迟数值出现后即可使用。

### Android 蓝牙

1. 如果 Windows 已把手机配对成普通设备，先在 Windows 和手机上删除这条旧配对。
2. Android 9 或更高版本选择“蓝牙”模式，点击“HID 配对”。
3. 允许手机在 300 秒内被发现，并让 RemoPad 保持在前台。
4. 立即在 Windows“添加设备 > 蓝牙”中选择手机。此时 HID 服务已经注册，Windows 会把它识别为鼠标和键盘。
5. 配对完成后点击“连接”；如果手机保存了多台电脑，请在弹窗中选择当前 Windows 电脑。RemoPad 会记住选择并自动重连。

Bluetooth HID 是否可用取决于手机厂商的系统实现。配对期间不能切到 Android 系统蓝牙设置，因为应用退到后台后 Android 会注销 HID 服务。Wi-Fi 键盘支持 Unicode；蓝牙键盘当前只映射常用英文字符。

## 手势

- 一指移动：鼠标移动
- 一指轻点：一次左键单击；连续轻点两次为双击
- 双指移动：水平/垂直滚动
- 双指轻点：右键
- 三指同时按下并移动：拖动

## 当前限制

- Windows Wi-Fi 接收端使用 `SendInput`，不是 Precision Touchpad。目标软件以管理员身份运行时，接收器也需要相同权限。
- 加密使用预共享随机密钥，不提供前向保密；请勿公开 `pairing-key.txt`。
- Bluetooth HID 需要 Android 9+，且应用必须保持前台；部分厂商会禁用该系统能力。
- iPadOS 工程仍停留在旧 v1 协议，当前不能连接 0.3 Windows 接收端。

协议说明见 `docs/protocol-v2.md`，旧版记录保留在 `docs/protocol-v1.md`。
