# RemoPad

RemoPad 把 Android 手机变成 Windows 的轻量触摸板和键盘，只传输输入事件，不传输屏幕画面。Android 0.4.0 同时支持前向保密 Wi-Fi 和实验性 Bluetooth HID。

## 项目状态

| 组件 | 状态 |
| --- | --- |
| Android 客户端 | 0.4.0：协议 v3、滚动累积与速度自适应 |
| Windows 接收端 | 0.4：自动 UAC 提权、DPAPI 密钥存储和临时 ECDH 会话 |
| iPadOS 客户端 | **实验性 v1 源码：尚未编译、签名或真机验证，也尚未升级到 v3 协议** |

## 已实现

- 一指移动、可靠原子单击
- 双指滚动和右键
- 三指拖动
- 默认竖屏，按钮切换横屏
- 深色沉浸式界面和隐藏式虚拟键盘
- Wi-Fi 自动发现、自动重连和 RTT 延迟显示
- 可靠事件 ACK、去重与重传
- P-256 临时 ECDH、AES-256-CBC 加密和 HMAC-SHA256 认证
- Windows DPAPI 当前用户密钥保护和配对密钥轮换
- 滚动增量累积与快速手势加速度补偿
- Android 9+ 实验性 Bluetooth HID 鼠标/键盘
- Windows 端无需安装开发环境，使用 Windows PowerShell 5.1 运行

项目仍然只使用原生 Java、Android 系统 View 和 PowerShell，不包含第三方运行时依赖。

## 快速开始

### Windows（Wi-Fi 模式）

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\windows\RemoPadReceiver.ps1
```

接收器每次启动会自动请求管理员权限，Windows 将显示一次 UAC 确认。这让它能够控制以管理员身份运行的普通桌面程序；UAC 安全桌面仍由 Windows 隔离，不能远程注入。

首次运行会为当前 Windows 用户随机生成独立的 256 位配对密钥，在窗口中显示，并通过 DPAPI `CurrentUser` 加密保存到 `%LOCALAPPDATA%\RemoPad\pairing-key.dat`。密钥不会通过自动发现广播。旧版明文 `pairing-key.txt` 会自动迁移并删除。

如需只做协议自检：

```powershell
.\windows\RemoPadReceiver.ps1 -SelfTest
```

如需轮换泄露或不再信任的配对密钥：

```powershell
.\windows\RemoPadReceiver.ps1 -ResetPairingKey
```

轮换后需要在 Android 重新录入。调试时可用 `-NoElevate` 禁用自动提权。

### Android Wi-Fi

1. 用 Android Studio 打开 `android` 目录并运行 `app`。
2. 保持手机和 Windows 位于同一受信任局域网。
3. 启动 Windows 接收器并确认 UAC，Android 通常会自动发现电脑。
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

- Windows Wi-Fi 接收端仍使用 `SendInput`，不是 Precision Touchpad。接收器默认自动请求管理员权限，但 Windows 的 UAC/登录安全桌面不会接受 `SendInput`，也不会被静默绕过。
- v3 为每次连接生成临时 ECDH 密钥，长期配对密钥以后泄露不会解密过去抓取的会话；泄露仍允许建立未来连接，应使用 `-ResetPairingKey` 轮换。
- Bluetooth HID 需要 Android 9+，且应用必须保持前台；部分厂商会禁用该系统能力。
- iPadOS 工程仍停留在旧 v1 协议，当前不能连接 0.4 Windows 接收端。

版本变化见 `CHANGELOG.md`。协议说明见 `docs/protocol-v3.md`，旧版记录保留在 `docs/protocol-v1.md` 和 `docs/protocol-v2.md`。
