package org.andre.construx.modules.identity.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.util.UUID;

/** Optional personal information, independent of organization membership. */
@Entity
@Table(name = "user_profile")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserProfile {
    @Id
    private UUID id;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_account_id", nullable = false, unique = true)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private UserAccount account;

    @Column(name = "full_name", nullable = false, length = 160)
    private String fullName;

    @Column(name = "phone_number", nullable = false, length = 16)
    private String phoneNumber;

    public UserProfile(UserAccount account, String fullName, String phoneNumber) {
        this.account = account;
        update(fullName, phoneNumber);
    }

    public void update(String fullName, String phoneNumber) {
        if (fullName == null || fullName.isBlank() || fullName.strip().length() > 160) {
            throw new IllegalArgumentException("Full name must have 1 to 160 characters");
        }
        String normalizedPhone = phoneNumber == null ? "" : phoneNumber.strip();
        if (!normalizedPhone.matches("\\+[1-9][0-9]{1,14}")) {
            throw new IllegalArgumentException("Phone number must use international E.164 format");
        }
        this.fullName = fullName.strip();
        this.phoneNumber = normalizedPhone;
    }
}
