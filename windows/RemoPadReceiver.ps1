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
    [switch]$SelfTest,
    [switch]$NoElevate,
    [switch]$ResetPairingKey
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Test-RemoPadAdministrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

if (-not $SelfTest -and -not $DryRun -and -not $NoElevate -and
    -not (Test-RemoPadAdministrator)) {
    if ([string]::IsNullOrWhiteSpace($PSCommandPath)) {
        throw '无法定位接收器脚本路径，请右键使用管理员身份运行。'
    }
    if (-not [string]::IsNullOrWhiteSpace($PairingKey)) {
        $env:REMOPAD_PAIRING_KEY = $PairingKey
    }
    $quotedScript = '"' + $PSCommandPath + '"'
    $arguments = @(
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $quotedScript,
        '-Port', $Port,
        '-PointerSensitivity', $PointerSensitivity.ToString([Globalization.CultureInfo]::InvariantCulture),
        '-WheelScale', $WheelScale.ToString([Globalization.CultureInfo]::InvariantCulture)
    )
    if ($ResetPairingKey) { $arguments += '-ResetPairingKey' }
    try {
        $startInfo = New-Object Diagnostics.ProcessStartInfo
        $startInfo.FileName = 'powershell.exe'
        $startInfo.Arguments = ($arguments -join ' ')
        $startInfo.WorkingDirectory = Split-Path -Parent $PSCommandPath
        $startInfo.Verb = 'runas'
        $startInfo.UseShellExecute = $true
        [void][Diagnostics.Process]::Start($startInfo)
    }
    catch {
        throw '管理员权限请求被取消；接收器尚未启动。'
    }
    exit 0
}

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

public sealed class RemoPadHandshakeResult
{
    public byte[] Response { get; set; }
    public RemoPadSession Session { get; set; }
}

public sealed class RemoPadSession
{
    private static readonly byte[] Magic = Encoding.ASCII.GetBytes("RME3");
    private const int HeaderLength = 24;
    private const int TagLength = 16;
    private const int MaxPayloadLength = 2048;
    private byte[] encryptionKey;
    private byte[] authenticationKey;

    internal RemoPadSession(byte[] sessionSeed)
    {
        encryptionKey = RemoPadCrypto.Authenticate(
            sessionSeed, Encoding.ASCII.GetBytes("RemoPad encryption v3"));
        authenticationKey = RemoPadCrypto.Authenticate(
            sessionSeed, Encoding.ASCII.GetBytes("RemoPad authentication v3"));
    }

    public byte[] Encrypt(int type, uint sequence, byte[] payload)
    {
        if (encryptionKey == null) throw new ObjectDisposedException("RemoPadSession");
        if (payload == null) payload = new byte[0];
        if (payload.Length > MaxPayloadLength)
            throw new ArgumentException("Payload is too large.");

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

        byte[] iv = RemoPadCrypto.RandomBytes(16);
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
        authenticated[4] = 3;
        authenticated[5] = 0;
        Array.Copy(BitConverter.GetBytes((ushort)encrypted.Length), 0, authenticated, 6, 2);
        Array.Copy(iv, 0, authenticated, 8, iv.Length);
        Array.Copy(encrypted, 0, authenticated, HeaderLength, encrypted.Length);
        byte[] tag = RemoPadCrypto.Authenticate(authenticationKey, authenticated);
        byte[] packet = new byte[authenticated.Length + TagLength];
        Array.Copy(authenticated, packet, authenticated.Length);
        Array.Copy(tag, 0, packet, authenticated.Length, TagLength);
        return packet;
    }

