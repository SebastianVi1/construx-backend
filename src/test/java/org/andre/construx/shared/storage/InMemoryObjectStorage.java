package org.andre.construx.shared.storage;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Keeps files in a map and hands out {@code memory://} links, for tests that need storage without S3. */
public class InMemoryObjectStorage implements ObjectStorage {
    private record Stored(byte[] bytes, String contentType) {}

    private final Map<String, Stored> objects = new HashMap<>();

    @Override
    public PresignedUpload presignUpload(String key, String contentType, long sizeBytes, Duration ttl) {
        return new PresignedUpload(URI.create("memory://upload/" + key), Map.of("Content-Type", contentType),
                Instant.now().plus(ttl));
    }

    /** The link names the key, so a test can tell which file it points at. */
    @Override
    public URI presignDownload(String key, Duration ttl) {
        return URI.create("memory://download/" + key);
    }

    @Override
    public Optional<StoredObject> head(String key) {
        return Optional.ofNullable(objects.get(key)).map(o -> new StoredObject(o.bytes().length, o.contentType()));
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        objects.put(key, new Stored(bytes.clone(), contentType));
    }

    @Override
    public byte[] getBytes(String key) {
        Stored stored = objects.get(key);
        if (stored == null) {
            throw new IllegalArgumentException("No object under " + key);
        }
        return stored.bytes().clone();
    }

    @Override
    public void delete(String key) {
        objects.remove(key);
    }
}
