package com.remopad.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

final class Protocol {
    static final int DEFAULT_PORT = 51234;
    static final int DEFAULT_PAIRING_CODE = 246810;

    private static final byte VERSION = 1;
    private static final byte TYPE_MOVE = 1;
    private static final byte TYPE_SCROLL = 2;
    private static final byte TYPE_BUTTON = 3;
    private static final byte TYPE_PING = 4;
    private static final byte[] MAGIC = "RMP1".getBytes(StandardCharsets.US_ASCII);

    private Protocol() {}

    static byte[] move(int sequence, int pairingCode, float dx, float dy) {
        ByteBuffer packet = packet(TYPE_MOVE, 8, sequence, pairingCode);
        packet.putFloat(dx);
        packet.putFloat(dy);
        return packet.array();
    }

    static byte[] scroll(int sequence, int pairingCode, float dx, float dy) {
        ByteBuffer packet = packet(TYPE_SCROLL, 8, sequence, pairingCode);
        packet.putFloat(dx);
        packet.putFloat(dy);
        return packet.array();
    }

    static byte[] button(int sequence, int pairingCode, int button, boolean isDown) {
        if (button != 1 && button != 2) {
            throw new IllegalArgumentException("button must be 1 or 2");
        }

        ByteBuffer packet = packet(TYPE_BUTTON, 4, sequence, pairingCode);
        packet.put((byte) button);
        packet.put((byte) (isDown ? 1 : 0));
        packet.putShort((short) 0);
        return packet.array();
    }

    static byte[] ping(int sequence, int pairingCode) {
        return packet(TYPE_PING, 0, sequence, pairingCode).array();
    }

    private static ByteBuffer packet(byte type, int payloadLength,
                                     int sequence, int pairingCode) {
        if (pairingCode < 0 || pairingCode > 999_999) {
            throw new IllegalArgumentException("invalid pairing code");
        }

        ByteBuffer packet = ByteBuffer.allocate(16 + payloadLength)
                .order(ByteOrder.LITTLE_ENDIAN);
        packet.put(MAGIC);
        packet.put(VERSION);
        packet.put(type);
        packet.putShort((short) payloadLength);
        packet.putInt(sequence);
        packet.putInt(pairingCode);
        return packet;
    }
}
