package com.oppo.starrocks.shield.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads shared password from a local file with simple mtime-based caching.
 */
final class ShieldSharedPasswordStore {
    private final String passwordFile;
    private volatile CachedPassword cachedPassword = CachedPassword.EMPTY;

    ShieldSharedPasswordStore(String passwordFile) {
        this.passwordFile = passwordFile;
    }

    byte[] loadPasswordBytes() throws IOException {
        Path path = Path.of(passwordFile);
        long lastModified = Files.getLastModifiedTime(path).toMillis();
        CachedPassword current = cachedPassword;
        if (current.matches(lastModified)) {
            return current.passwordBytes;
        }
        String content = Files.readString(path, StandardCharsets.UTF_8).trim();
        if (content.isEmpty()) {
            throw new IOException("password file is empty: " + passwordFile);
        }
        byte[] passwordBytes = content.getBytes(StandardCharsets.UTF_8);
        cachedPassword = new CachedPassword(lastModified, passwordBytes);
        return passwordBytes;
    }

    private static final class CachedPassword {
        private static final CachedPassword EMPTY = new CachedPassword(-1, new byte[0]);

        private final long lastModified;
        private final byte[] passwordBytes;

        private CachedPassword(long lastModified, byte[] passwordBytes) {
            this.lastModified = lastModified;
            this.passwordBytes = passwordBytes;
        }

        private boolean matches(long lastModified) {
            return this.lastModified == lastModified;
        }
    }
}
