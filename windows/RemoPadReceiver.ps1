[CmdletBinding()]
param(
    [ValidateRange(1, 65535)]
    [int]$Port = 51234,
    [string]$PairingKey = '',
    [ValidateRange(0.1, 10.0)]
    [double]$PointerSensitivity = 1.35,
    [ValidateRange(0.1, 20.0)]
    [double]$WheelScale = 4.0,
    [switch]$DryRun,
    [switch]$SelfTest
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Add-Type -TypeDefinition @"
using System;
using System.Collections.Generic;
using System.IO;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;

public sealed class RemoPadPacket
{
    public int Type { get; set; }
    public uint Sequence { get; set; }
    public byte[] Payload { get; set; }
}

public static class RemoPadCrypto
{
    private static readonly byte[] Magic = Encoding.ASCII.GetBytes("RME2");
    private const int HeaderLength = 24;
    private const int TagLength = 16;
    private const int MaxPayloadLength = 2048;

    public static byte[] GenerateKey()
    {
        byte[] key = new byte[32];
        using (RandomNumberGenerator random = RandomNumberGenerator.Create())
        {
            random.GetBytes(key);
        }
        return key;
    }

    public static string NormalizeKey(string value)
    {
        if (value == null) throw new ArgumentNullException("value");
        string normalized = value.Replace("-", "").Replace(" ", "").Trim();
        if (normalized.Length != 64)
            throw new ArgumentException("Pairing key must contain 64 hexadecimal digits.");
        return ToHex(ParseKey(normalized));
    }

    public static string ToHex(byte[] value)
    {
        StringBuilder result = new StringBuilder(value.Length * 2);
        foreach (byte item in value) result.Append(item.ToString("X2"));
        return result.ToString();
    }

    public static byte[] Encrypt(int type, uint sequence, byte[] payload, string keyText)
    {
        if (payload == null) payload = new byte[0];
        if (payload.Length > MaxPayloadLength)
            throw new ArgumentException("Payload is too large.");
        byte[] masterKey = ParseKey(keyText);
        byte[] encryptionKey = Derive(masterKey, "RemoPad encryption v2");
        byte[] authenticationKey = Derive(masterKey, "RemoPad authentication v2");

        byte[] inner;
        using (MemoryStream stream = new MemoryStream())
        using (BinaryWriter writer = new BinaryWriter(stream))
        {
            writer.Write((byte)type);
            writer.Write((byte)0);
            writer.Write((ushort)payload.Length);
            writer.Write(sequence);
            writer.Write(payload);
            inner = stream.ToArray();
        }

        byte[] iv = new byte[16];
        using (RandomNumberGenerator random = RandomNumberGenerator.Create())
        {
            random.GetBytes(iv);
        }
        byte[] encrypted;
        using (Aes aes = Aes.Create())
        {
            aes.KeySize = 256;
            aes.Mode = CipherMode.CBC;
            aes.Padding = PaddingMode.PKCS7;
            aes.Key = encryptionKey;
            aes.IV = iv;
            using (ICryptoTransform encryptor = aes.CreateEncryptor())
                encrypted = encryptor.TransformFinalBlock(inner, 0, inner.Length);
        }

        byte[] authenticated = new byte[HeaderLength + encrypted.Length];
        Array.Copy(Magic, 0, authenticated, 0, Magic.Length);
        authenticated[4] = 2;
        authenticated[5] = 0;
        Array.Copy(BitConverter.GetBytes((ushort)encrypted.Length), 0, authenticated, 6, 2);
        Array.Copy(iv, 0, authenticated, 8, iv.Length);
        Array.Copy(encrypted, 0, authenticated, HeaderLength, encrypted.Length);
        byte[] tag = Authenticate(authenticationKey, authenticated);
        byte[] packet = new byte[authenticated.Length + TagLength];
        Array.Copy(authenticated, packet, authenticated.Length);
        Array.Copy(tag, 0, packet, authenticated.Length, TagLength);
        return packet;
    }

    public static RemoPadPacket Decrypt(byte[] packet, string keyText)
    {
        if (packet == null || packet.Length < HeaderLength + 16 + TagLength) return null;
        for (int i = 0; i < Magic.Length; i++) if (packet[i] != Magic[i]) return null;
        if (packet[4] != 2 || packet[5] != 0) return null;
        int encryptedLength = BitConverter.ToUInt16(packet, 6);
        if (encryptedLength < 16 || encryptedLength % 16 != 0 ||
            packet.Length != HeaderLength + encryptedLength + TagLength) return null;

        byte[] masterKey;
        try { masterKey = ParseKey(keyText); }
        catch { return null; }
        byte[] encryptionKey = Derive(masterKey, "RemoPad encryption v2");
        byte[] authenticationKey = Derive(masterKey, "RemoPad authentication v2");
        int tagOffset = HeaderLength + encryptedLength;
        byte[] authenticated = new byte[tagOffset];
        Array.Copy(packet, authenticated, tagOffset);
        byte[] expected = Authenticate(authenticationKey, authenticated);
        int difference = 0;
        for (int i = 0; i < TagLength; i++) difference |= expected[i] ^ packet[tagOffset + i];
        if (difference != 0) return null;

        byte[] iv = new byte[16];
        Array.Copy(packet, 8, iv, 0, iv.Length);
        byte[] encrypted = new byte[encryptedLength];
        Array.Copy(packet, HeaderLength, encrypted, 0, encryptedLength);
        byte[] inner;
        try
        {
            using (Aes aes = Aes.Create())
            {
                aes.KeySize = 256;
                aes.Mode = CipherMode.CBC;
                aes.Padding = PaddingMode.PKCS7;
                aes.Key = encryptionKey;
                aes.IV = iv;
                using (ICryptoTransform decryptor = aes.CreateDecryptor())
                    inner = decryptor.TransformFinalBlock(encrypted, 0, encrypted.Length);
            }
        }
        catch (CryptographicException) { return null; }
        if (inner.Length < 8 || inner[1] != 0) return null;
        int payloadLength = BitConverter.ToUInt16(inner, 2);
        if (payloadLength > MaxPayloadLength || inner.Length != 8 + payloadLength) return null;
        byte[] payload = new byte[payloadLength];
        Array.Copy(inner, 8, payload, 0, payloadLength);
        return new RemoPadPacket {
            Type = inner[0], Sequence = BitConverter.ToUInt32(inner, 4), Payload = payload
        };
    }

    private static byte[] ParseKey(string text)
    {
        string normalized = text.Replace("-", "").Replace(" ", "").Trim();
        if (normalized.Length != 64) throw new ArgumentException("Invalid key length.");
        byte[] result = new byte[32];
        for (int i = 0; i < result.Length; i++)
            result[i] = Convert.ToByte(normalized.Substring(i * 2, 2), 16);
        return result;
    }

    private static byte[] Derive(byte[] key, string label)
    {
        return Authenticate(key, Encoding.ASCII.GetBytes(label));
    }

    private static byte[] Authenticate(byte[] key, byte[] data)
    {
        using (HMACSHA256 hmac = new HMACSHA256(key)) return hmac.ComputeHash(data);
    }
}

public static class RemoPadInput
{
    private const uint INPUT_MOUSE = 0;
    private const uint INPUT_KEYBOARD = 1;
    private const uint MOUSEEVENTF_MOVE = 0x0001;
    private const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
    private const uint MOUSEEVENTF_LEFTUP = 0x0004;
    private const uint MOUSEEVENTF_RIGHTDOWN = 0x0008;
    private const uint MOUSEEVENTF_RIGHTUP = 0x0010;
    private const uint MOUSEEVENTF_WHEEL = 0x0800;
    private const uint MOUSEEVENTF_HWHEEL = 0x1000;
    private const uint KEYEVENTF_KEYUP = 0x0002;
    private const uint KEYEVENTF_UNICODE = 0x0004;

    [StructLayout(LayoutKind.Sequential)]
    private struct MOUSEINPUT
    {
        public int dx; public int dy; public uint mouseData; public uint dwFlags;
        public uint time; public IntPtr dwExtraInfo;
    }
    [StructLayout(LayoutKind.Sequential)]
    private struct KEYBDINPUT
    {
        public ushort wVk; public ushort wScan; public uint dwFlags;
        public uint time; public IntPtr dwExtraInfo;
    }
    [StructLayout(LayoutKind.Explicit)]
    private struct INPUTUNION
    {
        [FieldOffset(0)] public MOUSEINPUT mi;
        [FieldOffset(0)] public KEYBDINPUT ki;
    }
    [StructLayout(LayoutKind.Sequential)]
    private struct INPUT { public uint type; public INPUTUNION union; }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint SendInput(uint count, INPUT[] inputs, int size);

    private static int Send(INPUT[] inputs)
    {
        if (inputs.Length == 0) return 0;
        if (SendInput((uint)inputs.Length, inputs, Marshal.SizeOf(typeof(INPUT))) == inputs.Length)
            return 0;
        int error = Marshal.GetLastWin32Error();
        return error == 0 ? -1 : error;
    }
    private static INPUT Mouse(uint flags, int dx, int dy, int data)
    {
        INPUT input = new INPUT(); input.type = INPUT_MOUSE;
        input.union.mi.dx = dx; input.union.mi.dy = dy;
        input.union.mi.mouseData = unchecked((uint)data); input.union.mi.dwFlags = flags;
        return input;
    }
    private static INPUT Keyboard(ushort virtualKey, ushort scanCode, uint flags)
    {
        INPUT input = new INPUT(); input.type = INPUT_KEYBOARD;
        input.union.ki.wVk = virtualKey; input.union.ki.wScan = scanCode;
        input.union.ki.dwFlags = flags; return input;
    }

    public static int Move(int dx, int dy) { return Send(new INPUT[] { Mouse(MOUSEEVENTF_MOVE, dx, dy, 0) }); }
    public static int VerticalWheel(int amount) { return Send(new INPUT[] { Mouse(MOUSEEVENTF_WHEEL, 0, 0, amount) }); }
    public static int HorizontalWheel(int amount) { return Send(new INPUT[] { Mouse(MOUSEEVENTF_HWHEEL, 0, 0, amount) }); }
    public static int Button(int button, bool down)
    {
        uint flag;
        if (button == 1) flag = down ? MOUSEEVENTF_LEFTDOWN : MOUSEEVENTF_LEFTUP;
        else if (button == 2) flag = down ? MOUSEEVENTF_RIGHTDOWN : MOUSEEVENTF_RIGHTUP;
        else throw new ArgumentOutOfRangeException("button");
        return Send(new INPUT[] { Mouse(flag, 0, 0, 0) });
    }
    public static int Click(int button)
    {
        uint down;
        uint up;
        if (button == 1) { down = MOUSEEVENTF_LEFTDOWN; up = MOUSEEVENTF_LEFTUP; }
        else if (button == 2) { down = MOUSEEVENTF_RIGHTDOWN; up = MOUSEEVENTF_RIGHTUP; }
        else throw new ArgumentOutOfRangeException("button");
        return Send(new INPUT[] { Mouse(down, 0, 0, 0), Mouse(up, 0, 0, 0) });
    }
    public static int KeyPress(int key)
    {
        ushort virtualKey;
        if (key == 1) virtualKey = 0x08;
        else if (key == 2) virtualKey = 0x0D;
        else if (key == 3) virtualKey = 0x09;
        else if (key == 4) virtualKey = 0x1B;
        else throw new ArgumentOutOfRangeException("key");
        return Send(new INPUT[] { Keyboard(virtualKey, 0, 0), Keyboard(virtualKey, 0, KEYEVENTF_KEYUP) });
    }
    public static int Text(string text)
    {
        List<INPUT> inputs = new List<INPUT>(text.Length * 2);
        foreach (char value in text)
        {
            inputs.Add(Keyboard(0, value, KEYEVENTF_UNICODE));
            inputs.Add(Keyboard(0, value, KEYEVENTF_UNICODE | KEYEVENTF_KEYUP));
        }
        return Send(inputs.ToArray());
    }
}
"@

function Get-RemoPadPairingKey {
    param([string]$ProvidedKey, [switch]$ForSelfTest)
    if ($ForSelfTest) { return ('01' * 32) }
    if (-not [string]::IsNullOrWhiteSpace($ProvidedKey)) {
        return [RemoPadCrypto]::NormalizeKey($ProvidedKey)
    }
    $localData = [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)
    $keyDirectory = Join-Path $localData 'RemoPad'
    $keyPath = Join-Path $keyDirectory 'pairing-key.txt'
    if (Test-Path -LiteralPath $keyPath) {
        return [RemoPadCrypto]::NormalizeKey([IO.File]::ReadAllText($keyPath, [Text.Encoding]::ASCII))
    }
    [IO.Directory]::CreateDirectory($keyDirectory) | Out-Null
    $generated = [RemoPadCrypto]::ToHex([RemoPadCrypto]::GenerateKey())
    [IO.File]::WriteAllText($keyPath, $generated, [Text.Encoding]::ASCII)
    return $generated
}

function Format-RemoPadKey {
    param([string]$Key)
    return ([regex]::Matches($Key, '.{1,4}') | ForEach-Object { $_.Value }) -join '-'
}

function New-RemoPadDiscoveryResponse {
    param([int]$ResponsePort)
    [byte[]]$nameBytes = [Text.Encoding]::UTF8.GetBytes([Environment]::MachineName)
    if ($nameBytes.Length -gt 80) { [byte[]]$nameBytes = $nameBytes[0..79] }
    $response = New-Object byte[] (6 + $nameBytes.Length)
    [Array]::Copy([Text.Encoding]::ASCII.GetBytes('RMR1'), 0, $response, 0, 4)
    [Array]::Copy([BitConverter]::GetBytes([uint16]$ResponsePort), 0, $response, 4, 2)
    [Array]::Copy($nameBytes, 0, $response, 6, $nameBytes.Length)
    return $response
}

function Get-LocalIPv4Addresses {
    try {
        return [Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces() |
            Where-Object { $_.OperationalStatus -eq 'Up' } |
            ForEach-Object { $_.GetIPProperties().UnicastAddresses } |
            ForEach-Object { $_.Address } |
            Where-Object { $_.AddressFamily -eq [Net.Sockets.AddressFamily]::InterNetwork -and -not [Net.IPAddress]::IsLoopback($_) } |
            Select-Object -Unique
    }
    catch { return @() }
}

$PairingKey = Get-RemoPadPairingKey -ProvidedKey $PairingKey -ForSelfTest:$SelfTest

if ($SelfTest) {
    $payload = New-Object byte[] 8
    [Array]::Copy([BitConverter]::GetBytes([single]1.5), 0, $payload, 0, 4)
    [Array]::Copy([BitConverter]::GetBytes([single]-2.25), 0, $payload, 4, 4)
    $encrypted = [RemoPadCrypto]::Encrypt(1, 42, $payload, $PairingKey)
    $parsed = [RemoPadCrypto]::Decrypt($encrypted, $PairingKey)
    if ($null -eq $parsed -or $parsed.Type -ne 1 -or $parsed.Sequence -ne 42 -or
        [Math]::Abs([BitConverter]::ToSingle($parsed.Payload, 0) - 1.5) -gt 0.001 -or
        [Math]::Abs([BitConverter]::ToSingle($parsed.Payload, 4) + 2.25) -gt 0.001) {
        throw 'Encrypted protocol self-test failed.'
    }
    $encrypted[$encrypted.Length - 1] = $encrypted[$encrypted.Length - 1] -bxor 1
    if ($null -ne [RemoPadCrypto]::Decrypt($encrypted, $PairingKey)) {
        throw 'Authentication-tag self-test failed.'
    }
    Write-Host 'RemoPad encrypted protocol self-test passed.' -ForegroundColor Green
    exit 0
}

$script:lastInputWarningAt = [DateTime]::MinValue
function Write-RemoPadInputWarning {
    param([int]$ErrorCode)
    $now = [DateTime]::UtcNow
    if (($now - $script:lastInputWarningAt).TotalSeconds -lt 5) { return }
    $script:lastInputWarningAt = $now
    if ($ErrorCode -eq 5) { $detail = '目标程序可能以管理员身份运行；请用管理员身份启动接收器。' }
    elseif ($ErrorCode -eq -1) { $detail = 'Windows 阻止了输入注入，可能是权限级别或安全桌面限制。' }
    else { $detail = (New-Object ComponentModel.Win32Exception($ErrorCode)).Message }
    Write-Warning "SendInput 暂时失败（代码 $ErrorCode）：$detail 接收器将继续运行。"
}

$udp = New-Object Net.Sockets.UdpClient($Port)
$remote = New-Object Net.IPEndPoint([Net.IPAddress]::Any, 0)
$lastSequence = @{}
$lastPingSequence = @{}
$processedReliable = @{}
$processedOrder = New-Object Collections.Queue
$moveResidualX = 0.0
$moveResidualY = 0.0
$scrollResidualX = 0.0
$scrollResidualY = 0.0
[uint32]$script:serverSequence = 0

function Send-RemoPadEncrypted {
    param([int]$Type, [byte[]]$Payload, [Net.IPEndPoint]$Target)
    $script:serverSequence++
    $packet = [RemoPadCrypto]::Encrypt($Type, $script:serverSequence, $Payload, $PairingKey)
    [void]$udp.Send($packet, $packet.Length, $Target)
}
function Send-RemoPadAck {
    param([uint32]$AcknowledgedSequence, [Net.IPEndPoint]$Target)
    Send-RemoPadEncrypted -Type 7 -Payload ([BitConverter]::GetBytes($AcknowledgedSequence)) -Target $Target
}

Write-Host 'RemoPad Windows Receiver v0.3' -ForegroundColor Cyan
Write-Host "Listening on UDP port $Port"
Write-Host ('Secure pairing key: ' + (Format-RemoPadKey $PairingKey)) -ForegroundColor Yellow
$addresses = @(Get-LocalIPv4Addresses)
if ($addresses.Count -gt 0) { Write-Host ('Local IPv4: ' + (($addresses | ForEach-Object { $_.ToString() }) -join ', ')) }
if ($DryRun) { Write-Host 'Dry-run mode: input injection is disabled.' -ForegroundColor Yellow }
Write-Host 'Encrypted discovery and reliable controls are enabled.' -ForegroundColor Green
Write-Host 'Press Ctrl+C to stop.'

try {
    while ($true) {
        $buffer = $udp.Receive([ref]$remote)
        if ($buffer.Length -eq 4 -and [Text.Encoding]::ASCII.GetString($buffer) -eq 'RMD1') {
            $discovery = New-RemoPadDiscoveryResponse -ResponsePort $Port
            [void]$udp.Send($discovery, $discovery.Length, $remote)
            continue
        }
        $message = [RemoPadCrypto]::Decrypt($buffer, $PairingKey)
        if ($null -eq $message) { continue }
        $endpointKey = '{0}:{1}' -f $remote.Address, $remote.Port

        if ($message.Type -eq 4) {
            $isNewSession = -not $lastPingSequence.ContainsKey($endpointKey) -or [uint32]$message.Sequence -le [uint32]$lastPingSequence[$endpointKey]
            if ($isNewSession) {
                $prefix = $endpointKey + ':'
                foreach ($key in @($lastSequence.Keys)) { if ($key.StartsWith($prefix, [StringComparison]::Ordinal)) { $lastSequence.Remove($key) } }
                foreach ($key in @($processedReliable.Keys)) { if ($key.StartsWith($prefix, [StringComparison]::Ordinal)) { $processedReliable.Remove($key) } }
            }
            $lastPingSequence[$endpointKey] = [uint32]$message.Sequence
            Send-RemoPadEncrypted -Type 8 -Payload ([BitConverter]::GetBytes([uint32]$message.Sequence)) -Target $remote
            if ($DryRun) { Write-Host "secure-ping from $($remote.Address)" }
            continue
        }

        if ($message.Type -eq 1 -or $message.Type -eq 2) {
            if ($message.Payload.Length -ne 8) { continue }
            $sequenceKey = $endpointKey + ':' + $message.Type
            if ($lastSequence.ContainsKey($sequenceKey) -and [uint32]$message.Sequence -le [uint32]$lastSequence[$sequenceKey]) { continue }
            $lastSequence[$sequenceKey] = [uint32]$message.Sequence
        }

        if ($message.Type -eq 3 -or $message.Type -eq 5 -or $message.Type -eq 6 -or $message.Type -eq 9) {
            $reliableKey = $endpointKey + ':' + $message.Sequence
            $alreadyProcessed = $processedReliable.ContainsKey($reliableKey)
            if (-not $alreadyProcessed) {
                $processedReliable[$reliableKey] = $true
                $processedOrder.Enqueue($reliableKey)
                while ($processedOrder.Count -gt 2048) {
                    $expired = [string]$processedOrder.Dequeue()
                    $processedReliable.Remove($expired)
                }
            }
            if ($alreadyProcessed) { Send-RemoPadAck -AcknowledgedSequence $message.Sequence -Target $remote; continue }
        }

        switch ($message.Type) {
            1 {
                $moveResidualX += [double][BitConverter]::ToSingle($message.Payload, 0) * $PointerSensitivity
                $moveResidualY += [double][BitConverter]::ToSingle($message.Payload, 4) * $PointerSensitivity
                $emitX = [int][Math]::Truncate($moveResidualX); $emitY = [int][Math]::Truncate($moveResidualY)
                $moveResidualX -= $emitX; $moveResidualY -= $emitY
                if ($emitX -ne 0 -or $emitY -ne 0) {
                    if ($DryRun) { Write-Host "move $emitX $emitY" }
                    else { $inputError = [RemoPadInput]::Move($emitX, $emitY); if ($inputError -ne 0) { Write-RemoPadInputWarning $inputError } }
                }
            }
            2 {
                $scrollResidualX += [double][BitConverter]::ToSingle($message.Payload, 0) * $WheelScale
                $scrollResidualY += -[double][BitConverter]::ToSingle($message.Payload, 4) * $WheelScale
                $emitHorizontal = [int][Math]::Truncate($scrollResidualX); $emitVertical = [int][Math]::Truncate($scrollResidualY)
                $scrollResidualX -= $emitHorizontal; $scrollResidualY -= $emitVertical
                if ($emitHorizontal -ne 0) {
                    if ($DryRun) { Write-Host "horizontal-scroll $emitHorizontal" }
                    else { $inputError = [RemoPadInput]::HorizontalWheel($emitHorizontal); if ($inputError -ne 0) { Write-RemoPadInputWarning $inputError } }
                }
                if ($emitVertical -ne 0) {
                    if ($DryRun) { Write-Host "vertical-scroll $emitVertical" }
                    else { $inputError = [RemoPadInput]::VerticalWheel($emitVertical); if ($inputError -ne 0) { Write-RemoPadInputWarning $inputError } }
                }
            }
            3 {
                if ($message.Payload.Length -ne 4 -or $message.Payload[0] -lt 1 -or $message.Payload[0] -gt 2 -or $message.Payload[1] -gt 1) { continue }
                $button = [int]$message.Payload[0]; $down = $message.Payload[1] -eq 1
                if ($DryRun) { Write-Host "button $button down=$down" }
                else { $inputError = [RemoPadInput]::Button($button, $down); if ($inputError -ne 0) { Write-RemoPadInputWarning $inputError } }
                Send-RemoPadAck -AcknowledgedSequence $message.Sequence -Target $remote
            }
            9 {
                if ($message.Payload.Length -ne 4 -or $message.Payload[0] -lt 1 -or $message.Payload[0] -gt 2) { continue }
                $button = [int]$message.Payload[0]
                if ($DryRun) { Write-Host "click $button" }
                else { $inputError = [RemoPadInput]::Click($button); if ($inputError -ne 0) { Write-RemoPadInputWarning $inputError } }
                Send-RemoPadAck -AcknowledgedSequence $message.Sequence -Target $remote
            }
            5 {
                try { $text = [Text.UTF8Encoding]::new($false, $true).GetString($message.Payload) } catch { continue }
                if ([string]::IsNullOrEmpty($text)) { continue }
                if ($DryRun) { Write-Host "text length=$($text.Length)" }
                else { $inputError = [RemoPadInput]::Text($text); if ($inputError -ne 0) { Write-RemoPadInputWarning $inputError } }
                Send-RemoPadAck -AcknowledgedSequence $message.Sequence -Target $remote
            }
            6 {
                if ($message.Payload.Length -ne 4 -or $message.Payload[0] -lt 1 -or $message.Payload[0] -gt 4) { continue }
                $key = [int]$message.Payload[0]
                if ($DryRun) { Write-Host "key $key" }
                else { $inputError = [RemoPadInput]::KeyPress($key); if ($inputError -ne 0) { Write-RemoPadInputWarning $inputError } }
                Send-RemoPadAck -AcknowledgedSequence $message.Sequence -Target $remote
            }
        }
    }
}
finally {
    if (-not $DryRun) { [void][RemoPadInput]::Button(1, $false); [void][RemoPadInput]::Button(2, $false) }
    $udp.Close()
}