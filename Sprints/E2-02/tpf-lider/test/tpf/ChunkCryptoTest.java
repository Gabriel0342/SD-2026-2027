package tpf;

import static tpf.TestSupport.check;
import static tpf.TestSupport.expectThrows;
import static tpf.TestSupport.section;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.AEADBadTagException;

/**
 * Tests of {@link ChunkCrypto}: seal and open, a changed ciphertext byte, a different
 * salt / nonce / key version, a different master key, swapped headers, and the HKDF
 * against the RFC 5869 test vector.
 */
final class ChunkCryptoTest {

    private static final SecureRandom RNG = new SecureRandom();
    private static final String SECRET = "texto muito secreto que nenhuma réplica pode ler";

    private ChunkCryptoTest() {
    }

    static void run() throws Exception {
        section("ChunkCryptoTest");

        byte[] masterKey = randomBytes(32);
        ChunkCrypto crypto = new ChunkCrypto(masterKey, 1);
        byte[] plaintext = new Chunk(List.of(new Registo(1, 0, "alice", "arquivo", SECRET))).serializar(4096);

        // ---- seal + open
        ChunkCrypto.Sealed sealed = crypto.seal(plaintext);
        check(Arrays.equals(crypto.open(sealed), plaintext), "seal followed by open returns the original plaintext");
        check(sealed.id.equals(ChunkCrypto.sha256Hex(sealed.ciphertext)),
                "chunkId is the SHA-256 (hex) of the ciphertext, tag included");
        check(sealed.ciphertext.length == plaintext.length + 16, "ciphertext = padded plaintext + 16-byte GCM tag");
        check(sealed.salt.length == 32 && sealed.nonce.length == 12 && sealed.keyVersion == 1,
                "32-byte salt, 12-byte nonce, keyVersion 1");
        check(!contains(sealed.ciphertext, SECRET.getBytes(StandardCharsets.UTF_8)),
                "the ciphertext does not contain the plaintext");

        ChunkCrypto.Sealed again = crypto.seal(plaintext);
        check(!Arrays.equals(sealed.salt, again.salt) && !Arrays.equals(sealed.nonce, again.nonce)
                        && !sealed.id.equals(again.id),
                "sealing the same plaintext twice gives a new salt, nonce and chunkId");

        // ---- a changed ciphertext byte
        byte[] flipped = sealed.ciphertext.clone();
        flipped[flipped.length / 2] ^= 0x01;
        GeneralSecurityException hashFailure = expectThrows(GeneralSecurityException.class,
                "one changed ciphertext byte makes open fail",
                () -> crypto.open(sealedWith(sealed.id, sealed.keyVersion, sealed.salt, sealed.nonce, flipped)));
        check(hashFailure.getMessage().startsWith("INTEGRIDADE"),
                "... and it is caught by the hash check, before decrypting");
        expectThrows(AEADBadTagException.class,
                "a changed byte with a recomputed (matching) id is caught by GCM authentication",
                () -> crypto.open(sealedWith(ChunkCrypto.sha256Hex(flipped), sealed.keyVersion,
                        sealed.salt, sealed.nonce, flipped)));

        // ---- different salt / nonce / keyVersion
        expectThrows(AEADBadTagException.class, "a different salt makes open fail",
                () -> crypto.open(sealedWith(sealed.id, sealed.keyVersion, randomBytes(32), sealed.nonce,
                        sealed.ciphertext)));
        expectThrows(AEADBadTagException.class, "a different nonce makes open fail",
                () -> crypto.open(sealedWith(sealed.id, sealed.keyVersion, sealed.salt, randomBytes(12),
                        sealed.ciphertext)));
        GeneralSecurityException versionFailure = expectThrows(GeneralSecurityException.class,
                "a different keyVersion makes open fail",
                () -> crypto.open(sealedWith(sealed.id, 2, sealed.salt, sealed.nonce, sealed.ciphertext)));
        check(versionFailure.getMessage().contains("key version"), "... at the key version check");
        ChunkCrypto cryptoV2 = new ChunkCrypto(masterKey, 2);
        expectThrows(AEADBadTagException.class,
                "keyVersion relabelled to one the leader accepts still fails (it is part of the aad)",
                () -> cryptoV2.open(sealedWith(sealed.id, 2, sealed.salt, sealed.nonce, sealed.ciphertext)));

        // ---- different master key
        ChunkCrypto otherKey = new ChunkCrypto(randomBytes(32), 1);
        expectThrows(AEADBadTagException.class, "a different master.key makes open fail",
                () -> otherKey.open(sealed));

        // ---- headers swapped between two chunks
        expectThrows(AEADBadTagException.class, "the salt/nonce of another chunk make open fail",
                () -> crypto.open(sealedWith(sealed.id, sealed.keyVersion, again.salt, again.nonce,
                        sealed.ciphertext)));

        expectThrows(IllegalArgumentException.class, "a master key that is not 32 bytes is rejected",
                () -> new ChunkCrypto(new byte[31], 1));

        // ---- building blocks
        HexFormat hex = HexFormat.of();
        byte[] okm = ChunkCrypto.hkdf(hex.parseHex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"),
                hex.parseHex("000102030405060708090a0b0c"), hex.parseHex("f0f1f2f3f4f5f6f7f8f9"));
        check(hex.formatHex(okm).equals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf"),
                "hkdf matches the first 32 bytes of RFC 5869 test case 1 (extract + one expand block)");

        byte[] aad = ChunkCrypto.aad(1, sealed.salt, sealed.nonce);
        check(aad.length == 6 + 4 + 32 + 12
                        && new String(aad, 0, 6, StandardCharsets.US_ASCII).equals("tpf-v1")
                        && aad[6] == 0 && aad[7] == 0 && aad[8] == 0 && aad[9] == 1
                        && Arrays.equals(Arrays.copyOfRange(aad, 10, 42), sealed.salt)
                        && Arrays.equals(Arrays.copyOfRange(aad, 42, 54), sealed.nonce),
                "aad = \"tpf-v1\" || keyVersion (4 bytes, big-endian) || salt || nonce");
    }

    /** A sealed chunk with the given fields (copied), to simulate tampering. */
    private static ChunkCrypto.Sealed sealedWith(String id, int keyVersion, byte[] salt, byte[] nonce,
                                                 byte[] ciphertext) {
        return new ChunkCrypto.Sealed(id, keyVersion, salt.clone(), nonce.clone(), ciphertext.clone());
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        RNG.nextBytes(bytes);
        return bytes;
    }

    static boolean contains(byte[] haystack, byte[] needle) {
        String h = new String(haystack, StandardCharsets.ISO_8859_1);
        return h.contains(new String(needle, StandardCharsets.ISO_8859_1));
    }
}
