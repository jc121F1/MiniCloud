package jc121f1.common;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import java.security.SecureRandom;
import java.util.Base64;

public class PasswordUtil {

    private static final int SALT_LENGTH = 16;   // bytes
    private static final int HASH_LENGTH = 32;   // bytes
    private static final int ITERATIONS = 3;
    private static final int MEMORY_KB = 65536;  // 64 MB
    private static final int PARALLELISM = 1;

    private static final SecureRandom RANDOM = new SecureRandom();

    public static String hash(char[] password) {
        byte[] salt = new byte[SALT_LENGTH];
        RANDOM.nextBytes(salt);

        byte[] hash = rawHash(password, salt);

        // Store algorithm params + salt + hash together, similar to how bcrypt/argon2 formats do it
        return String.format("%d$%d$%d$%s$%s",
                ITERATIONS, MEMORY_KB, PARALLELISM,
                Base64.getEncoder().encodeToString(salt),
                Base64.getEncoder().encodeToString(hash));
    }

    public static boolean verify(char[] password, String stored) {
        String[] parts = stored.split("\\$");
        int iterations = Integer.parseInt(parts[0]);
        int memoryKb = Integer.parseInt(parts[1]);
        int parallelism = Integer.parseInt(parts[2]);
        byte[] salt = Base64.getDecoder().decode(parts[3]);
        byte[] expectedHash = Base64.getDecoder().decode(parts[4]);

        Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(iterations)
                .withMemoryAsKB(memoryKb)
                .withParallelism(parallelism)
                .withSalt(salt)
                .build();

        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(params);

        byte[] actualHash = new byte[expectedHash.length];
        generator.generateBytes(password, actualHash);

        return constantTimeEquals(expectedHash, actualHash);
    }

    private static byte[] rawHash(char[] password, byte[] salt) {
        Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(ITERATIONS)
                .withMemoryAsKB(MEMORY_KB)
                .withParallelism(PARALLELISM)
                .withSalt(salt)
                .build();

        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(params);

        byte[] hash = new byte[HASH_LENGTH];
        generator.generateBytes(password, hash);
        return hash;
    }

    // Avoid timing attacks on hash comparison
    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }
}
