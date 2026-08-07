package com.remopad.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class Protocol {
    static final int DEFAULT_PORT = 51234;

    static final int TYPE_MOVE = 1;
    static final int TYPE_SCROLL = 2;
    static final int TYPE_BUTTON = 3;
    static final int TYPE_PING = 4;
    static final int TYPE_TEXT = 5;
    static final int TYPE_KEY = 6;
    static final int TYPE_ACK = 7;
    static final int TYPE_PONG = 8;
    static final int TYPE_CLICK = 9;

    static final int KEY_BACKSPACE = 1;
    static final int KEY_ENTER = 2;
    static final int KEY_TAB = 3;
    static final int KEY_ESCAPE = 4;

    private static final byte VERSION = 2;
    private static final int HEADER_LENGTH = 24;
    private static final int TAG_LENGTH = 16;
    private static final int INNER_HEADER_LENGTH = 8;
    private static final int MAX_PAYLOAD_LENGTH = 2048;
    private static final byte[] MAGIC = "RME2".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DISCOVERY_REQUEST =
            "RMD1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DISCOVERY_RESPONSE =
            "RMR1".getBytes(StandardCharsets.US_ASCII);
    private static final SecureRandom RANDOM = new SecureRandom();

    private Protocol() {}

    static CryptoContext createCryptoContext(String keyText)
            throws GeneralSecurityException {
        return new CryptoContext(parseKey(keyText));
    }

    static boolean isValidPairingKey(String keyText) {
        try {
            parseKey(keyText);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    static byte[] move(CryptoContext crypto, int sequence, float dx, float dy)
            throws GeneralSecurityException {
        ByteBuffer payload = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        payload.putFloat(dx).putFloat(dy);
        return crypto.encrypt(TYPE_MOVE, sequence, payload.array());
    }

    static byte[] scroll(CryptoContext crypto, int sequence, float dx, float dy)
            throws GeneralSecurityException {
        ByteBuffer payload = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        payload.putFloat(dx).putFloat(dy);
        return crypto.encrypt(TYPE_SCROLL, sequence, payload.array());
    }

    static byte[] button(CryptoContext crypto, int sequence,
                         int button, boolean isDown)
            throws GeneralSecurityException {
        if (button != 1 && button != 2) {
            throw new IllegalArgumentException("button must be 1 or 2");
        }
        return crypto.encrypt(TYPE_BUTTON, sequence,
                new byte[] {(byte) button, (byte) (isDown ? 1 : 0), 0, 0});
    }

    static byte[] click(CryptoContext crypto, int sequence, int button)
            throws GeneralSecurityException {
        if (button != 1 && button != 2) {
            throw new IllegalArgumentException("button must be 1 or 2");
        }
        return crypto.encrypt(TYPE_CLICK, sequence,
                new byte[] {(byte) button, 0, 0, 0});
    }

    static byte[] ping(CryptoContext crypto, int sequence)
            throws GeneralSecurityException {
        return crypto.encrypt(TYPE_PING, sequence, new byte[0]);
    }

    static byte[] pong(CryptoContext crypto, int sequence, int pingSequence)
            throws GeneralSecurityException {
        return crypto.encrypt(TYPE_PONG, sequence, intPayload(pingSequence));
    }

    static byte[] acknowledge(CryptoContext crypto, int sequence,
                              int acknowledgedSequence)
            throws GeneralSecurityException {
        return crypto.encrypt(TYPE_ACK, sequence, intPayload(acknowledgedSequence));
    }

    static byte[] text(CryptoContext crypto, int sequence, String value)
            throws GeneralSecurityException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length == 0 || encoded.length > MAX_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException("text payload is empty or too large");
        }
        return crypto.encrypt(TYPE_TEXT, sequence, encoded);
    }

    static byte[] keyPress(CryptoContext crypto, int sequence, int key)
            throws GeneralSecurityException {
        if (key < KEY_BACKSPACE || key > KEY_ESCAPE) {
            throw new IllegalArgumentException("unknown key");
        }
        return crypto.encrypt(TYPE_KEY, sequence,
                new byte[] {(byte) key, 0, 0, 0});
    }

    static byte[] discoveryRequest() {
        return DISCOVERY_REQUEST.clone();
    }

    static DiscoveryResult parseDiscoveryResponse(byte[] data, int length) {
        if (data == null || length < 6 || length > 128) {
            return null;
        }
        for (int index = 0; index < DISCOVERY_RESPONSE.length; index++) {
            if (data[index] != DISCOVERY_RESPONSE[index]) {
                return null;
            }
        }
        ByteBuffer buffer = ByteBuffer.wrap(data, 4, length - 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        int port = buffer.getShort() & 0xffff;
        if (port < 1 || !buffer.hasRemaining()) {
            return null;
        }
        byte[] nameBytes = new byte[buffer.remaining()];
        buffer.get(nameBytes);
        String name = new String(nameBytes, StandardCharsets.UTF_8).trim();
        return name.isEmpty() ? null : new DiscoveryResult(port, name);
    }

    static int intFromPayload(DecodedPacket packet) {
        if (packet.payload.length != 4) {
            return -1;
        }
        return ByteBuffer.wrap(packet.payload)
                .order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static byte[] intPayload(int value) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(value).array();
    }

    private static byte[] parseKey(String keyText) {
        if (keyText == null) {
            throw new IllegalArgumentException("pairing key is missing");
        }
        String normalized = keyText.replace("-", "")
                .replace(" ", "").trim();
        if (normalized.length() != 64) {
            throw new IllegalArgumentException("pairing key must contain 64 hex digits");
        }
        byte[] key = new byte[32];
        for (int index = 0; index < key.length; index++) {
            int high = Character.digit(normalized.charAt(index * 2), 16);
            int low = Character.digit(normalized.charAt(index * 2 + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("pairing key is not hexadecimal");
            }
            key[index] = (byte) ((high << 4) | low);
        }
        return key;
    }

    static final class CryptoContext {
        private final byte[] encryptionKey;
        private final byte[] authenticationKey;

        CryptoContext(byte[] masterKey) throws GeneralSecurityException {
            encryptionKey = derive(masterKey, "RemoPad encryption v2");
            authenticationKey = derive(masterKey, "RemoPad authentication v2");
        }

        byte[] encrypt(int type, int sequence, byte[] payload)
                throws GeneralSecurityException {
            if (payload == null || payload.length > MAX_PAYLOAD_LENGTH) {
                throw new IllegalArgumentException("invalid payload length");
            }

            ByteBuffer inner = ByteBuffer.allocate(INNER_HEADER_LENGTH + payload.length)
                    .order(ByteOrder.LITTLE_ENDIAN);
            inner.put((byte) type);
            inner.put((byte) 0);
            inner.putShort((short) payload.length);
            inner.putInt(sequence);
            inner.put(payload);

            byte[] iv = new byte[16];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(encryptionKey, "AES"),
                    new IvParameterSpec(iv));
            byte[] encrypted = cipher.doFinal(inner.array());

            ByteBuffer packet = ByteBuffer.allocate(
                    HEADER_LENGTH + encrypted.length + TAG_LENGTH)
                    .order(ByteOrder.LITTLE_ENDIAN);
            packet.put(MAGIC);
            packet.put(VERSION);
            packet.put((byte) 0);
            packet.putShort((short) encrypted.length);
            packet.put(iv);
            packet.put(encrypted);

            byte[] withoutTag = Arrays.copyOf(packet.array(),
                    HEADER_LENGTH + encrypted.length);
            byte[] tag = authenticate(authenticationKey, withoutTag);
            packet.put(tag, 0, TAG_LENGTH);
            return packet.array();
        }

        DecodedPacket decrypt(byte[] packet, int length)
                throws GeneralSecurityException {
            if (packet == null || length < HEADER_LENGTH + 16 + TAG_LENGTH) {
                return null;
            }
            for (int index = 0; index < MAGIC.length; index++) {
                if (packet[index] != MAGIC[index]) {
                    return null;
                }
            }
            if (packet[4] != VERSION || packet[5] != 0) {
                return null;
            }

            ByteBuffer outer = ByteBuffer.wrap(packet, 0, length)
                    .order(ByteOrder.LITTLE_ENDIAN);
            int encryptedLength = outer.getShort(6) & 0xffff;
            if (encryptedLength < 16 || encryptedLength % 16 != 0
                    || length != HEADER_LENGTH + encryptedLength + TAG_LENGTH) {
                return null;
            }

            int tagOffset = HEADER_LENGTH + encryptedLength;
            byte[] expectedTag = authenticate(authenticationKey,
                    Arrays.copyOf(packet, tagOffset));
            byte[] actualTag = Arrays.copyOfRange(packet,
                    tagOffset, tagOffset + TAG_LENGTH);
            if (!MessageDigest.isEqual(
                    Arrays.copyOf(expectedTag, TAG_LENGTH), actualTag)) {
                return null;
            }

            byte[] iv = Arrays.copyOfRange(packet, 8, HEADER_LENGTH);
            byte[] encrypted = Arrays.copyOfRange(packet,
                    HEADER_LENGTH, tagOffset);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(encryptionKey, "AES"),
                    new IvParameterSpec(iv));
            byte[] innerBytes = cipher.doFinal(encrypted);
            if (innerBytes.length < INNER_HEADER_LENGTH) {
                return null;
            }

            ByteBuffer inner = ByteBuffer.wrap(innerBytes)
                    .order(ByteOrder.LITTLE_ENDIAN);
            int type = inner.get() & 0xff;
            if (inner.get() != 0) {
                return null;
            }
            int payloadLength = inner.getShort() & 0xffff;
            int sequence = inner.getInt();
            if (payloadLength != inner.remaining()
                    || payloadLength > MAX_PAYLOAD_LENGTH) {
                return null;
            }
            byte[] payload = new byte[payloadLength];
            inner.get(payload);
            return new DecodedPacket(type, sequence, payload);
        }

        private static byte[] derive(byte[] masterKey, String label)
                throws GeneralSecurityException {
            return authenticate(masterKey, label.getBytes(StandardCharsets.US_ASCII));
        }

        private static byte[] authenticate(byte[] key, byte[] data)
                throws GeneralSecurityException {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        }
    }

    static final class DecodedPacket {
        final int type;
        final int sequence;
        final byte[] payload;

        DecodedPacket(int type, int sequence, byte[] payload) {
            this.type = type;
            this.sequence = sequence;
            this.payload = payload;
        }
    }

    static final class DiscoveryResult {
        final int port;
        final String name;

        DiscoveryResult(int port, String name) {
            this.port = port;
            this.name = name;
        }
    }
}