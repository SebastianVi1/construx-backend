package org.andre.construx.modules.identity.service;

import org.andre.construx.modules.identity.model.UserAccount;
import org.andre.construx.modules.identity.model.UserProfile;
import org.andre.construx.modules.identity.repository.UserAccountRepository;
import org.andre.construx.modules.identity.repository.UserProfileRepository;
import org.andre.construx.shared.exception.RuleViolationException;
import org.andre.construx.shared.storage.ObjectStorage;
import org.andre.construx.shared.storage.ObjectStorage.PresignedUpload;
import org.andre.construx.shared.storage.ObjectStorage.StoredObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class UserProfileAvatarTest {
    private UserProfileRepository profiles;
    private ObjectStorage storage;
    private UserProfileService service;
    private UserProfile profile;
    private final UUID accountId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        UserAccountRepository users = mock(UserAccountRepository.class);
        profiles = mock(UserProfileRepository.class);
        storage = mock(ObjectStorage.class);
        service = new UserProfileService(users, profiles, storage, new PersonDirectory(profiles, storage));

        UserAccount account = mock(UserAccount.class);
        when(users.findById(accountId)).thenReturn(Optional.of(account));
        profile = new UserProfile(account, "Ada Lovelace");
        when(profiles.findById(accountId)).thenReturn(Optional.of(profile));
        when(profiles.save(any(UserProfile.class))).thenAnswer(inv -> inv.getArgument(0));
        when(storage.presignDownload(anyString(), any())).thenReturn(URI.create("https://s3.test/view"));
    }

    @Test
    void presignsAnUploadUnderTheAccountPrefix() {
        when(storage.presignUpload(anyString(), eq("image/png"), eq(1000L), any()))
                .thenReturn(new PresignedUpload(URI.create("https://s3.test/put"), Map.of("Content-Type", "image/png"),
                        Instant.now()));

        UserProfileService.AvatarUploadData upload = service.requestAvatarUpload(accountId, " IMAGE/PNG ", 1000);

        assertTrue(upload.key().startsWith("users/" + accountId + "/avatar/"));
        assertEquals(URI.create("https://s3.test/put"), upload.uploadUrl());
    }

    @Test
    void rejectsUnsupportedTypesAndLargeFiles() {
        assertEquals("AVATAR_TYPE_UNSUPPORTED", assertThrows(RuleViolationException.class,
                () -> service.requestAvatarUpload(accountId, "application/pdf", 1000)).getCode());
        assertEquals("AVATAR_TOO_LARGE", assertThrows(RuleViolationException.class,
                () -> service.requestAvatarUpload(accountId, "image/jpeg", 6L * 1024 * 1024)).getCode());
        verify(storage, never()).presignUpload(anyString(), anyString(), anyLong(), any());
    }

    @Test
    void settingAPhotoStoresTheKeyAndDeletesThePreviousOne() {
        String first = "users/" + accountId + "/avatar/one";
        String second = "users/" + accountId + "/avatar/two";
        when(storage.head(anyString())).thenReturn(Optional.of(new StoredObject(1000, "image/jpeg")));

        service.setAvatar(accountId, first);
        UserProfileService.ProfileData data = service.setAvatar(accountId, second);

        assertEquals(second, profile.getAvatarKey());
        assertEquals(URI.create("https://s3.test/view"), data.avatarUrl());
        verify(storage).delete(first);
        verify(storage, never()).delete(second);
    }

    @Test
    void refusesAKeyOfAnotherAccount() {
        String foreign = "users/" + UUID.randomUUID() + "/avatar/x";

        RuleViolationException ex = assertThrows(RuleViolationException.class,
                () -> service.setAvatar(accountId, foreign));

        assertEquals("AVATAR_KEY_INVALID", ex.getCode());
        verify(storage, never()).head(anyString());
    }

    @Test
    void refusesAMissingUpload() {
        when(storage.head(anyString())).thenReturn(Optional.empty());

        RuleViolationException ex = assertThrows(RuleViolationException.class,
                () -> service.setAvatar(accountId, "users/" + accountId + "/avatar/x"));

        assertEquals("AVATAR_UPLOAD_MISSING", ex.getCode());
        assertNull(profile.getAvatarKey());
    }

    @Test
    void removingThePhotoClearsTheKeyAndTheObject() {
        String key = "users/" + accountId + "/avatar/one";
        profile.changeAvatarKey(key);

        UserProfileService.ProfileData data = service.removeAvatar(accountId);

        assertNull(profile.getAvatarKey());
        assertNull(data.avatarUrl());
        verify(storage).delete(key);
    }
}
