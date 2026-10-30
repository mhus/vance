package de.mhus.vance.shared.resourceoverride;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** Content hash of a file set; independent of the order the files come in. */
public final class ResourceOverrideDigest {

    private ResourceOverrideDigest() {}

    public static String of(List<ResourceOverrideFile> files) {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JDK", e);
        }
        files.stream()
                .sorted(Comparator.comparing(ResourceOverrideFile::getPath))
                .forEach(file -> {
                    sha.update(file.getPath().getBytes(StandardCharsets.UTF_8));
                    sha.update((byte) 0);
                    sha.update(file.getContent().getBytes(StandardCharsets.UTF_8));
                    sha.update((byte) 0);
                });
        return HexFormat.of().formatHex(sha.digest());
    }
}
