package org.andre.construx.modules.identity.service;

import org.andre.construx.modules.identity.model.UserProfile;
import org.andre.construx.modules.identity.repository.UserProfileRepository;
import org.andre.construx.shared.storage.ObjectStorage;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PersonDirectoryTest {
    private final UserProfileRepository profiles = mock(UserProfileRepository.class);
    private final ObjectStorage storage = mock(ObjectStorage.class);
    private final PersonDirectory directory = new PersonDirectory(profiles, storage);

    /** The profile id is the account id, which only JPA fills in through {@code @MapsId}. */
    private UserProfile profile(UUID id, String name, String avatarKey) {
        UserProfile profile = mock(UserProfile.class);
        when(profile.getId()).thenReturn(id);
        when(profile.getFullName()).thenReturn(name);
        when(profile.getAvatarKey()).thenReturn(avatarKey);
        return profile;
    }

    @Test
    void returnsNamesAndPresignedPhotosAndFallsBackForAccountsWithoutAProfile() {
        UUID withPhoto = UUID.randomUUID();
        UUID withoutPhoto = UUID.randomUUID();
        UUID noProfile = UUID.randomUUID();
        List<UserProfile> stored = List.of(
                profile(withPhoto, "Ada Lovelace", "users/a/avatar/1"),
                profile(withoutPhoto, "Grace Hopper", null));
        when(profiles.findAllById(any())).thenReturn(stored);
        when(storage.presignDownload(anyString(), any(Duration.class))).thenReturn(URI.create("https://s3.test/view"));

        Map<UUID, PersonDirectory.Person> people = directory.byUserId(List.of(withPhoto, withoutPhoto, noProfile, withPhoto));

        assertEquals(new PersonDirectory.Person("Ada Lovelace", URI.create("https://s3.test/view")),
                PersonDirectory.of(people, withPhoto));
        assertNull(PersonDirectory.of(people, withoutPhoto).avatarUrl());
        assertEquals(new PersonDirectory.Person(null, null), PersonDirectory.of(people, noProfile));
        verify(storage, times(1)).presignDownload(eq("users/a/avatar/1"), any(Duration.class));
    }
}
