# RemoPad Protocol v3

v3 运行在 UDP/IPv4 上，默认端口 `51234`。局域网发现报文本身不包含秘密；连接建立必须经过由长期配对密钥认证的 P-256 ECDH 临时密钥交换。输入、控制、ACK 和心跳随后使用每次连接独立的会话密钥加密并认证。多字节整数使用 little-endian。

## 长期配对密钥

Windows 接收器为当前 Windows 用户首次运行时随机生成 32 字节配对密钥。每个用户配置独立生成，不是所有 RemoPad 用户共用同一个值。

Windows 使用 DPAPI `CurrentUser` 保护后保存到 `%LOCALAPPDATA%\RemoPad\pairing-key.dat`。只有同一 Windows 用户上下文能够解密。升级时，旧 `pairing-key.txt` 会在成功迁移后删除。Android 将用户录入的密钥保存在应用私有数据中，并禁用了系统备份。

可用 `-ResetPairingKey` 启动接收器来轮换长期密钥；轮换后需要在 Android 重新录入。

## 自动发现

客户端向 UDP 51234 广播 ASCII `RMD1`。接收器返回：

| 偏移 | 长度 | 字段 |
| ---: | ---: | --- |
| 0 | 4 | ASCII `RMR1` |
| 4 | 2 | 接收端口 UInt16 |
| 6 | 可变 | UTF-8 电脑名称 |

发现响应不认证，因此攻击者可以干扰或伪造发现结果，但无法通过后续认证握手。客户端只有完成握手并收到加密 Pong 后才显示连接成功。

## 客户端 Hello

客户端为每次连接生成新的 P-256 临时密钥对和 16 字节随机数。公钥使用 65 字节 SEC1 未压缩格式：`0x04 || X(32) || Y(32)`。

| 偏移 | 长度 | 字段 |
| ---: | ---: | --- |
| 0 | 4 | ASCII `RMH3` |
| 4 | 16 | clientNonce |
| 20 | 65 | clientEphemeralPublicKey |
| 85 | 16 | `HMAC-SHA256(pairingKey, bytes[0..84])` 前 16 字节 |

## 服务端 Hello

服务端验证客户端 Hello 后为该连接生成新的 P-256 临时密钥对和随机数，并返回：

| 偏移 | 长度 | 字段 |
| ---: | ---: | --- |
| 0 | 4 | ASCII `RMS3` |
| 4 | 16 | 原样返回的 clientNonce |
| 20 | 16 | serverNonce |
| 36 | 65 | serverEphemeralPublicKey |
| 101 | 16 | `HMAC-SHA256(pairingKey, bytes[0..100])` 前 16 字节 |

双方计算 P-256 ECDH 共享秘密并按以下顺序派生会话密钥：

```text
sharedHash = SHA-256(ECDH shared secret)
sessionSeed = HMAC-SHA256(
  pairingKey,
  ASCII("RemoPad v3 session") || sharedHash || clientNonce || serverNonce
)
encryptionKey = HMAC-SHA256(sessionSeed, ASCII("RemoPad encryption v3"))
authenticationKey = HMAC-SHA256(sessionSeed, ASCII("RemoPad authentication v3"))
```

临时私钥在握手完成后不再保留。长期配对密钥将来泄露不会解密过去捕获的会话；泄露仍允许攻击者建立新的连接，因此发现泄露后应轮换密钥。

## 加密外层

| 偏移 | 长度 | 字段 |
| ---: | ---: | --- |
| 0 | 4 | ASCII `RME3` |
| 4 | 1 | 版本 `3` |
| 5 | 1 | flags，当前为 `0` |
| 6 | 2 | 密文长度 |
| 8 | 16 | 随机 AES IV |
| 24 | 可变 | AES-256-CBC/PKCS7 密文 |
| 末尾 | 16 | HMAC-SHA256 前 16 字节 |

HMAC 使用本次连接的 `authenticationKey`，覆盖从 magic 到密文末尾，必须在解密前以常量时间比较。每个数据包使用新的随机 IV。

## 加密内层与消息

内层字段和消息类型沿用 v2：8 字节头部包含类型、保留字段、payloadLength 和 sequence；移动、滚动、按钮、Ping/Pong、UTF-8 文本、按键、ACK 和原子点击的载荷定义不变。

可靠事件约每 110ms 重发，最多 7 次。移动与滚动为相对增量，Android 发送端在网络线程来不及发送时必须累积增量，不能只覆盖为最新事件。接收器按远程端点、消息类型和 sequence 去重。

每 2 秒发送加密 Ping 并计算 RTT；5 秒没有 Pong 时自动重连。自动重连执行新的 ECDH 握手并产生新的会话密钥。接收器会清除十分钟无活动的会话。
