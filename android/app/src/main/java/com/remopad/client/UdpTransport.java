package com.remopad.client;

import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class UdpTransport {
    interface Listener {
        void onStatusChanged(String message, boolean connected);
    }

    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object stateLock = new Object();
    private final AtomicInteger sequence = new AtomicInteger();
    private final AtomicReference<byte[]> latestMove = new AtomicReference<>();
    private final AtomicReference<byte[]> latestScroll = new AtomicReference<>();
    private final ConcurrentLinkedQueue<byte[]> discretePackets =
            new ConcurrentLinkedQueue<>();
    private final Semaphore senderWakeup = new Semaphore(0);

    private volatile DatagramSocket socket;
    private volatile boolean connected;
    private volatile boolean connecting;
    private volatile int pairingCode;
    private int generation;

    UdpTransport(Listener listener) {
        this.listener = listener;
    }

    boolean isActive() {
        return connected || connecting;
    }

    void connect(String host, int port, int code) {
        final int currentGeneration;

        synchronized (stateLock) {
            closeSocketLocked();
            clearQueuesLocked();
            generation++;
            currentGeneration = generation;
            pairingCode = code;
            connected = false;
            connecting = true;
            sequence.set(0);
        }

        postStatus("正在连接…", false);

        Thread connector = new Thread(() -> {
            DatagramSocket candidate = null;
            try {
                InetAddress address = InetAddress.getByName(host);
                candidate = new DatagramSocket();
                candidate.connect(new InetSocketAddress(address, port));
                candidate.setSendBufferSize(16 * 1024);
                try {
                    candidate.setTrafficClass(0x10);
                } catch (SocketException ignored) {
                    // Not all Android network stacks expose traffic class.
                }

                synchronized (stateLock) {
                    if (generation != currentGeneration || !connecting) {
                        candidate.close();
                        return;
                    }
                    socket = candidate;
                    connected = true;
                    connecting = false;
                }

                DatagramSocket senderSocket = candidate;
                Thread sender = new Thread(
                        () -> senderLoop(senderSocket, currentGeneration),
                        "RemoPad-UDP-Sender"
                );
                sender.start();

                postStatus("已连接到 " + host + ":" + port, true);
                enqueueDiscrete(Protocol.ping(nextSequence(), pairingCode), false);
            } catch (Exception error) {
                if (candidate != null) {
                    candidate.close();
                }
                failConnection(currentGeneration, "连接失败：" + safeMessage(error));
            }
        }, "RemoPad-UDP-Connect");
        connector.start();
    }

    void disconnect() {
        synchronized (stateLock) {
            generation++;
            connected = false;
            connecting = false;
            closeSocketLocked();
            clearQueuesLocked();
        }
        senderWakeup.release();
        postStatus("未连接", false);
    }

    void sendMove(float dx, float dy) {
        if (!connected || !isFinite(dx) || !isFinite(dy)) {
            return;
        }
        byte[] packet = Protocol.move(nextSequence(), pairingCode, dx, dy);
        if (latestMove.getAndSet(packet) == null) {
            senderWakeup.release();
        }
    }

    void sendScroll(float dx, float dy) {
        if (!connected || !isFinite(dx) || !isFinite(dy)) {
            return;
        }
        byte[] packet = Protocol.scroll(nextSequence(), pairingCode, dx, dy);
        if (latestScroll.getAndSet(packet) == null) {
            senderWakeup.release();
        }
    }

    void sendButton(int button, boolean isDown) {
        if (!connected) {
            return;
        }
        byte[] packet = Protocol.button(nextSequence(), pairingCode, button, isDown);
        enqueueDiscrete(packet, true);
    }

    void sendClick(int button) {
        sendButton(button, true);
        sendButton(button, false);
    }

    private void enqueueDiscrete(byte[] packet, boolean duplicate) {
        if (!connected) {
            return;
        }
        discretePackets.offer(packet);
        if (duplicate) {
            discretePackets.offer(packet);
        }
        senderWakeup.release();
    }

    private int nextSequence() {
        return sequence.incrementAndGet();
    }

    private void senderLoop(DatagramSocket senderSocket, int senderGeneration) {
        while (isCurrentConnection(senderSocket, senderGeneration)) {
            byte[] packet = discretePackets.poll();
            if (packet == null) {
                packet = latestMove.getAndSet(null);
            }
            if (packet == null) {
                packet = latestScroll.getAndSet(null);
            }

            if (packet == null) {
                try {
                    senderWakeup.tryAcquire(250, TimeUnit.MILLISECONDS);
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
                    failConnection(senderGeneration, "发送失败：" + safeMessage(error));
                }
                return;
            }
        }
    }

    private boolean isCurrentConnection(DatagramSocket expected, int expectedGeneration) {
        return connected && socket == expected && generation == expectedGeneration;
    }

    private void failConnection(int failedGeneration, String message) {
        synchronized (stateLock) {
            if (generation != failedGeneration) {
                return;
            }
            generation++;
            connected = false;
            connecting = false;
            closeSocketLocked();
            clearQueuesLocked();
        }
        senderWakeup.release();
        postStatus(message, false);
    }

    private void closeSocketLocked() {
        DatagramSocket activeSocket = socket;
        socket = null;
        if (activeSocket != null) {
            activeSocket.close();
        }
    }

    private void clearQueuesLocked() {
        latestMove.set(null);
        latestScroll.set(null);
        discretePackets.clear();
        senderWakeup.drainPermits();
    }

    private void postStatus(String message, boolean isConnected) {
        mainHandler.post(() -> listener.onStatusChanged(message, isConnected));
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
}
