[CmdletBinding()]
param(
    [ValidateRange(1, 65535)]
    [int]$Port = 51234,

    [ValidateRange(0, 999999)]
    [uint32]$PairingCode = 246810,

    [ValidateRange(0.1, 10.0)]
    [double]$PointerSensitivity = 1.35,

    [ValidateRange(0.1, 20.0)]
    [double]$WheelScale = 4.0,

    [switch]$DryRun,
    [switch]$SelfTest
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;

public static class RemoPadInput
{
    private const uint INPUT_MOUSE = 0;
    private const uint MOUSEEVENTF_MOVE = 0x0001;
    private const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
    private const uint MOUSEEVENTF_LEFTUP = 0x0004;
    private const uint MOUSEEVENTF_RIGHTDOWN = 0x0008;
    private const uint MOUSEEVENTF_RIGHTUP = 0x0010;
    private const uint MOUSEEVENTF_WHEEL = 0x0800;
    private const uint MOUSEEVENTF_HWHEEL = 0x01000;

    [StructLayout(LayoutKind.Sequential)]
    private struct MOUSEINPUT
    {
        public int dx;
        public int dy;
        public uint mouseData;
        public uint dwFlags;
        public uint time;
        public IntPtr dwExtraInfo;
    }

    [StructLayout(LayoutKind.Explicit)]
    private struct INPUTUNION
    {
        [FieldOffset(0)] public MOUSEINPUT mi;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct INPUT
    {
        public uint type;
        public INPUTUNION union;
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint SendInput(uint count, INPUT[] inputs, int size);

    private static void Send(uint flags, int dx, int dy, int data)
    {
        INPUT input = new INPUT();
        input.type = INPUT_MOUSE;
        input.union.mi.dx = dx;
        input.union.mi.dy = dy;
        input.union.mi.mouseData = unchecked((uint)data);
        input.union.mi.dwFlags = flags;
        INPUT[] inputs = new INPUT[] { input };

        if (SendInput(1, inputs, Marshal.SizeOf(typeof(INPUT))) != 1)
        {
            throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());
        }
    }

    public static void Move(int dx, int dy) { Send(MOUSEEVENTF_MOVE, dx, dy, 0); }
    public static void VerticalWheel(int amount) { Send(MOUSEEVENTF_WHEEL, 0, 0, amount); }
    public static void HorizontalWheel(int amount) { Send(MOUSEEVENTF_HWHEEL, 0, 0, amount); }

    public static void Button(int button, bool down)
    {
        uint flag;
        if (button == 1) flag = down ? MOUSEEVENTF_LEFTDOWN : MOUSEEVENTF_LEFTUP;
        else if (button == 2) flag = down ? MOUSEEVENTF_RIGHTDOWN : MOUSEEVENTF_RIGHTUP;
        else throw new ArgumentOutOfRangeException("button");
        Send(flag, 0, 0, 0);
    }
}
'@

function Read-RemoPadPacket {
    param(
        [byte[]]$Buffer,
        [uint32]$ExpectedPairingCode
    )

    if ($Buffer.Length -lt 16) { return $null }
    if ($Buffer[0] -ne 0x52 -or $Buffer[1] -ne 0x4D -or
        $Buffer[2] -ne 0x50 -or $Buffer[3] -ne 0x31) { return $null }
    if ($Buffer[4] -ne 1) { return $null }

    $messageType = [int]$Buffer[5]
    $payloadLength = [BitConverter]::ToUInt16($Buffer, 6)
    $sequence = [BitConverter]::ToUInt32($Buffer, 8)
    $receivedCode = [BitConverter]::ToUInt32($Buffer, 12)

    if ($receivedCode -ne $ExpectedPairingCode) { return $null }
    if ($Buffer.Length -ne 16 + $payloadLength) { return $null }

    switch ($messageType) {
        1 {
            if ($payloadLength -ne 8) { return $null }
            return [pscustomobject]@{
                Type = 1; Sequence = $sequence
                X = [BitConverter]::ToSingle($Buffer, 16)
                Y = [BitConverter]::ToSingle($Buffer, 20)
            }
        }
        2 {
            if ($payloadLength -ne 8) { return $null }
            return [pscustomobject]@{
                Type = 2; Sequence = $sequence
                X = [BitConverter]::ToSingle($Buffer, 16)
                Y = [BitConverter]::ToSingle($Buffer, 20)
            }
        }
        3 {
            if ($payloadLength -ne 4) { return $null }
            if ($Buffer[16] -lt 1 -or $Buffer[16] -gt 2) { return $null }
            if ($Buffer[17] -gt 1) { return $null }
            return [pscustomobject]@{
                Type = 3; Sequence = $sequence
                Button = [int]$Buffer[16]
                Down = ($Buffer[17] -eq 1)
            }
        }
        4 {
            if ($payloadLength -ne 0) { return $null }
            return [pscustomobject]@{ Type = 4; Sequence = $sequence }
        }
        default { return $null }
    }
}

function New-TestMovePacket {
    param([uint32]$Code, [uint32]$Sequence, [single]$X, [single]$Y)

    $packet = New-Object byte[] 24
    [Array]::Copy([Text.Encoding]::ASCII.GetBytes('RMP1'), 0, $packet, 0, 4)
    $packet[4] = 1
    $packet[5] = 1
    [Array]::Copy([BitConverter]::GetBytes([uint16]8), 0, $packet, 6, 2)
    [Array]::Copy([BitConverter]::GetBytes($Sequence), 0, $packet, 8, 4)
    [Array]::Copy([BitConverter]::GetBytes($Code), 0, $packet, 12, 4)
    [Array]::Copy([BitConverter]::GetBytes($X), 0, $packet, 16, 4)
    [Array]::Copy([BitConverter]::GetBytes($Y), 0, $packet, 20, 4)
    return $packet
}

if ($SelfTest) {
    $testPacket = New-TestMovePacket -Code 246810 -Sequence 42 -X 1.5 -Y -2.25
    $parsed = Read-RemoPadPacket -Buffer $testPacket -ExpectedPairingCode 246810
    if ($null -eq $parsed -or $parsed.Type -ne 1 -or $parsed.Sequence -ne 42 -or
        [Math]::Abs($parsed.X - 1.5) -gt 0.001 -or
        [Math]::Abs($parsed.Y + 2.25) -gt 0.001) {
        throw 'Protocol self-test failed.'
    }
    if ($null -ne (Read-RemoPadPacket -Buffer $testPacket -ExpectedPairingCode 123456)) {
        throw 'Pairing-code self-test failed.'
    }
    Write-Host 'RemoPad protocol self-test passed.' -ForegroundColor Green
    exit 0
}

function Get-LocalIPv4Addresses {
    try {
        return [Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces() |
            Where-Object { $_.OperationalStatus -eq 'Up' } |
            ForEach-Object { $_.GetIPProperties().UnicastAddresses } |
            ForEach-Object { $_.Address } |
            Where-Object {
                $_.AddressFamily -eq [Net.Sockets.AddressFamily]::InterNetwork -and
                -not [Net.IPAddress]::IsLoopback($_)
            } |
            Select-Object -Unique
    }
    catch { return @() }
}

$udp = New-Object Net.Sockets.UdpClient($Port)
$remote = New-Object Net.IPEndPoint([Net.IPAddress]::Any, 0)
$lastSequence = @{}
$moveResidualX = 0.0
$moveResidualY = 0.0
$scrollResidualX = 0.0
$scrollResidualY = 0.0

Write-Host 'RemoPad Windows Receiver' -ForegroundColor Cyan
Write-Host "Listening on UDP port $Port"
Write-Host ('Pairing code: {0:D6}' -f $PairingCode)
$addresses = @(Get-LocalIPv4Addresses)
if ($addresses.Count -gt 0) {
    Write-Host ('Local IPv4: ' + (($addresses | ForEach-Object { $_.ToString() }) -join ', '))
}
if ($DryRun) { Write-Host 'Dry-run mode: input injection is disabled.' -ForegroundColor Yellow }
Write-Host 'Press Ctrl+C to stop.'

try {
    while ($true) {
        $buffer = $udp.Receive([ref]$remote)
        $message = Read-RemoPadPacket -Buffer $buffer -ExpectedPairingCode $PairingCode
        if ($null -eq $message) { continue }

        if ($message.Type -eq 1 -or $message.Type -eq 2) {
            $sequenceKey = '{0}:{1}' -f $remote.Address, $message.Type
            if ($lastSequence.ContainsKey($sequenceKey) -and
                [uint32]$message.Sequence -le [uint32]$lastSequence[$sequenceKey]) {
                continue
            }
            $lastSequence[$sequenceKey] = [uint32]$message.Sequence
        }

        switch ($message.Type) {
            1 {
                $moveResidualX += [double]$message.X * $PointerSensitivity
                $moveResidualY += [double]$message.Y * $PointerSensitivity
                $emitX = [int][Math]::Truncate($moveResidualX)
                $emitY = [int][Math]::Truncate($moveResidualY)
                $moveResidualX -= $emitX
                $moveResidualY -= $emitY
                if ($emitX -ne 0 -or $emitY -ne 0) {
                    if ($DryRun) { Write-Host "move $emitX $emitY" }
                    else { [RemoPadInput]::Move($emitX, $emitY) }
                }
            }
            2 {
                $scrollResidualX += [double]$message.X * $WheelScale
                $scrollResidualY += -[double]$message.Y * $WheelScale
                $emitHorizontal = [int][Math]::Truncate($scrollResidualX)
                $emitVertical = [int][Math]::Truncate($scrollResidualY)
                $scrollResidualX -= $emitHorizontal
                $scrollResidualY -= $emitVertical
                if ($emitHorizontal -ne 0) {
                    if ($DryRun) { Write-Host "horizontal-scroll $emitHorizontal" }
                    else { [RemoPadInput]::HorizontalWheel($emitHorizontal) }
                }
                if ($emitVertical -ne 0) {
                    if ($DryRun) { Write-Host "vertical-scroll $emitVertical" }
                    else { [RemoPadInput]::VerticalWheel($emitVertical) }
                }
            }
            3 {
                if ($DryRun) { Write-Host "button $($message.Button) down=$($message.Down)" }
                else { [RemoPadInput]::Button($message.Button, $message.Down) }
            }
            4 {
                if ($DryRun) { Write-Host "ping from $($remote.Address)" }
            }
        }
    }
}
finally {
    $udp.Close()
}
