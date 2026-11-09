package org.andre.construx.modules.identity.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.andre.construx.modules.identity.service.UserProfileService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/** Self-service personal information; no caller-supplied account identifier. */
@RestController
@RequestMapping("/api/profile")
public class UserProfileController {
    private final UserProfileService profiles;

    public UserProfileController(UserProfileService profiles) {
        this.profiles = profiles;
    }

    public record ProfileUpdate(
            @NotBlank @Size(max = 160) String fullName,
            @NotBlank @Size(max = 64) @Pattern(regexp = "\\s*\\+[1-9][0-9]{1,14}\\s*",
                    message = "must use international E.164 format") String phoneNumber,
            @Size(max = 80) String trade) {}

    public record AvatarUploadRequest(@NotBlank @Size(max = 100) String contentType, @NotNull @Positive Long sizeBytes) {}

    public record AvatarUpdate(@NotBlank @Size(max = 300) String key) {}

    /** {@code avatarUrl} is a presigned download URL that expires after an hour; null without a photo. */
    public record ProfileResponse(String fullName, String phoneNumber, String trade, URI avatarUrl) {
        static ProfileResponse from(UserProfileService.ProfileData data) {
            return new ProfileResponse(data.fullName(), data.phoneNumber(), data.trade(), data.avatarUrl());
        }
    }

    @GetMapping
    public ProfileResponse get(Authentication authentication) {
        return ProfileResponse.from(profiles.get(UUID.fromString(authentication.getName())));
    }

    @PutMapping
    public ProfileResponse update(@Valid @RequestBody ProfileUpdate body, Authentication authentication) {
        return ProfileResponse.from(profiles.update(UUID.fromString(authentication.getName()),
                body.fullName(), body.phoneNumber(), body.trade()));
    }

    @PostMapping("/avatar/uploads")
    @ResponseStatus(HttpStatus.CREATED)
    public UserProfileService.AvatarUploadData requestAvatarUpload(@Valid @RequestBody AvatarUploadRequest body,
                                                                   Authentication authentication) {
        return profiles.requestAvatarUpload(UUID.fromString(authentication.getName()),
                body.contentType(), body.sizeBytes());
    }

    @PutMapping("/avatar")
    public ProfileResponse setAvatar(@Valid @RequestBody AvatarUpdate body, Authentication authentication) {
        return ProfileResponse.from(profiles.setAvatar(UUID.fromString(authentication.getName()), body.key()));
    }

    @DeleteMapping("/avatar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeAvatar(Authentication authentication) {
        profiles.removeAvatar(UUID.fromString(authentication.getName()));
    }
}
