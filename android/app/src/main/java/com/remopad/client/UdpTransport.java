package com.remopad.client;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.security.GeneralSecurityException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class UdpTransport {
    interface Listener {
        void onStatusChanged(String message, boolean connected, int latencyMs);
    }

    interface DiscoveryListener {
        void onReceiverFound(String host, int port, String name);
        void onDiscoveryFinished(boolean found);
    }

    private static final int HANDSHAKE_TIMEOUT_MS = 1800;
    private static final int HEARTBEAT_INTERVAL_MS = 2000;
    private static final int HEARTBEAT_TIMEOUT_MS = 5000;
    private static final int RELIABLE_RETRY_MS = 110;
    private static final int RELIABLE_MAX_ATTEMPTS = 7;

    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object stateLock = new Object();
    private final AtomicInteger sequence = new AtomicInteger();
    private final RelativeAccumulator pendingMove = new RelativeAccumulator();
    private final RelativeAccumulator pendingScroll = new RelativeAccumulator();
    private final ConcurrentLinkedQueue<ReliablePacket> reliableQueue =
            new ConcurrentLinkedQueue<>();
    private final AtomicReference<ReliablePacket> activeReliable =
            new AtomicReference<>();
    private final Semaphore senderWakeup = new Semaphore(0);

    private volatile DatagramSocket socket;
    private volatile Protocol.PairingContext pairing;
    private volatile Protocol.CryptoContext crypto;
    private volatile boolean connected;
    private volatile boolean connecting;
    private volatile boolean reconnectEnabled;
    private volatile int generation;
    private volatile String host;
    private volatile int port;
    private volatile String connectedLabel;
    private volatile int latencyMs = -1;
    private volatile int pendingPingSequence;
    private volatile long pendingPingSentAt;
    private volatile long nextHeartbeatAt;

    UdpTransport(Listener listener) {
        this.listener = listener;
    }

    boolean isActive() {
        return connected || connecting || reconnectEnabled;
    }

    boolean isConnected() {
        return connected;
    }

    void connect(String targetHost, int targetPort, String keyText) {
        final Protocol.PairingContext pairingContext;
        try {
            pairingContext = Protocol.createPairingContext(keyText);
        } catch (Exception error) {
            postStatus("配对密钥格式不正确", false, -1);
            return;
        }

        synchronized (stateLock) {
            reconnectEnabled = true;
            host = targetHost;
            port = targetPort;
            pairing = pairingContext;
            crypto = null;
        }
        beginConnection(false);
    }

    void disconnect() {
        synchronized (stateLock) {
            reconnectEnabled = false;
            generation++;
            connected = false;
            connecting = false;
            closeSocketLocked();
            clearQueuesLocked();
        }
        senderWakeup.release();
        postStatus("未连接", false, -1);
    }

    void sendMove(float dx, float dy) {
        if (!connected || !isFinite(dx) || !isFinite(dy)) {
            return;
        }
        if (pendingMove.add(dx, dy)) {
            senderWakeup.release();
        }
    }

    void sendScroll(float dx, float dy) {
        if (!connected || !isFinite(dx) || !isFinite(dy)) {
            return;
        }
        if (pendingScroll.add(dx, dy)) {
            senderWakeup.release();
        }
    }

    void sendButton(int button, boolean isDown) {
        if (!connected) {
            return;
        }
        int eventSequence = nextSequence();
        try {
            enqueueReliable(new ReliablePacket(eventSequence,
                    Protocol.button(crypto, eventSequence, button, isDown)));
        } catch (GeneralSecurityException error) {
            failCurrentConnection("加密按键事件失败");
        }
    }

    void sendClick(int button) {
        if (!connected) {
            return;
        }
        int eventSequence = nextSequence();
        try {
            enqueueReliable(new ReliablePacket(eventSequence,
                    Protocol.click(crypto, eventSequence, button)));
        } catch (GeneralSecurityException error) {
            failCurrentConnection("加密点击事件失败");
        }
    }

    void sendText(String text) {
        if (!connected || text == null || text.isEmpty()) {
            return;
        }
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + 256);
            if (end < text.length()
                    && Character.isHighSurrogate(text.charAt(end - 1))) {
                end--;
            }
            String part = text.substring(start, end);
            int eventSequence = nextSequence();
            try {
                enqueueReliable(new ReliablePacket(eventSequence,
                        Protocol.text(crypto, eventSequence, part)));
            } catch (GeneralSecurityException error) {
                failCurrentConnection("加密键盘文本失败");
                return;
            }
            start = end;
        }
    }

    void sendKeyPress(int key) {
        if (!connected) {
            return;
        }
        int eventSequence = nextSequence();
        try {
            enqueueReliable(new ReliablePacket(eventSequence,
                    Protocol.keyPress(crypto, eventSequence, key)));
        } catch (GeneralSecurityException error) {
            failCurrentConnection("加密键盘按键失败");
        }
    }

    void discover(int discoveryPort, DiscoveryListener discoveryListener) {
        Thread thread = new Thread(() -> {
            boolean found = false;
            DatagramSocket discoverySocket = null;
            try {
                discoverySocket = new DatagramSocket();
                discoverySocket.setBroadcast(true);
                discoverySocket.setSoTimeout(1200);
                byte[] request = Protocol.discoveryRequest();
                discoverySocket.send(new DatagramPacket(request, request.length,
                        InetAddress.getByName("255.255.255.255"), discoveryPort));

                long deadline = SystemClock.elapsedRealtime() + 1250;
                while (SystemClock.elapsedRealtime() < deadline) {
                    byte[] buffer = new byte[160];
                    DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                    try {
                        discoverySocket.receive(response);
                    } catch (SocketTimeoutException timeout) {
                        break;
                    }
                    Protocol.DiscoveryResult result =
                            Protocol.parseDiscoveryResponse(buffer, response.getLength());
                    if (result == null) {
                        continue;
                    }
                    found = true;
                    String address = response.getAddress().getHostAddress();
                    mainHandler.post(() -> discoveryListener.onReceiverFound(
                            address, result.port, result.name));
                    break;
                }
            } catch (Exception ignored) {
                // Discovery is optional; manual IP entry remains available.
            } finally {
                if (discoverySocket != null) {
                    discoverySocket.close();
                }
                boolean finalFound = found;
                mainHandler.post(() ->
                        discoveryListener.onDiscoveryFinished(finalFound));
            }
        }, "RemoPad-UDP-Discovery");
        thread.start();
    }

    private void beginConnection(boolean automatic) {
        final int currentGeneration;
        final String targetHost;
        final int targetPort;
        final Protocol.PairingContext pairingContext;

        synchronized (stateLock) {
            if (!reconnectEnabled) {
                return;
            }
            closeSocketLocked();
            clearQueuesLocked();
            generation++;
            currentGeneration = generation;
            connected = false;
            connecting = true;
            sequence.set(0);
            targetHost = host;
            targetPort = port;
            pairingContext = pairing;
        }

        postStatus(automatic ? "正在自动重连…" : "正在安全连接…",
                false, -1);

        Thread connector = new Thread(() -> {
            DatagramSocket candidate = null;
            long handshakeStartedAt = SystemClock.elapsedRealtime();
            try {
                InetAddress address = InetAddress.getByName(targetHost);
                candidate = new DatagramSocket();
                candidate.connect(new InetSocketAddress(address, targetPort));
                candidate.setSendBufferSize(16 * 1024);
                candidate.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
                try {
                    candidate.setTrafficClass(0x10);
                } catch (SocketException ignored) {
                    // Not every Android network stack exposes traffic class.
                }

                synchronized (stateLock) {
                    if (generation != currentGeneration || !connecting) {
                        candidate.close();
                        return;
                    }
                }

                Protocol.ClientHandshake handshake =
                        pairingContext.beginHandshake();
                byte[] hello = handshake.request();
                candidate.send(new DatagramPacket(hello, hello.length));
                byte[] helloResponse = new byte[256];
                DatagramPacket helloPacket = new DatagramPacket(
                        helloResponse, helloResponse.length);
                candidate.receive(helloPacket);
                Protocol.CryptoContext context = handshake.finish(
                        helloResponse, helloPacket.getLength());

                int pingSequence = nextSequence();
                byte[] ping = Protocol.ping(context, pingSequence);
                candidate.send(new DatagramPacket(ping, ping.length));

                Protocol.DecodedPacket response = receiveDecoded(candidate, context);
                if (response == null || response.type != Protocol.TYPE_PONG
                        || Protocol.intFromPayload(response) != pingSequence) {
                    throw new IOException("电脑端未返回有效的加密握手");
                }
                int handshakeLatency = (int) Math.min(9999,
                        SystemClock.elapsedRealtime() - handshakeStartedAt);
                candidate.setSoTimeout(500);

                synchronized (stateLock) {
                    if (generation != currentGeneration || !connecting) {
                        candidate.close();
                        return;
                    }
                    socket = candidate;
                    crypto = context;
                    connected = true;
                    connecting = false;
                    latencyMs = handshakeLatency;
                    pendingPingSequence = 0;
                    nextHeartbeatAt = SystemClock.elapsedRealtime()
                            + HEARTBEAT_INTERVAL_MS;
                    connectedLabel = "已连接 " + targetHost;
                }

                DatagramSocket activeSocket = candidate;
                new Thread(() -> senderLoop(activeSocket, context,
                        currentGeneration), "RemoPad-UDP-Sender").start();
                new Thread(() -> receiverLoop(activeSocket, context,
                        currentGeneration), "RemoPad-UDP-Receiver").start();
                postStatus(connectedLabel, true, handshakeLatency);
            } catch (SocketTimeoutException timeout) {
                if (candidate != null) {
                    candidate.close();
                }
                failConnection(currentGeneration, "电脑端未响应");
            } catch (Exception error) {
                if (candidate != null) {
                    candidate.close();
                }
                failConnection(currentGeneration,
                        "连接失败：" + safeMessage(error));
            }
        }, "RemoPad-UDP-Connect");
        connector.start();
    }

    private Protocol.DecodedPacket receiveDecoded(DatagramSocket source,
                                                    Protocol.CryptoContext context)
            throws IOException, GeneralSecurityException {
        byte[] responseBuffer = new byte[4096];
        DatagramPacket response = new DatagramPacket(
                responseBuffer, responseBuffer.length);
        source.receive(response);
        return context.decrypt(responseBuffer, response.getLength());
    }

    private void senderLoop(DatagramSocket senderSocket,
                            Protocol.CryptoContext context,
                            int senderGeneration) {
        while (isCurrentConnection(senderSocket, senderGeneration)) {
            long now = SystemClock.elapsedRealtime();
            byte[] packet = null;
            long waitMs = 250;

            ReliablePacket reliable = activeReliable.get();
            if (reliable == null) {
                reliable = reliableQueue.poll();
                if (reliable != null && !activeReliable.compareAndSet(null, reliable)) {
                    reliableQueue.offer(reliable);
                    reliable = activeReliable.get();
                }
            }

            if (reliable != null) {
                if (now >= reliable.nextAttemptAt) {
                    if (reliable.attempts >= RELIABLE_MAX_ATTEMPTS) {
                        if (activeReliable.compareAndSet(reliable, null)) {
                            postStatus(connectedLabel + " · 控制事件未确认",
                                    true, latencyMs);
                            senderWakeup.release();
                        }
                        continue;
                    }
                    reliable.attempts++;
                    reliable.nextAttemptAt = now + RELIABLE_RETRY_MS;
                    packet = reliable.packet;
                } else {
                    waitMs = Math.min(waitMs,
                            Math.max(1, reliable.nextAttemptAt - now));
                }
            }

            if (packet == null) {
                RelativeDelta move = pendingMove.drain();
                if (move != null) {
                    try {
                        packet = Protocol.move(context, nextSequence(),
                                move.dx, move.dy);
                    } catch (GeneralSecurityException error) {
                        failConnection(senderGeneration, "加密移动事件失败");
                        return;
                    }
                }
            }
            if (packet == null) {
                RelativeDelta scroll = pendingScroll.drain();
                if (scroll != null) {
                    try {
                        packet = Protocol.scroll(context, nextSequence(),
                                scroll.dx, scroll.dy);
                    } catch (GeneralSecurityException error) {
                        failConnection(senderGeneration, "加密滚动事件失败");
                        return;
                    }
                }
            }

            now = SystemClock.elapsedRealtime();
            if (pendingPingSequence != 0
                    && now - pendingPingSentAt > HEARTBEAT_TIMEOUT_MS) {
                failConnection(senderGeneration, "连接心跳超时");
                return;
            }
            if (packet == null && pendingPingSequence == 0
                    && now >= nextHeartbeatAt) {
                try {
                    int pingSequence = nextSequence();
                    packet = Protocol.ping(context, pingSequence);
                    pendingPingSequence = pingSequence;
                    pendingPingSentAt = now;
                    nextHeartbeatAt = now + HEARTBEAT_INTERVAL_MS;
                } catch (GeneralSecurityException error) {
                    failConnection(senderGeneration, "心跳加密失败");
                    return;
                }
            }

            if (packet == null) {
                try {
                    senderWakeup.tryAcquire(waitMs, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                }
                continue;
            }

            try {
                senderSocket.send(new DatagramPacket(packet, packet.length));
            } catch (IOException error) {
                if (isCurrentConnection(senderSocket, senderGeneration)) {
                    failConnection(senderGeneration,
                            "发送失败：" + safeMessage(error));
                }
                return;
            }
        }
    }

    private void receiverLoop(DatagramSocket receiverSocket,
                              Protocol.CryptoContext context,
                              int receiverGeneration) {
        while (isCurrentConnection(receiverSocket, receiverGeneration)) {
            try {
                Protocol.DecodedPacket response = receiveDecoded(
                        receiverSocket, context);
                if (response == null) {
                    continue;
                }
                if (response.type == Protocol.TYPE_ACK) {
                    int acknowledged = Protocol.intFromPayload(response);
                    ReliablePacket active = activeReliable.get();
                    if (active != null && active.sequence == acknowledged
                            && activeReliable.compareAndSet(active, null)) {
                        senderWakeup.release();
                    }
                } else if (response.type == Protocol.TYPE_PONG) {
                    int ping = Protocol.intFromPayload(response);
                    if (ping != -1 && ping == pendingPingSequence) {
                        int measured = (int) Math.min(9999,
                                SystemClock.elapsedRealtime() - pendingPingSentAt);
                        pendingPingSequence = 0;
                        latencyMs = measured;
                        postStatus(connectedLabel, true, measured);
                        senderWakeup.release();
                    }
                }
            } catch (SocketTimeoutException ignored) {
                // Sender loop owns the heartbeat timeout decision.
            } catch (Exception error) {
                if (isCurrentConnection(receiverSocket, receiverGeneration)) {
                    failConnection(receiverGeneration,
                            "接收失败：" + safeMessage(error));
                }
                return;
            }
        }
    }

    private void enqueueReliable(ReliablePacket packet) {
        reliableQueue.offer(packet);
        senderWakeup.release();
    }

    private int nextSequence() {
        return sequence.incrementAndGet();
    }

    private boolean isCurrentConnection(DatagramSocket expected,
                                        int expectedGeneration) {
        return connected && socket == expected && generation == expectedGeneration;
    }

    private void failCurrentConnection(String message) {
        failConnection(generation, message);
    }

    private void failConnection(int failedGeneration, String message) {
        final int retryGeneration;
        final boolean retry;
        synchronized (stateLock) {
            if (generation != failedGeneration) {
                return;
            }
            connected = false;
            connecting = false;
            closeSocketLocked();
            clearQueuesLocked();
            generation++;
            retryGeneration = generation;
            retry = reconnectEnabled;
        }
        senderWakeup.release();
        postStatus(retry ? message + "，即将重连…" : message,
                false, -1);
        if (retry) {
            mainHandler.postDelayed(() -> {
                synchronized (stateLock) {
                    if (!reconnectEnabled || generation != retryGeneration) {
                        return;
                    }
                }
                beginConnection(true);
            }, 1000);
        }
    }

    private void closeSocketLocked() {
        DatagramSocket activeSocket = socket;
        socket = null;
        if (activeSocket != null) {
            activeSocket.close();
        }
    }

    private void clearQueuesLocked() {
        pendingMove.clear();
        pendingScroll.clear();
        reliableQueue.clear();
        activeReliable.set(null);
        pendingPingSequence = 0;
        crypto = null;
        senderWakeup.drainPermits();
    }

    private void postStatus(String message, boolean isConnected,
                            int measuredLatencyMs) {
        mainHandler.post(() -> listener.onStatusChanged(
                message, isConnected, measuredLatencyMs));
    }

    private static String safeMessage(Exception error) {
        String message = error.getLocalizedMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message;
    }

    private static boolean isFinite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static final class RelativeAccumulator {
        private static final float MAX_PENDING = 8192f;
        private float dx;
        private float dy;
        private boolean pending;

        synchronized boolean add(float addedDx, float addedDy) {
            boolean wasEmpty = !pending;
            dx = clamp(dx + addedDx);
            dy = clamp(dy + addedDy);
            pending = true;
            return wasEmpty;
        }

        synchronized RelativeDelta drain() {
            if (!pending) {
                return null;
            }
            RelativeDelta result = new RelativeDelta(dx, dy);
            dx = 0f;
            dy = 0f;
            pending = false;
            return result;
        }

        synchronized void clear() {
            dx = 0f;
            dy = 0f;
            pending = false;
        }

        private static float clamp(float value) {
            return Math.max(-MAX_PENDING, Math.min(MAX_PENDING, value));
        }
    }

    private static final class RelativeDelta {
        final float dx;
        final float dy;

        RelativeDelta(float dx, float dy) {
            this.dx = dx;
            this.dy = dy;
        }
    }

    private static final class ReliablePacket {
        final int sequence;
        final byte[] packet;
        volatile int attempts;
        volatile long nextAttemptAt;

        ReliablePacket(int sequence, byte[] packet) {
            this.sequence = sequence;
            this.packet = packet;
        }
    }
}