    public RemoPadPacket Decrypt(byte[] packet)
    {
        if (authenticationKey == null || packet == null ||
            packet.Length < HeaderLength + 16 + TagLength) return null;
        for (int i = 0; i < Magic.Length; i++) if (packet[i] != Magic[i]) return null;
        if (packet[4] != 3 || packet[5] != 0) return null;
        int encryptedLength = BitConverter.ToUInt16(packet, 6);
        if (encryptedLength < 16 || encryptedLength % 16 != 0 ||
            packet.Length != HeaderLength + encryptedLength + TagLength) return null;

        int tagOffset = HeaderLength + encryptedLength;
        byte[] authenticated = new byte[tagOffset];
        Array.Copy(packet, authenticated, tagOffset);
        byte[] expected = RemoPadCrypto.Authenticate(authenticationKey, authenticated);
        if (!RemoPadCrypto.FixedEquals(expected, 0, packet, tagOffset, TagLength)) return null;

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

    public void Clear()
    {
        if (encryptionKey != null) Array.Clear(encryptionKey, 0, encryptionKey.Length);
        if (authenticationKey != null) Array.Clear(authenticationKey, 0, authenticationKey.Length);
        encryptionKey = null;
        authenticationKey = null;
    }
}

public static class RemoPadCrypto
{
    private static readonly byte[] ClientHelloMagic = Encoding.ASCII.GetBytes("RMH3");
    private static readonly byte[] ServerHelloMagic = Encoding.ASCII.GetBytes("RMS3");
    private static readonly byte[] SessionLabel = Encoding.ASCII.GetBytes("RemoPad v3 session");
    private const int NonceLength = 16;
    private const int PublicKeyLength = 65;
    private const int TagLength = 16;
    private const int ClientHelloUnsignedLength = 4 + NonceLength + PublicKeyLength;
    private const int ClientHelloLength = ClientHelloUnsignedLength + TagLength;
    private const int ServerHelloUnsignedLength = 4 + NonceLength + NonceLength + PublicKeyLength;
    private const int ServerHelloLength = ServerHelloUnsignedLength + TagLength;

    public static byte[] GenerateKey() { return RandomBytes(32); }

