package org.andre.construx.modules.identity.service;

import org.andre.construx.modules.identity.model.AuthSession;
import org.andre.construx.modules.identity.model.UserAccount;
import org.andre.construx.modules.identity.model.UserStatus;
import org.andre.construx.modules.identity.repository.AuthSessionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/** Issues short-lived JWTs and rotates opaque refresh secrets under a database lock. */
@Service
public class TokenService {
    public static final Duration ACCESS_LIFETIME = Duration.ofMinutes(15);
    public static final Duration REFRESH_LIFETIME = Duration.ofDays(30);
    /** A just-rotated refresh secret may still be presented by a parallel request (for example another tab). */
    public static final Duration REFRESH_REUSE_GRACE = Duration.ofSeconds(10);
    private final AuthSessionRepository sessions;
    private final JwtEncoder encoder;
    private final String issuer;
    private final String audience;
    private final SecureRandom random = new SecureRandom();

    public TokenService(AuthSessionRepository sessions, JwtEncoder encoder,
            @Value("${app.security.issuer}") String issuer,
            @Value("${app.security.audience}") String audience) {
        this.sessions = sessions;
        this.encoder = encoder;
        this.issuer = issuer;
        this.audience = audience;
    }

    public record Tokens(String access, String refresh) {}

    @Transactional
    public Tokens start(UserAccount user) {
        if (user.getStatus() != UserStatus.ACTIVE || !user.isEmailVerified()) {
            throw new BadCredentialsException("Account unavailable");
        }
        String secret = randomSecret();
        AuthSession session = sessions.save(new AuthSession(user, sha256(secret), Instant.now().plus(REFRESH_LIFETIME)));
        return new Tokens(access(session), session.getId() + "." + secret);
    }

    @Transactional(noRollbackFor = BadCredentialsException.class)
    public Tokens refresh(String token) {
        String[] parts = token == null ? new String[0] : token.split("\\.", -1);
        if (parts.length != 2) throw new BadCredentialsException("Invalid refresh token");
        UUID id;
        try { id = UUID.fromString(parts[0]); }
        catch (IllegalArgumentException ex) { throw new BadCredentialsException("Invalid refresh token"); }
        AuthSession session = sessions.findWithLockById(id)
                .orElseThrow(() -> new BadCredentialsException("Invalid refresh token"));
        if (!session.activeAt(Instant.now()) || session.getUser().getStatus() != UserStatus.ACTIVE
                || !session.getUser().isEmailVerified()) {
            throw new BadCredentialsException("Invalid refresh token");
        }
        String presented = sha256(parts[1]);
        if (constantTimeEquals(session.getRefreshHash(), presented)) {
            String secret = randomSecret();
            session.rotate(sha256(secret), Instant.now());
            return new Tokens(access(session), session.getId() + "." + secret);
        }
        if (justRotatedFrom(session, presented)) {
            // The winning request already delivered the new refresh cookie; only mint an access token.
            return new Tokens(access(session), null);
        }
        session.revoke(Instant.now());
        throw new BadCredentialsException("Invalid refresh token");
    }

    @Transactional
    public void logoutByRefresh(String token) {
        String[] parts = token == null ? new String[0] : token.split("\\.", -1);
        if (parts.length != 2) return;
        try {
            sessions.findWithLockById(UUID.fromString(parts[0]))
                    .filter(s -> constantTimeEquals(s.getRefreshHash(), sha256(parts[1])))
                    .ifPresent(s -> s.revoke(Instant.now()));
        } catch (IllegalArgumentException ignored) { /* Always clear browser cookies. */ }
    }

    @Transactional
    public void revokeAll(UUID userId) {
        sessions.findByUserId(userId).forEach(s -> s.revoke(Instant.now()));
    }

    private String access(AuthSession session) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(issuer).audience(java.util.List.of(audience))
                .subject(session.getUser().getId().toString()).issuedAt(now)
                .expiresAt(now.plus(ACCESS_LIFETIME)).claim("sid", session.getId().toString()).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    private String randomSecret() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private boolean justRotatedFrom(AuthSession session, String presentedHash) {
        return session.getPreviousRefreshHash() != null
                && constantTimeEquals(session.getPreviousRefreshHash(), presentedHash)
                && session.getRotatedAt().plus(REFRESH_REUSE_GRACE).isAfter(Instant.now());
    }

    private boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
    }
}
