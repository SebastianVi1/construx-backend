package org.andre.construx.modules.identity.service;

import org.andre.construx.modules.identity.model.UserProfile;
import org.andre.construx.modules.identity.repository.UserProfileRepository;
import org.andre.construx.shared.storage.ObjectStorage;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Name and photo of accounts, for lists that show people beside their work. */
@Service
public class PersonDirectory {
    private static final Duration AVATAR_VIEW_TTL = Duration.ofHours(1);

    private final UserProfileRepository profiles;
    private final ObjectStorage storage;

    public PersonDirectory(UserProfileRepository profiles, ObjectStorage storage) {
        this.profiles = profiles;
        this.storage = storage;
    }

    /** {@code avatarUrl} is a short-lived download link, or null without a photo. */
    public record Person(String fullName, URI avatarUrl) {
        static final Person UNKNOWN = new Person(null, null);
    }

    /** Accounts without a profile are left out; use {@link #of} to read with a fallback. */
    public Map<UUID, Person> byUserId(Collection<UUID> userIds) {
        Map<UUID, Person> people = new HashMap<>();
        for (UserProfile profile : profiles.findAllById(userIds.stream().distinct().toList())) {
            URI avatarUrl = profile.getAvatarKey() != null
                    ? storage.presignDownload(profile.getAvatarKey(), AVATAR_VIEW_TTL) : null;
            people.put(profile.getId(), new Person(profile.getFullName(), avatarUrl));
        }
        return people;
    }

    public static Person of(Map<UUID, Person> people, UUID userId) {
        return people.getOrDefault(userId, Person.UNKNOWN);
    }
}
