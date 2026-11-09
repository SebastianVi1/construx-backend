package org.andre.construx.modules.identity.service;

import org.andre.construx.modules.identity.model.UserProfile;
import org.andre.construx.modules.identity.repository.UserProfileRepository;
import org.andre.construx.shared.storage.InMemoryObjectStorage;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PersonDirectoryTest {
    private final UserProfileRepository profiles = mock(UserProfileRepository.class);
    private final PersonDirectory directory = new PersonDirectory(profiles, new InMemoryObjectStorage());

    /** The profile id is the account id, which only JPA fills in through {@code @MapsId}. */
    private UserProfile profile(UUID id, String name, String avatarKey) {
        UserProfile profile = mock(UserProfile.class);
        when(profile.getId()).thenReturn(id);
        when(profile.getFullName()).thenReturn(name);
        when(profile.getAvatarKey()).thenReturn(avatarKey);
        return profile;
    }

    @Test
    void returnsNamesAndPhotoLinksAndFallsBackForAccountsWithoutAProfile() {
        UUID withPhoto = UUID.randomUUID();
        UUID withoutPhoto = UUID.randomUUID();
        UUID noProfile = UUID.randomUUID();
        List<UserProfile> stored = List.of(
                profile(withPhoto, "Ada Lovelace", "users/a/avatar/1"),
                profile(withoutPhoto, "Grace Hopper", null));
        when(profiles.findAllById(any())).thenReturn(stored);

        Map<UUID, PersonDirectory.Person> people = directory.byUserId(List.of(withPhoto, withoutPhoto, noProfile, withPhoto));

        assertEquals(new PersonDirectory.Person("Ada Lovelace", URI.create("memory://download/users/a/avatar/1")),
                PersonDirectory.of(people, withPhoto));
        assertEquals(new PersonDirectory.Person("Grace Hopper", null), PersonDirectory.of(people, withoutPhoto));
        assertEquals(new PersonDirectory.Person(null, null), PersonDirectory.of(people, noProfile));
    }

    @Test
    void looksUpOnePersonAndFallsBackWithoutAProfile() {
        UUID withPhoto = UUID.randomUUID();
        UUID noProfile = UUID.randomUUID();
        UserProfile stored = profile(withPhoto, "Ada Lovelace", "users/a/avatar/1");
        when(profiles.findById(withPhoto)).thenReturn(Optional.of(stored));
        when(profiles.findById(noProfile)).thenReturn(Optional.empty());

        assertEquals(new PersonDirectory.Person("Ada Lovelace", URI.create("memory://download/users/a/avatar/1")),
                directory.of(withPhoto));
        assertEquals(new PersonDirectory.Person(null, null), directory.of(noProfile));
    }
}
