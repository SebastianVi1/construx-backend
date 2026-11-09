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

/**
 * Name and photo of accounts, for every response that shows a person. The only place that reads
 * profiles for display or turns an avatar key into a link, so the photo link policy lives here.
 */
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
            people.put(profile.getId(), of(profile));
        }
        return people;
    }

    /** One account; without a profile both fields are null. */
    public Person of(UUID userId) {
        return profiles.findById(userId).map(this::of).orElse(Person.UNKNOWN);
    }

    public static Person of(Map<UUID, Person> people, UUID userId) {
        return people.getOrDefault(userId, Person.UNKNOWN);
    }

    /** For identity services that already hold the profile, so it is not read twice. */
    Person of(UserProfile profile) {
        URI avatarUrl = profile.getAvatarKey() != null
                ? storage.presignDownload(profile.getAvatarKey(), AVATAR_VIEW_TTL) : null;
        return new Person(profile.getFullName(), avatarUrl);
    }
}
