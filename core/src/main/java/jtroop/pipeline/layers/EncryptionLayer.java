package jtroop.pipeline.layers;

import jtroop.ProtocolException;
import jtroop.pipeline.Layer;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AES-GCM encryption layer with per-layer IV uniqueness and zero hot-path allocation.
 *
 * <h2>Wire format</h2>
 * <pre>{@code
 *   [12-byte IV][4-byte ciphertext length][ciphertext || 16-byte GCM tag]
 * }</pre>
 *
 * <h2>IV construction (NIST SP 800-38D §8.2.1 deterministic)</h2>
 * <ul>
 *   <li>bytes 0..3 — fixed random salt chosen once per layer instance</li>
 *   <li>bytes 4..11 — 64-bit message counter (big-endian), strictly increasing</li>
 * </ul>
 * The counter is an {@link AtomicLong}, so every encode across every thread
 * observes a unique IV for this layer's key. 2^64 encodes before wrap —
 * effectively unbounded. Two layers constructed with the same key MUST use a
 * different salt; a 32-bit random salt gives a birthday-collision horizon of
 * ~2^16 instances, which is fine for the per-connection model
 * ({@link jtroop.pipeline.Layers} docs).
 *
 * <h2>Zero-alloc strategy</h2>
 * <ul>
 *   <li>{@link Cipher} instances (one per direction) are held in a
 *       {@link ThreadLocal}. {@code Cipher.getInstance} allocates ~1 KB of
 *       provider-lookup state — we pay that once per thread and reinitialise
 *       per call.</li>
 *   <li>Scratch plaintext / ciphertext {@code byte[]} arrays live in the same
 *       {@link ThreadLocal} and grow on demand.</li>
 *   <li>Heap {@link ByteBuffer} arguments are accessed via {@link ByteBuffer#array()}
 *       so {@link Cipher#doFinal(byte[], int, int, byte[], int)} reads and writes
 *       the backing arrays directly — no intermediate byte arrays.</li>
 *   <li>{@link GCMParameterSpec} is recreated each call (constructor clones its
 *       IV internally), but it's a tiny object that C2 can reliably
 *       scalar-replace when {@code Cipher.init} is inlined; otherwise it's ~32 B.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * The layer is thread-safe: ciphers are per-thread, the counter is atomic, the
 * salt is final. Per CLAUDE.md rule #7 this layer avoids per-call lambdas,
 * boxing, and collection wrappers.
 */
public final class EncryptionLayer implements Layer {

    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_TAG_BYTES = GCM_TAG_BITS / 8;
    private static final int IV_LENGTH = 12;
    private static final int LENGTH_PREFIX_BYTES = 4;

    private final SecretKey key;
    private final byte[] ivSalt = new byte[4];
    private final AtomicLong sendCounter = new AtomicLong();

    private final ThreadLocal<State> state = ThreadLocal.withInitial(State::new);

    public EncryptionLayer(SecretKey key) {
        this.key = key;
        new SecureRandom().nextBytes(ivSalt);
    }

    @Override
    public void encodeOutbound(Layer.Context ctx, ByteBuffer payload, ByteBuffer out) {
        var s = state.get();
        int plainLen = payload.remaining();
        int cipherLen = plainLen + GCM_TAG_BYTES;

        // Build a unique IV: 4-byte salt || 8-byte counter.
        // AtomicLong guarantees no two encodes (across threads) share a counter.
        long counter = sendCounter.getAndIncrement();
        byte[] iv = s.iv;
        iv[0] = ivSalt[0]; iv[1] = ivSalt[1]; iv[2] = ivSalt[2]; iv[3] = ivSalt[3];
        iv[4]  = (byte) (counter >>> 56);
        iv[5]  = (byte) (counter >>> 48);
        iv[6]  = (byte) (counter >>> 40);
        iv[7]  = (byte) (counter >>> 32);
        iv[8]  = (byte) (counter >>> 24);
        iv[9]  = (byte) (counter >>> 16);
        iv[10] = (byte) (counter >>> 8);
        iv[11] = (byte)  counter;

        try {
            Cipher cipher = s.encryptCipher(key, iv);

            // Use direct array access to avoid an intermediate byte[] copy of the plaintext.
            // Both buffers are heap-backed in Pipeline (ByteBuffer.allocate), but fall back
            // to the allocating path if a caller hands us a direct buffer.
            if (payload.hasArray() && out.hasArray()) {
                byte[] in = payload.array();
                int inOff = payload.arrayOffset() + payload.position();

                // Reserve IV + length prefix first so the ciphertext writes directly after.
                out.put(iv);
                out.putInt(cipherLen);

                byte[] dst = out.array();
                int dstOff = out.arrayOffset() + out.position();

                int written = cipher.doFinal(in, inOff, plainLen, dst, dstOff);
                out.position(out.position() + written);
                payload.position(payload.position() + plainLen);
            } else {
                // Cold path — direct ByteBuffers. Still reuses the cached Cipher.
                byte[] scratch = s.ensurePlain(plainLen);
                payload.get(scratch, 0, plainLen);
                byte[] cipherBytes = s.ensureCipher(cipherLen);
                int written = cipher.doFinal(scratch, 0, plainLen, cipherBytes, 0);
                out.put(iv);
                out.putInt(cipherLen);
                out.put(cipherBytes, 0, written);
            }
        } catch (Exception e) {
            throw new ProtocolException("Encryption failed", e);
        }
    }

    @Override
    public ByteBuffer decodeInbound(Layer.Context ctx, ByteBuffer wire) {
        if (wire.remaining() < IV_LENGTH + LENGTH_PREFIX_BYTES) return null;

        // Peek without consuming so a short read leaves wire untouched.
        int start = wire.position();
        int encLen = wire.getInt(start + IV_LENGTH);
        if (encLen < GCM_TAG_BYTES || encLen > FramingLayer.MAX_FRAME_LENGTH) {
            throw new ProtocolException("Invalid encrypted frame length: " + encLen);
        }
        if (wire.remaining() - IV_LENGTH - LENGTH_PREFIX_BYTES < encLen) return null;

        var s = state.get();
        byte[] iv = s.iv;
        for (int i = 0; i < IV_LENGTH; i++) iv[i] = wire.get(start + i);

        int cipherPos = start + IV_LENGTH + LENGTH_PREFIX_BYTES;
        int plainMax = encLen; // plaintext length is encLen - TAG, but doFinal returns exact

        try {
            Cipher cipher = s.decryptCipher(key, iv);

            if (wire.hasArray()) {
                byte[] src = wire.array();
                int srcOff = wire.arrayOffset() + cipherPos;
                byte[] dst = s.ensurePlain(plainMax);
                int written = cipher.doFinal(src, srcOff, encLen, dst, 0);
                wire.position(cipherPos + encLen);

                // Reuse the thread-local output ByteBuffer view. Re-wrap only if
                // the underlying scratch array grew (ensurePlain may reallocate).
                ByteBuffer view = s.plainView;
                if (view == null || view.array() != dst) {
                    view = ByteBuffer.wrap(dst);
                    s.plainView = view;
                }
                view.limit(dst.length).position(0).limit(written);
                return view;
            } else {
                // Direct wire buffer — fall back to a copy.
                byte[] cipherBytes = s.ensureCipher(encLen);
                int savedPos = wire.position();
                wire.position(cipherPos);
                wire.get(cipherBytes, 0, encLen);
                wire.position(savedPos); // restored, then advanced below
                byte[] dst = s.ensurePlain(plainMax);
                int written = cipher.doFinal(cipherBytes, 0, encLen, dst, 0);
                wire.position(cipherPos + encLen);
                ByteBuffer view = s.plainView;
                if (view == null || view.array() != dst) {
                    view = ByteBuffer.wrap(dst);
                    s.plainView = view;
                }
                view.limit(dst.length).position(0).limit(written);
                return view;
            }
        } catch (Exception e) {
            throw new ProtocolException("Decryption failed", e);
        }
    }

    /** Per-thread scratch: ciphers (init on demand), IV buffer, plaintext/ciphertext arrays. */
    private static final class State {
        final byte[] iv = new byte[IV_LENGTH];
        Cipher enc;
        Cipher dec;
        byte[] plain = new byte[1024];
        byte[] cipher = new byte[1024];
        ByteBuffer plainView; // wraps `plain`; re-wrapped only if `plain` is reallocated

        Cipher encryptCipher(SecretKey key, byte[] iv) throws Exception {
            Cipher c = enc;
            if (c == null) {
                c = Cipher.getInstance("AES/GCM/NoPadding");
                enc = c;
            }
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return c;
        }

        Cipher decryptCipher(SecretKey key, byte[] iv) throws Exception {
            Cipher c = dec;
            if (c == null) {
                c = Cipher.getInstance("AES/GCM/NoPadding");
                dec = c;
            }
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return c;
        }

        byte[] ensurePlain(int n) {
            if (plain.length < n) plain = new byte[Math.max(n, plain.length * 2)];
            return plain;
        }

        byte[] ensureCipher(int n) {
            if (cipher.length < n) cipher = new byte[Math.max(n, cipher.length * 2)];
            return cipher;
        }
    }
}
