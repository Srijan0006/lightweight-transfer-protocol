package lwsp;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

final class LWSPCrypto {

    static final int NONCE_SIZE = 12;
    static final int TAG_BITS = 128;
    private static final SecureRandom RNG = new SecureRandom();

    private LWSPCrypto() {
    }

    static boolean enabled(String secret) {
        return secret != null && !secret.isBlank();
    }

    static byte[] encrypt(String secret, byte[] aad, byte[] plaintext) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        byte[] nonce = new byte[NONCE_SIZE];
        RNG.nextBytes(nonce);
        cipher.init(Cipher.ENCRYPT_MODE, key(secret), new GCMParameterSpec(TAG_BITS, nonce));
        if (aad != null && aad.length > 0) {
            cipher.updateAAD(aad);
        }
        byte[] ciphertext = cipher.doFinal(plaintext);
        byte[] out = new byte[nonce.length + ciphertext.length];
        System.arraycopy(nonce, 0, out, 0, nonce.length);
        System.arraycopy(ciphertext, 0, out, nonce.length, ciphertext.length);
        return out;
    }

    static byte[] decrypt(String secret, byte[] aad, byte[] encrypted) throws Exception {
        if (encrypted.length < NONCE_SIZE + 16) {
            throw new IllegalArgumentException("Encrypted payload too short");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        byte[] nonce = Arrays.copyOfRange(encrypted, 0, NONCE_SIZE);
        byte[] ciphertext = Arrays.copyOfRange(encrypted, NONCE_SIZE, encrypted.length);
        cipher.init(Cipher.DECRYPT_MODE, key(secret), new GCMParameterSpec(TAG_BITS, nonce));
        if (aad != null && aad.length > 0) {
            cipher.updateAAD(aad);
        }
        return cipher.doFinal(ciphertext);
    }

    private static SecretKeySpec key(String secret) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(secret.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(Arrays.copyOf(hash, 16), "AES");
    }
}
