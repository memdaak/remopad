package com.remopad.client;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
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

    private static final byte VERSION = 3;
    private static final int HEADER_LENGTH = 24;
    private static final int TAG_LENGTH = 16;
    private static final int INNER_HEADER_LENGTH = 8;
    private static final int MAX_PAYLOAD_LENGTH = 2048;
    private static final int NONCE_LENGTH = 16;
    private static final int PUBLIC_KEY_LENGTH = 65;
    private static final int CLIENT_HELLO_UNSIGNED_LENGTH = 4 + NONCE_LENGTH
            + PUBLIC_KEY_LENGTH;
    private static final int CLIENT_HELLO_LENGTH = CLIENT_HELLO_UNSIGNED_LENGTH
            + TAG_LENGTH;
    private static final int SERVER_HELLO_UNSIGNED_LENGTH = 4 + NONCE_LENGTH
            + NONCE_LENGTH + PUBLIC_KEY_LENGTH;
    private static final int SERVER_HELLO_LENGTH = SERVER_HELLO_UNSIGNED_LENGTH
            + TAG_LENGTH;

    private static final byte[] MAGIC =
            "RME3".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CLIENT_HELLO_MAGIC =
            "RMH3".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SERVER_HELLO_MAGIC =
            "RMS3".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DISCOVERY_REQUEST =
            "RMD1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DISCOVERY_RESPONSE =
            "RMR1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SESSION_LABEL =
            "RemoPad v3 session".getBytes(StandardCharsets.US_ASCII);
    private static final SecureRandom RANDOM = new SecureRandom();

    private Protocol() {}

    static PairingContext createPairingContext(String keyText) {
        return new PairingContext(parseKey(keyText));
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
        if (!hasMagic(data, length, DISCOVERY_RESPONSE)) {
            return null;
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

    private static byte[] authenticate(byte[] key, byte[] data)
            throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] derive(byte[] key, String label)
            throws GeneralSecurityException {
        return authenticate(key, label.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] deriveSessionSeed(byte[] pairingKey,
                                            byte[] sharedHash,
                                            byte[] clientNonce,
                                            byte[] serverNonce)
            throws GeneralSecurityException {
        ByteBuffer input = ByteBuffer.allocate(SESSION_LABEL.length
                + sharedHash.length + clientNonce.length + serverNonce.length);
        input.put(SESSION_LABEL).put(sharedHash).put(clientNonce).put(serverNonce);
        return authenticate(pairingKey, input.array());
    }

    private static boolean hasMagic(byte[] data, int length, byte[] magic) {
        if (data == null || length < magic.length) {
            return false;
        }
        for (int index = 0; index < magic.length; index++) {
            if (data[index] != magic[index]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] encodePublicKey(ECPublicKey key) {
        byte[] encoded = new byte[PUBLIC_KEY_LENGTH];
        encoded[0] = 4;
        copyCoordinate(key.getW().getAffineX(), encoded, 1);
        copyCoordinate(key.getW().getAffineY(), encoded, 33);
        return encoded;
    }

    private static ECPublicKey decodePublicKey(byte[] encoded)
            throws GeneralSecurityException {
        if (encoded == null || encoded.length != PUBLIC_KEY_LENGTH
                || encoded[0] != 4) {
            throw new GeneralSecurityException("invalid P-256 public key");
        }
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec spec = parameters.getParameterSpec(ECParameterSpec.class);
        ECPoint point = new ECPoint(
                new BigInteger(1, Arrays.copyOfRange(encoded, 1, 33)),
                new BigInteger(1, Arrays.copyOfRange(encoded, 33, 65)));
        return (ECPublicKey) KeyFactory.getInstance("EC")
                .generatePublic(new ECPublicKeySpec(point, spec));
    }

    private static void copyCoordinate(BigInteger value,
                                       byte[] target, int offset) {
        byte[] source = value.toByteArray();
        int sourceOffset = Math.max(0, source.length - 32);
        int length = Math.min(32, source.length);
        System.arraycopy(source, sourceOffset, target,
                offset + 32 - length, length);
    }

    static final class PairingContext {
        private final byte[] pairingKey;

        PairingContext(byte[] pairingKey) {
            this.pairingKey = pairingKey.clone();
        }

        ClientHandshake beginHandshake() throws GeneralSecurityException {
            return new ClientHandshake(pairingKey);
        }
    }

    static final class ClientHandshake {
        private final byte[] pairingKey;
        private final byte[] clientNonce;
        private final KeyPair keyPair;
        private final byte[] request;

        ClientHandshake(byte[] pairingKey) throws GeneralSecurityException {
            this.pairingKey = pairingKey.clone();
            clientNonce = new byte[NONCE_LENGTH];
            RANDOM.nextBytes(clientNonce);
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
            keyPair = generator.generateKeyPair();

            ByteBuffer unsigned = ByteBuffer.allocate(
                    CLIENT_HELLO_UNSIGNED_LENGTH);
            unsigned.put(CLIENT_HELLO_MAGIC);
            unsigned.put(clientNonce);
            unsigned.put(encodePublicKey((ECPublicKey) keyPair.getPublic()));
            byte[] tag = authenticate(this.pairingKey, unsigned.array());
            ByteBuffer packet = ByteBuffer.allocate(CLIENT_HELLO_LENGTH);
            packet.put(unsigned.array()).put(tag, 0, TAG_LENGTH);
            request = packet.array();
        }

        byte[] request() {
            return request.clone();
        }

        CryptoContext finish(byte[] response, int length)
                throws GeneralSecurityException {
            if (response == null || length != SERVER_HELLO_LENGTH
                    || !hasMagic(response, length, SERVER_HELLO_MAGIC)) {
                throw new GeneralSecurityException("invalid server hello");
            }
            byte[] returnedClientNonce = Arrays.copyOfRange(response, 4,
                    4 + NONCE_LENGTH);
            if (!MessageDigest.isEqual(clientNonce, returnedClientNonce)) {
                throw new GeneralSecurityException("server hello nonce mismatch");
            }
            byte[] expectedTag = authenticate(pairingKey,
                    Arrays.copyOf(response, SERVER_HELLO_UNSIGNED_LENGTH));
            byte[] actualTag = Arrays.copyOfRange(response,
                    SERVER_HELLO_UNSIGNED_LENGTH, SERVER_HELLO_LENGTH);
            if (!MessageDigest.isEqual(
                    Arrays.copyOf(expectedTag, TAG_LENGTH), actualTag)) {
                throw new GeneralSecurityException("server hello authentication failed");
            }

            byte[] serverNonce = Arrays.copyOfRange(response,
                    4 + NONCE_LENGTH, 4 + NONCE_LENGTH * 2);
            byte[] serverPublic = Arrays.copyOfRange(response,
                    4 + NONCE_LENGTH * 2, SERVER_HELLO_UNSIGNED_LENGTH);
            KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
            agreement.init(keyPair.getPrivate());
            agreement.doPhase(decodePublicKey(serverPublic), true);
            byte[] sharedSecret = agreement.generateSecret();
            byte[] sharedHash = MessageDigest.getInstance("SHA-256")
                    .digest(sharedSecret);
            Arrays.fill(sharedSecret, (byte) 0);
            byte[] sessionSeed = deriveSessionSeed(pairingKey, sharedHash,
                    clientNonce, serverNonce);
            Arrays.fill(sharedHash, (byte) 0);
            CryptoContext context = new CryptoContext(sessionSeed);
            Arrays.fill(sessionSeed, (byte) 0);
            return context;
        }
    }

    static final class CryptoContext {
        private final byte[] encryptionKey;
        private final byte[] authenticationKey;

        CryptoContext(byte[] sessionSeed) throws GeneralSecurityException {
            encryptionKey = derive(sessionSeed, "RemoPad encryption v3");
            authenticationKey = derive(sessionSeed, "RemoPad authentication v3");
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
            if (packet == null || length < HEADER_LENGTH + 16 + TAG_LENGTH
                    || !hasMagic(packet, length, MAGIC)) {
                return null;
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
