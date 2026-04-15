package jtroop.pipeline.layers;

import jtroop.pipeline.Layer;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;

public final class EncryptionLayer implements Layer {

    private static final int GCM_TAG_LENGTH = 128;
    private static final int IV_LENGTH = 12;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public EncryptionLayer(SecretKey key) {
        this.key = key;
    }

    @Override
    public void encodeOutbound(ByteBuffer payload, ByteBuffer out) {
        try {
            var iv = new byte[IV_LENGTH];
            random.nextBytes(iv);

            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            var plain = new byte[payload.remaining()];
            payload.get(plain);
            var encrypted = cipher.doFinal(plain);

            out.put(iv);
            out.putInt(encrypted.length);
            out.put(encrypted);
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    @Override
    public ByteBuffer decodeInbound(ByteBuffer wire) {
        try {
            if (wire.remaining() < IV_LENGTH + 4) return null;

            var iv = new byte[IV_LENGTH];
            wire.get(iv);
            int encLen = wire.getInt();
            if (wire.remaining() < encLen) return null;

            var encrypted = new byte[encLen];
            wire.get(encrypted);

            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            var decrypted = cipher.doFinal(encrypted);

            return ByteBuffer.wrap(decrypted);
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }
}
