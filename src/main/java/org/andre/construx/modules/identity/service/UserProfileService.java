package org.andre.construx.modules.identity.service;

import org.andre.construx.modules.identity.model.UserAccount;
import org.andre.construx.modules.identity.model.UserProfile;
import org.andre.construx.modules.identity.repository.UserAccountRepository;
import org.andre.construx.modules.identity.repository.UserProfileRepository;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class UserProfileService {
    private final UserAccountRepository users;
    private final UserProfileRepository profiles;

    public UserProfileService(UserAccountRepository users, UserProfileRepository profiles) {
        this.users = users;
        this.profiles = profiles;
    }

    public record ProfileData(String fullName, String phoneNumber) {}

    @Transactional(readOnly = true)
    public ProfileData get(UUID accountId) {
        requireAccount(accountId);
        return profiles.findById(accountId)
                .map(profile -> new ProfileData(profile.getFullName(), profile.getPhoneNumber()))
                .orElse(new ProfileData(null, null));
    }

    @Transactional
    public ProfileData update(UUID accountId, String fullName, String phoneNumber) {
        UserAccount account = requireAccount(accountId);
        UserProfile profile = profiles.findById(accountId).orElse(null);
        if (profile == null) {
            profile = new UserProfile(account, fullName, phoneNumber);
        } else {
            profile.update(fullName, phoneNumber);
        }
        UserProfile saved = profiles.save(profile);
        return new ProfileData(saved.getFullName(), saved.getPhoneNumber());
    }

    private UserAccount requireAccount(UUID id) {
        return users.findById(id).orElseThrow(() -> new BadCredentialsException("Account unavailable"));
    }
}
