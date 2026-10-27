package org.andre.construx.modules.identity.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.andre.construx.modules.identity.service.UserProfileService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    public record ProfileResponse(String fullName, String phoneNumber, String trade) {
        static ProfileResponse from(UserProfileService.ProfileData data) {
            return new ProfileResponse(data.fullName(), data.phoneNumber(), data.trade());
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
}
