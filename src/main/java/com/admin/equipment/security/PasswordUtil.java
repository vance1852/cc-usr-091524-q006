package com.admin.equipment.security;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/** 使用 JDK 自带的 PBKDF2 进行密码哈希，无需额外依赖。 */
public final class PasswordUtil {

    private static final int ITERATIONS = 120_000;
    private static final int KEY_LENGTH = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordUtil() {}

    public static String hash(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] dk = pbkdf2(password, salt);
        return toHex(salt) + "$" + toHex(dk);
    }

    public static boolean verify(String password, String stored) {
        String[] parts = stored.split("\\$", 2);
        if (parts.length != 2) {
            return false;
        }
        byte[] salt = fromHex(parts[0]);
        byte[] expected = fromHex(parts[1]);
        byte[] actual = pbkdf2(password, salt);
        if (actual.length != expected.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < actual.length; i++) {
            diff |= actual[i] ^ expected[i];
        }
        return diff == 0;
    }

    private static byte[] pbkdf2(String password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH);
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return f.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new RuntimeException("密码哈希失败", e);
        }
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }

    private static byte[] fromHex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