    public static byte[] RandomBytes(int length)
    {
        byte[] value = new byte[length];
        using (RandomNumberGenerator random = RandomNumberGenerator.Create())
            random.GetBytes(value);
        return value;
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

    public static byte[] ParseKey(string text)
    {
        string normalized = text.Replace("-", "").Replace(" ", "").Trim();
        if (normalized.Length != 64) throw new ArgumentException("Invalid key length.");
        byte[] result = new byte[32];
        for (int i = 0; i < result.Length; i++)
            result[i] = Convert.ToByte(normalized.Substring(i * 2, 2), 16);
        return result;
    }

    public static bool IsClientHello(byte[] packet)
    {
        if (packet == null || packet.Length != ClientHelloLength) return false;
        for (int i = 0; i < ClientHelloMagic.Length; i++)
            if (packet[i] != ClientHelloMagic[i]) return false;
        return true;
    }

    public static RemoPadHandshakeResult AcceptClientHello(byte[] packet, string keyText)
    {
        if (!IsClientHello(packet)) return null;
        byte[] pairingKey;
        try { pairingKey = ParseKey(keyText); }
        catch { return null; }
        try
        {
            byte[] expected = Authenticate(pairingKey, Slice(packet, 0, ClientHelloUnsignedLength));
            if (!FixedEquals(expected, 0, packet, ClientHelloUnsignedLength, TagLength)) return null;

            byte[] clientNonce = Slice(packet, 4, 4 + NonceLength);
            byte[] clientPublic = Slice(packet, 4 + NonceLength, ClientHelloUnsignedLength);
            byte[] serverNonce = RandomBytes(NonceLength);
            using (ECDiffieHellmanCng server = NewEcdh())
            using (CngKey remote = ImportPublicKey(clientPublic))
            {
                byte[] sharedHash = server.DeriveKeyMaterial(remote);
                byte[] sessionSeed = DeriveSessionSeed(pairingKey, sharedHash, clientNonce, serverNonce);
                RemoPadSession session = new RemoPadSession(sessionSeed);
                byte[] serverPublic = ExportPublicKey(server);

                byte[] unsigned = new byte[ServerHelloUnsignedLength];
                Array.Copy(ServerHelloMagic, 0, unsigned, 0, 4);
                Array.Copy(clientNonce, 0, unsigned, 4, NonceLength);
                Array.Copy(serverNonce, 0, unsigned, 4 + NonceLength, NonceLength);
                Array.Copy(serverPublic, 0, unsigned, 4 + NonceLength * 2, PublicKeyLength);
                byte[] tag = Authenticate(pairingKey, unsigned);
                byte[] response = new byte[ServerHelloLength];
                Array.Copy(unsigned, response, unsigned.Length);
                Array.Copy(tag, 0, response, unsigned.Length, TagLength);

                Array.Clear(sharedHash, 0, sharedHash.Length);
                Array.Clear(sessionSeed, 0, sessionSeed.Length);
                return new RemoPadHandshakeResult { Response = response, Session = session };
            }
        }
        catch (CryptographicException) { return null; }
        catch (ArgumentException) { return null; }
        finally { Array.Clear(pairingKey, 0, pairingKey.Length); }
    }

    public static void RunSelfTest(string keyText)
    {
        byte[] pairingKey = ParseKey(keyText);
        try
        {
            using (ECDiffieHellmanCng client = NewEcdh())
            {
                byte[] clientNonce = RandomBytes(NonceLength);
                byte[] unsigned = new byte[ClientHelloUnsignedLength];
                Array.Copy(ClientHelloMagic, unsigned, 4);
                Array.Copy(clientNonce, 0, unsigned, 4, NonceLength);
                Array.Copy(ExportPublicKey(client), 0, unsigned, 4 + NonceLength, PublicKeyLength);
                byte[] tag = Authenticate(pairingKey, unsigned);
                byte[] hello = new byte[ClientHelloLength];
                Array.Copy(unsigned, hello, unsigned.Length);
                Array.Copy(tag, 0, hello, unsigned.Length, TagLength);

                RemoPadHandshakeResult result = AcceptClientHello(hello, keyText);
                if (result == null || result.Response == null || result.Session == null)
                    throw new CryptographicException("Handshake self-test failed.");
                byte[] responseExpected = Authenticate(pairingKey,
                    Slice(result.Response, 0, ServerHelloUnsignedLength));
                if (!FixedEquals(responseExpected, 0, result.Response,
                    ServerHelloUnsignedLength, TagLength))
                    throw new CryptographicException("Server authentication self-test failed.");
                if (!FixedEquals(clientNonce, 0, result.Response, 4, NonceLength))
                    throw new CryptographicException("Nonce self-test failed.");

                byte[] serverNonce = Slice(result.Response, 4 + NonceLength,
                    4 + NonceLength * 2);
                byte[] serverPublic = Slice(result.Response, 4 + NonceLength * 2,
                    ServerHelloUnsignedLength);
                using (CngKey remote = ImportPublicKey(serverPublic))
                {
                    byte[] sharedHash = client.DeriveKeyMaterial(remote);
                    byte[] sessionSeed = DeriveSessionSeed(pairingKey, sharedHash,
                        clientNonce, serverNonce);
                    RemoPadSession clientSession = new RemoPadSession(sessionSeed);
                    byte[] payload = new byte[8];
                    Array.Copy(BitConverter.GetBytes((float)1.5), 0, payload, 0, 4);
                    Array.Copy(BitConverter.GetBytes((float)-2.25), 0, payload, 4, 4);
                    byte[] encrypted = clientSession.Encrypt(1, 42, payload);
                    RemoPadPacket parsed = result.Session.Decrypt(encrypted);
                    if (parsed == null || parsed.Type != 1 || parsed.Sequence != 42 ||
                        Math.Abs(BitConverter.ToSingle(parsed.Payload, 0) - 1.5) > 0.001 ||
                        Math.Abs(BitConverter.ToSingle(parsed.Payload, 4) + 2.25) > 0.001)
                        throw new CryptographicException("Session encryption self-test failed.");
                    encrypted[encrypted.Length - 1] ^= 1;
                    if (result.Session.Decrypt(encrypted) != null)
                        throw new CryptographicException("Authentication-tag self-test failed.");
                    clientSession.Clear();
                    result.Session.Clear();
                    Array.Clear(sharedHash, 0, sharedHash.Length);
                    Array.Clear(sessionSeed, 0, sessionSeed.Length);
                }
            }
        }
        finally { Array.Clear(pairingKey, 0, pairingKey.Length); }
    }

    internal static byte[] Authenticate(byte[] key, byte[] data)
    {
        using (HMACSHA256 hmac = new HMACSHA256(key)) return hmac.ComputeHash(data);
    }

    internal static bool FixedEquals(byte[] left, int leftOffset,
                                     byte[] right, int rightOffset, int length)
    {
        if (left == null || right == null || leftOffset < 0 || rightOffset < 0 ||
            length < 0 || leftOffset + length > left.Length || rightOffset + length > right.Length)
            return false;
        int difference = 0;
        for (int i = 0; i < length; i++) difference |= left[leftOffset + i] ^ right[rightOffset + i];
        return difference == 0;
    }

    private static ECDiffieHellmanCng NewEcdh()
    {
        ECDiffieHellmanCng value = new ECDiffieHellmanCng(256);
        value.KeyDerivationFunction = ECDiffieHellmanKeyDerivationFunction.Hash;
        value.HashAlgorithm = CngAlgorithm.Sha256;
        return value;
    }

    private static byte[] ExportPublicKey(ECDiffieHellmanCng value)
    {
        byte[] blob = value.Key.Export(CngKeyBlobFormat.EccPublicBlob);
        if (blob.Length != 72) throw new CryptographicException("Unexpected P-256 public key.");
        byte[] result = new byte[PublicKeyLength];
        result[0] = 4;
        Array.Copy(blob, 8, result, 1, 64);
        return result;
    }

    private static CngKey ImportPublicKey(byte[] encoded)
    {
        if (encoded == null || encoded.Length != PublicKeyLength || encoded[0] != 4)
            throw new ArgumentException("Invalid P-256 public key.");
        byte[] blob = new byte[72];
        Array.Copy(BitConverter.GetBytes(0x314B4345u), 0, blob, 0, 4);
        Array.Copy(BitConverter.GetBytes(32u), 0, blob, 4, 4);
        Array.Copy(encoded, 1, blob, 8, 64);
        return CngKey.Import(blob, CngKeyBlobFormat.EccPublicBlob);
    }

    private static byte[] DeriveSessionSeed(byte[] pairingKey, byte[] sharedHash,
                                            byte[] clientNonce, byte[] serverNonce)
    {
        byte[] input = new byte[SessionLabel.Length + sharedHash.Length +
                                clientNonce.Length + serverNonce.Length];
        int offset = 0;
        Array.Copy(SessionLabel, 0, input, offset, SessionLabel.Length); offset += SessionLabel.Length;
        Array.Copy(sharedHash, 0, input, offset, sharedHash.Length); offset += sharedHash.Length;
        Array.Copy(clientNonce, 0, input, offset, clientNonce.Length); offset += clientNonce.Length;
        Array.Copy(serverNonce, 0, input, offset, serverNonce.Length);
        return Authenticate(pairingKey, input);
    }

    private static byte[] Slice(byte[] value, int start, int end)
    {
        byte[] result = new byte[end - start];
        Array.Copy(value, start, result, 0, result.Length);
        return result;
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
    private const uint KEYEVENTF_EXTENDEDKEY = 0x0001;
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
        else if (key == 5) virtualKey = 0x21;
        else if (key == 6) virtualKey = 0x22;
        else throw new ArgumentOutOfRangeException("key");
        uint flags = key >= 5 ? KEYEVENTF_EXTENDEDKEY : 0;
        return Send(new INPUT[] { Keyboard(virtualKey, 0, flags), Keyboard(virtualKey, 0, flags | KEYEVENTF_KEYUP) });
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

Add-Type -AssemblyName System.Security

function Save-RemoPadProtectedKey {
    param([string]$Path, [byte[]]$KeyBytes)
    [byte[]]$entropy = [Text.Encoding]::UTF8.GetBytes('RemoPad pairing key v3')
    [byte[]]$protected = [Security.Cryptography.ProtectedData]::Protect(
        $KeyBytes, $entropy, [Security.Cryptography.DataProtectionScope]::CurrentUser)
    [IO.File]::WriteAllBytes($Path, $protected)
}

function Get-RemoPadPairingKey {
    param([string]$ProvidedKey, [switch]$ForSelfTest, [switch]$Reset)
    if ($ForSelfTest) { return ('01' * 32) }
    if ([string]::IsNullOrWhiteSpace($ProvidedKey) -and
        -not [string]::IsNullOrWhiteSpace($env:REMOPAD_PAIRING_KEY)) {
        $ProvidedKey = $env:REMOPAD_PAIRING_KEY
        Remove-Item Env:REMOPAD_PAIRING_KEY -ErrorAction SilentlyContinue
    }
    if (-not [string]::IsNullOrWhiteSpace($ProvidedKey)) {
        return [RemoPadCrypto]::NormalizeKey($ProvidedKey)
    }

    $localData = [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)
    $keyDirectory = Join-Path $localData 'RemoPad'
    $protectedPath = Join-Path $keyDirectory 'pairing-key.dat'
    $legacyPath = Join-Path $keyDirectory 'pairing-key.txt'
    [IO.Directory]::CreateDirectory($keyDirectory) | Out-Null
    if ($Reset) {
        [byte[]]$replacement = [RemoPadCrypto]::GenerateKey()
        Save-RemoPadProtectedKey -Path $protectedPath -KeyBytes $replacement
        if (Test-Path -LiteralPath $legacyPath) { Remove-Item -LiteralPath $legacyPath -Force }
        return [RemoPadCrypto]::ToHex($replacement)
    }

    [byte[]]$entropy = [Text.Encoding]::UTF8.GetBytes('RemoPad pairing key v3')
    if (Test-Path -LiteralPath $protectedPath) {
        [byte[]]$protected = [IO.File]::ReadAllBytes($protectedPath)
        [byte[]]$plain = [Security.Cryptography.ProtectedData]::Unprotect(
            $protected, $entropy, [Security.Cryptography.DataProtectionScope]::CurrentUser)
        if ($plain.Length -ne 32) { throw '受保护的配对密钥长度不正确。' }
        return [RemoPadCrypto]::ToHex($plain)
    }

    if (Test-Path -LiteralPath $legacyPath) {
        $migrated = [RemoPadCrypto]::NormalizeKey(
            [IO.File]::ReadAllText($legacyPath, [Text.Encoding]::ASCII))
        [byte[]]$migratedBytes = [RemoPadCrypto]::ParseKey($migrated)
        Save-RemoPadProtectedKey -Path $protectedPath -KeyBytes $migratedBytes
        Remove-Item -LiteralPath $legacyPath -Force
        Write-Host '旧明文配对密钥已迁移为 Windows DPAPI 当前用户加密存储。' -ForegroundColor Green
        return $migrated
    }

    [byte[]]$generatedBytes = [RemoPadCrypto]::GenerateKey()
    Save-RemoPadProtectedKey -Path $protectedPath -KeyBytes $generatedBytes
    return [RemoPadCrypto]::ToHex($generatedBytes)
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

$PairingKey = Get-RemoPadPairingKey -ProvidedKey $PairingKey -ForSelfTest:$SelfTest -Reset:$ResetPairingKey

if ($SelfTest) {
    [RemoPadCrypto]::RunSelfTest($PairingKey)
    Write-Host 'RemoPad v3 ECDH、会话加密和认证自检通过。' -ForegroundColor Green
    exit 0
}

$script:lastInputWarningAt = [DateTime]::MinValue
function Write-RemoPadInputWarning {
    param([int]$ErrorCode)
    $now = [DateTime]::UtcNow
    if (($now - $script:lastInputWarningAt).TotalSeconds -lt 5) { return }
    $script:lastInputWarningAt = $now
    if ($ErrorCode -eq 5) { $detail = '目标程序的完整性级别更高；请允许接收器的管理员权限请求。' }
    elseif ($ErrorCode -eq -1) { $detail = 'Windows 阻止了输入注入；安全桌面（例如 UAC 界面）不接受此类输入。' }
    else { $detail = (New-Object ComponentModel.Win32Exception($ErrorCode)).Message }
    Write-Warning "SendInput 暂时失败（代码 $ErrorCode）：$detail 接收器将继续运行。"
}

$udp = New-Object Net.Sockets.UdpClient($Port)
$remote = New-Object Net.IPEndPoint([Net.IPAddress]::Any, 0)
$sessions = @{}
$lastSequence = @{}
$processedReliable = @{}
$processedOrder = New-Object Collections.Queue
$moveResidualX = 0.0
$moveResidualY = 0.0
$scrollResidualX = 0.0
$scrollResidualY = 0.0
[uint32]$script:serverSequence = 0
$script:packetCounter = 0

function Clear-RemoPadEndpointState {
    param([string]$EndpointKey)
    $prefix = $EndpointKey + ':'
    foreach ($key in @($lastSequence.Keys)) {
        if ($key.StartsWith($prefix, [StringComparison]::Ordinal)) { $lastSequence.Remove($key) }
    }
    foreach ($key in @($processedReliable.Keys)) {
        if ($key.StartsWith($prefix, [StringComparison]::Ordinal)) { $processedReliable.Remove($key) }
    }
}

function Remove-RemoPadExpiredSessions {
    $cutoff = [DateTime]::UtcNow.AddMinutes(-10)
    foreach ($key in @($sessions.Keys)) {
        if ($sessions[$key].LastSeen -lt $cutoff) {
            $sessions[$key].Crypto.Clear()
            $sessions.Remove($key)
            Clear-RemoPadEndpointState -EndpointKey $key
        }
    }
}

function Send-RemoPadEncrypted {
    param([int]$Type, [byte[]]$Payload, [Net.IPEndPoint]$Target,
          [RemoPadSession]$Session)
    $script:serverSequence++
    $packet = $Session.Encrypt($Type, $script:serverSequence, $Payload)
    [void]$udp.Send($packet, $packet.Length, $Target)
}
function Send-RemoPadAck {
    param([uint32]$AcknowledgedSequence, [Net.IPEndPoint]$Target,
          [RemoPadSession]$Session)
    $ackParameters = @{
        Type = 7
        Payload = [BitConverter]::GetBytes($AcknowledgedSequence)
        Target = $Target
        Session = $Session
    }
    Send-RemoPadEncrypted @ackParameters
}

Write-Host 'RemoPad Windows Receiver v0.5.0' -ForegroundColor Cyan
Write-Host "Listening on UDP port $Port"
Write-Host ('Secure pairing key: ' + (Format-RemoPadKey $PairingKey)) -ForegroundColor Yellow
$addresses = @(Get-LocalIPv4Addresses)
if ($addresses.Count -gt 0) { Write-Host ('Local IPv4: ' + (($addresses | ForEach-Object { $_.ToString() }) -join ', ')) }
if ($DryRun) { Write-Host 'Dry-run mode: input injection is disabled.' -ForegroundColor Yellow }
elseif (Test-RemoPadAdministrator) { Write-Host '管理员输入模式已启用（UAC 安全桌面仍受 Windows 保护）。' -ForegroundColor Green }
else { Write-Warning '接收器未提升权限；管理员程序可能无法接收输入。' }
Write-Host 'Protocol v3 forward secrecy, authenticated handshake, and reliable controls are enabled.' -ForegroundColor Green
Write-Host 'Pairing key is protected with Windows DPAPI for the current user.' -ForegroundColor Green
Write-Host 'Press Ctrl+C to stop.'

try {
    while ($true) {
        $buffer = $udp.Receive([ref]$remote)
        $endpointKey = '{0}:{1}' -f $remote.Address, $remote.Port
        if ($buffer.Length -eq 4 -and [Text.Encoding]::ASCII.GetString($buffer) -eq 'RMD1') {
            $discovery = New-RemoPadDiscoveryResponse -ResponsePort $Port
            [void]$udp.Send($discovery, $discovery.Length, $remote)
            continue
        }

        if ([RemoPadCrypto]::IsClientHello($buffer)) {
            $handshake = [RemoPadCrypto]::AcceptClientHello($buffer, $PairingKey)
            if ($null -eq $handshake) { continue }
            if ($sessions.ContainsKey($endpointKey)) { $sessions[$endpointKey].Crypto.Clear() }
            $sessions[$endpointKey] = [pscustomobject]@{
                Crypto = $handshake.Session
                LastSeen = [DateTime]::UtcNow
            }
            Clear-RemoPadEndpointState -EndpointKey $endpointKey
            [void]$udp.Send($handshake.Response, $handshake.Response.Length, $remote)
            Remove-RemoPadExpiredSessions
            if ($DryRun) { Write-Host "forward-secret handshake from $($remote.Address)" }
            continue
        }

        if (-not $sessions.ContainsKey($endpointKey)) { continue }
        $sessionEntry = $sessions[$endpointKey]
        $message = $sessionEntry.Crypto.Decrypt($buffer)
        if ($null -eq $message) { continue }
        $sessionEntry.LastSeen = [DateTime]::UtcNow
        $script:packetCounter++
        if (($script:packetCounter % 256) -eq 0) { Remove-RemoPadExpiredSessions }

        if ($message.Type -eq 4) {
            $pongParameters = @{
                Type = 8
                Payload = [BitConverter]::GetBytes([uint32]$message.Sequence)
                Target = $remote
                Session = $sessionEntry.Crypto
            }
            Send-RemoPadEncrypted @pongParameters
            if ($DryRun) { Write-Host "secure-ping from $($remote.Address)" }
            continue
        }

        if ($message.Type -eq 1 -or $message.Type -eq 2) {
            if ($message.Payload.Length -ne 8) { continue }
            $sequenceKey = $endpointKey + ':' + $message.Type
            if ($lastSequence.ContainsKey($sequenceKey) -and
                [uint32]$message.Sequence -le [uint32]$lastSequence[$sequenceKey]) { continue }
            $lastSequence[$sequenceKey] = [uint32]$message.Sequence
        }

        if ($message.Type -eq 3 -or $message.Type -eq 5 -or
            $message.Type -eq 6 -or $message.Type -eq 9 -or $message.Type -eq 10) {
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
            if ($alreadyProcessed) {
                Send-RemoPadAck -AcknowledgedSequence $message.Sequence -Target $remote -Session $sessionEntry.Crypto
                continue
            }
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
                Send-RemoPadAck -AcknowledgedSequence $message.Sequence -Target $remote -Session $sessionEntry.Crypto
            }
            9 {
                if ($message.Payload.Length -ne 4 -or $message.Payload[0] -lt 1 -or $message.Payload[0] -gt 2) { continue }
                $button = [int]$message.Payload[0]
                if ($DryRun) { Write-Host "click $button" }
                else { $inputError = [RemoPadInput]::Click($button); if ($inputError -ne 0) { Write-RemoPadInputWarning $inputError } }
                Send-RemoPadAck -AcknowledgedSequence $message.Sequence -Target $remote -Session $sessionEntry.Crypto
            }
            5 {
                try { $text = [Text.UTF8Encoding]::new($false, $true).GetString($message.Payload) } catch { continue }
                if ([string]::IsNullOrEmpty($text)) { continue }
                if ($DryRun) { Write-Host "text length=$($text.Length)" }
                else { $inputError = [RemoPadInput]::Text($text); if ($inputError -ne 0) { Write-RemoPadInputWarning $inputError } }
                Send-RemoPadAck -AcknowledgedSequence $message.Sequence -Target $remote -Session $sessionEntry.Crypto
            }
            6 {
                if ($message.Payload.Length -ne 4 -or $message.Payload[0] -lt 1 -or $message.Payload[0] -gt 6) { continue }
                $key = [int]$message.Payload[0]
                if ($DryRun) { Write-Host "key $key" }
                else { $inputError = [RemoPadInput]::KeyPress($key); if ($inputError -ne 0) { Write-RemoPadInputWarning $inputError } }
                Send-RemoPadAck -AcknowledgedSequence $message.Sequence -Target $remote -Session $sessionEntry.Crypto
            }
            10 {
                if ($message.Payload.Length -ne 4) { continue }
                $ticks = [BitConverter]::ToInt32($message.Payload, 0)
                if ($ticks -eq 0 -or $ticks -lt -127 -or $ticks -gt 127) { continue }
                $delta = $ticks * 120
                if ($DryRun) { Write-Host "vertical-wheel $delta" }
                else { $inputError = [RemoPadInput]::VerticalWheel($delta); if ($inputError -ne 0) { Write-RemoPadInputWarning $inputError } }
                Send-RemoPadAck -AcknowledgedSequence $message.Sequence -Target $remote -Session $sessionEntry.Crypto
            }
        }
    }
}
finally {
    if (-not $DryRun) { [void][RemoPadInput]::Button(1, $false); [void][RemoPadInput]::Button(2, $false) }
    foreach ($entry in @($sessions.Values)) { $entry.Crypto.Clear() }
    $udp.Close()
}
