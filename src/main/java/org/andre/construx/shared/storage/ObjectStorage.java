package org.andre.construx.shared.storage;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Private file storage. Files never pass through the API: clients send bytes straight to a presigned
 * upload URL and read them through short-lived presigned download URLs.
 */
public interface ObjectStorage {

    /** Where and how the client sends the bytes; {@code headers} must be sent as given or the upload fails. */
    record PresignedUpload(URI url, Map<String, String> headers, Instant expiresAt) {}

    /** What the storage actually holds under a key. */
    record StoredObject(long sizeBytes, String contentType) {}

    PresignedUpload presignUpload(String key, String contentType, long sizeBytes, Duration ttl);

    URI presignDownload(String key, Duration ttl);

    Optional<StoredObject> head(String key);

    /** Deleting a missing key is not an error. */
    void delete(String key);
}
