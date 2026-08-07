# RemoPad Protocol v2

v2 运行在 UDP/IPv4 上，默认端口 `51234`。除公开的局域网发现报文外，所有输入、控制、ACK 和心跳均加密并认证。多字节整数使用 little-endian。

## 配对密钥

Windows 首次运行生成 32 字节随机主密钥，保存到 `%LOCALAPPDATA%\RemoPad\pairing-key.txt`。Android 首次录入后保存在应用私有首选项。显示时可用连字符分组，解析时忽略连字符和空格。

从主密钥分别派生：

- `HMAC-SHA256(master, "RemoPad encryption v2")`：AES-256 密钥
- `HMAC-SHA256(master, "RemoPad authentication v2")`：认证密钥

## 自动发现

客户端向 UDP 51234 广播 ASCII `RMD1`。接收器返回：

| 偏移 | 长度 | 字段 |
| ---: | ---: | --- |
| 0 | 4 | ASCII `RMR1` |
| 4 | 2 | 接收端口 UInt16 |
| 6 | 可变 | UTF-8 电脑名称 |

发现响应不含密钥，也不作为连接成功依据；客户端必须完成后续加密 Ping/Pong。

## 加密外层

| 偏移 | 长度 | 字段 |
| ---: | ---: | --- |
| 0 | 4 | ASCII `RME2` |
| 4 | 1 | 版本 `2` |
| 5 | 1 | flags，当前为 `0` |
| 6 | 2 | 密文长度 |
| 8 | 16 | 随机 AES IV |
| 24 | 可变 | AES-256-CBC/PKCS7 密文 |
| 末尾 | 16 | HMAC-SHA256 前 16 字节 |

HMAC 覆盖从 magic 到密文末尾，必须在解密前以常量时间比较。每个数据包使用新的随机 IV。

## 加密内层

| 偏移 | 长度 | 字段 |
| ---: | ---: | --- |
| 0 | 1 | 消息类型 |
| 1 | 1 | reserved `0` |
| 2 | 2 | payloadLength |
| 4 | 4 | sequence |
| 8 | 可变 | payload，最多 2048 字节 |

## 消息类型

| 类型 | 名称 | 载荷 | 可靠 |
| ---: | --- | --- | --- |
| 1 | Move | Float32 dx、dy | 否，保留最新值 |
| 2 | Scroll | Float32 dx、dy | 否，保留最新值 |
| 3 | Button state | button、state、2 字节保留 | 是 |
| 4 | Ping | 无 | Pong 响应 |
| 5 | UTF-8 text | UTF-8 字节 | 是 |
| 6 | Key press | key、3 字节保留 | 是 |
| 7 | ACK | 被确认 sequence | 响应 |
| 8 | Pong | Ping sequence | 响应 |
| 9 | Atomic click | button、3 字节保留 | 是 |

可靠事件一次只发送一个。未收到 ACK 时约每 110ms 重发，最多 7 次。接收器按远程端点和 sequence 去重；重复包不再次执行，但仍返回 ACK。原子点击在一次 Windows `SendInput` 调用中发送按下和释放。

连接后每 2 秒发送 Ping 并计算 RTT；5 秒没有 Pong 时进入自动重连。新会话的低序号 Ping 会清除该端点旧的移动和可靠事件历史。

## 安全边界

v2 能防止局域网窃听、篡改和不知道密钥的输入注入。它使用预共享密钥，不提供前向保密；主密钥泄露后必须删除 Windows 密钥文件并在 Android 清除旧密钥重新配对。