package tpf;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts and decrypts chunks with keys derived from the leader's master key (MK).
 *
 * salt    = 32 random bytes            (new for every chunk)
 * nonce   = 12 random bytes            (new for every chunk)
 * DEK     = HKDF-SHA256(MK, salt, info = "tpf-chunk-v1")
 * aad     = "tpf-v1" || keyVersion (4 bytes) || salt || nonce
 * cipher  = AES-256-GCM(DEK, nonce, padded plaintext, aad), 128-bit tag appended
 * chunkId = SHA-256(cipher, tag included), in hexadecimal
 */
public final class ChunkCrypto {

    /** A sealed chunk: these are the only fields that ever leave the leader. */
    public static final class Sealed {
        public final String id;          // SHA-256 of the ciphertext, in hexadecimal
        public final int keyVersion;
        public final byte[] salt;
        public final byte[] nonce;
        public final byte[] ciphertext;  // includes the 16-byte GCM tag at the end

        public Sealed(String id, int keyVersion, byte[] salt, byte[] nonce, byte[] ciphertext) {
            this.id = id;
            this.keyVersion = keyVersion;
            this.salt = salt;
            this.nonce = nonce;
            this.ciphertext = ciphertext;
        }
    }

    /** Size of the GCM authentication tag appended to every ciphertext, in bits. */
    static final int GCM_TAG_BITS = 128;
    static final int SALT_BYTES = 32;
    static final int NONCE_BYTES = 12;

    private static final byte[] INFO = "tpf-chunk-v1".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom RNG = new SecureRandom();

    private final byte[] masterKey;
    private final int keyVersion;

    public ChunkCrypto(byte[] masterKey, int keyVersion) {
        if (masterKey.length != 32) throw new IllegalArgumentException("the master key must have 32 bytes");
        this.masterKey = masterKey.clone();
        this.keyVersion = keyVersion;
    }

    /**
     * HKDF-SHA256 (RFC 5869) with a 32-byte output: the extract step followed by a
     * single expand block, i.e. T(1) = HMAC(PRK, info || 0x01).
     */
    static byte[] hkdf(byte[] ikm, byte[] salt, byte[] info) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);                      // extract
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        mac.update(info);
        mac.update((byte) 0x01);
        return mac.doFinal();                               // expand, first (and only) block
    }

    /**
     * Additional authenticated data: it is not encrypted but is covered by the GCM tag,
     * so changing the key version, the salt or the nonce of a chunk makes it fail to open.
     */
    static byte[] aad(int keyVersion, byte[] salt, byte[] nonce) {
        return ByteBuffer.allocate(6 + 4 + salt.length + nonce.length)
                .put("tpf-v1".getBytes(StandardCharsets.US_ASCII))
                .putInt(keyVersion).put(salt).put(nonce).array();
    }

    public static String sha256Hex(byte[] data) throws GeneralSecurityException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    /** Encrypts an (already padded) plaintext with a fresh salt and nonce. */
    public Sealed seal(byte[] plaintext) throws GeneralSecurityException {
        byte[] salt = new byte[SALT_BYTES];
        byte[] nonce = new byte[NONCE_BYTES];
        RNG.nextBytes(salt);
        RNG.nextBytes(nonce);
        byte[] dek = hkdf(masterKey, salt, INFO);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(dek, "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            c.updateAAD(aad(keyVersion, salt, nonce));
            byte[] ct = c.doFinal(plaintext);
            return new Sealed(sha256Hex(ct), keyVersion, salt, nonce, ct);
        } finally {
            Arrays.fill(dek, (byte) 0);   // do not leave the derived key lying around in memory
        }
    }

    /**
     * Decrypts a sealed chunk, checking, in this order: the hash against the id, the
     * key version, and the GCM authentication. Any failure throws an exception.
     */
    public byte[] open(Sealed s) throws GeneralSecurityException {
        if (!sha256Hex(s.ciphertext).equals(s.id))
            throw new GeneralSecurityException("INTEGRIDADE: the ciphertext hash does not match " + s.id);
        if (s.keyVersion != keyVersion)
            throw new GeneralSecurityException("unknown key version: " + s.keyVersion);
        byte[] dek = hkdf(masterKey, s.salt, INFO);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dek, "AES"), new GCMParameterSpec(GCM_TAG_BITS, s.nonce));
            c.updateAAD(aad(s.keyVersion, s.salt, s.nonce));
            return c.doFinal(s.ciphertext);   // AEADBadTagException if anything was changed
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }
}
