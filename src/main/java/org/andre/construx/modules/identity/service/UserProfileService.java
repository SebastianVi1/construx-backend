package org.andre.construx.modules.identity.service;

import org.andre.construx.modules.identity.model.UserAccount;
import org.andre.construx.modules.identity.model.UserProfile;
import org.andre.construx.modules.identity.repository.UserAccountRepository;
import org.andre.construx.modules.identity.repository.UserProfileRepository;
import org.andre.construx.shared.exception.RuleViolationException;
import org.andre.construx.shared.storage.ObjectStorage;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class UserProfileService {
    static final long MAX_AVATAR_BYTES = 5L * 1024 * 1024;
    static final Set<String> AVATAR_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    static final Duration UPLOAD_TTL = Duration.ofMinutes(10);
    static final Duration VIEW_TTL = Duration.ofHours(1);

    private final UserAccountRepository users;
    private final UserProfileRepository profiles;
    private final ObjectStorage storage;

    public UserProfileService(UserAccountRepository users, UserProfileRepository profiles, ObjectStorage storage) {
        this.users = users;
        this.profiles = profiles;
        this.storage = storage;
    }

    public record ProfileData(String fullName, String phoneNumber, String trade, URI avatarUrl) {}

    /** {@code key} is sent back to {@link #setAvatar} once the bytes are uploaded. */
    public record AvatarUploadData(String key, URI uploadUrl, Map<String, String> headers, Instant expiresAt) {}

    @Transactional(readOnly = true)
    public ProfileData get(UUID accountId) {
        requireAccount(accountId);
        return profiles.findById(accountId)
                .map(this::toData)
                .orElse(new ProfileData(null, null, null, null));
    }

    @Transactional
    public ProfileData update(UUID accountId, String fullName, String phoneNumber) {
        return update(accountId, fullName, phoneNumber, null);
    }

    /** A null trade keeps the stored value, so clients that do not send it cannot erase it. */
    @Transactional
    public ProfileData update(UUID accountId, String fullName, String phoneNumber, String trade) {
        UserAccount account = requireAccount(accountId);
        UserProfile profile = profiles.findById(accountId).orElse(null);
        if (profile == null) {
            profile = new UserProfile(account, fullName, phoneNumber);
        } else {
            profile.update(fullName, phoneNumber);
        }
        if (trade != null) {
            profile.changeTrade(trade);
        }
        return toData(profiles.save(profile));
    }

    /** Presigns an upload for a new photo; the stored photo only changes on {@link #setAvatar}. */
    public AvatarUploadData requestAvatarUpload(UUID accountId, String contentType, long sizeBytes) {
        requireAccount(accountId);
        String type = contentType == null ? "" : contentType.strip().toLowerCase(Locale.ROOT);
        if (!AVATAR_TYPES.contains(type)) {
            throw new RuleViolationException("AVATAR_TYPE_UNSUPPORTED", "Profile photos must be JPEG, PNG or WebP");
        }
        if (sizeBytes <= 0 || sizeBytes > MAX_AVATAR_BYTES) {
            throw new RuleViolationException("AVATAR_TOO_LARGE", "Profile photos must be at most 5 MB");
        }
        String key = avatarPrefix(accountId) + UUID.randomUUID();
        ObjectStorage.PresignedUpload upload = storage.presignUpload(key, type, sizeBytes, UPLOAD_TTL);
        return new AvatarUploadData(key, upload.url(), upload.headers(), upload.expiresAt());
    }

    /** Points the profile at an uploaded photo and deletes the previous one. */
    @Transactional
    public ProfileData setAvatar(UUID accountId, String key) {
        requireAccount(accountId);
        UserProfile profile = requireProfile(accountId);
        if (key == null || !key.startsWith(avatarPrefix(accountId))) {
            throw new RuleViolationException("AVATAR_KEY_INVALID", "The photo does not belong to this account");
        }
        ObjectStorage.StoredObject stored = storage.head(key)
                .orElseThrow(() -> new RuleViolationException("AVATAR_UPLOAD_MISSING", "The photo was not uploaded"));
        String storedType = stored.contentType() == null ? "" : stored.contentType().toLowerCase(Locale.ROOT);
        if (stored.sizeBytes() > MAX_AVATAR_BYTES || !AVATAR_TYPES.contains(storedType)) {
            storage.delete(key);
            throw new RuleViolationException("AVATAR_TYPE_UNSUPPORTED",
                    "Profile photos must be JPEG, PNG or WebP up to 5 MB");
        }
        String previous = profile.getAvatarKey();
        profile.changeAvatarKey(key);
        UserProfile saved = profiles.save(profile);
        if (previous != null && !previous.equals(key)) {
            storage.delete(previous);
        }
        return toData(saved);
    }

    @Transactional
    public ProfileData removeAvatar(UUID accountId) {
        requireAccount(accountId);
        UserProfile profile = requireProfile(accountId);
        String previous = profile.getAvatarKey();
        profile.changeAvatarKey(null);
        UserProfile saved = profiles.save(profile);
        if (previous != null) {
            storage.delete(previous);
        }
        return toData(saved);
    }

    private ProfileData toData(UserProfile profile) {
        URI avatarUrl = profile.getAvatarKey() == null ? null : storage.presignDownload(profile.getAvatarKey(), VIEW_TTL);
        return new ProfileData(profile.getFullName(), profile.getPhoneNumber(), profile.getTrade(), avatarUrl);
    }

    private static String avatarPrefix(UUID accountId) {
        return "users/%s/avatar/".formatted(accountId);
    }

    private UserProfile requireProfile(UUID accountId) {
        return profiles.findById(accountId).orElseThrow(() ->
                new RuleViolationException("PROFILE_REQUIRED", "Complete your profile before adding a photo"));
    }

    private UserAccount requireAccount(UUID id) {
        return users.findById(id).orElseThrow(() -> new BadCredentialsException("Account unavailable"));
    }
}
