package org.andre.construx.shared.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.andre.construx.modules.identity.repository.AuthSessionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Configuration
public class JwtConfig {
    @Bean
    public SecretKey jwtKey(@Value("${app.security.jwt-secret}") String encoded) {
        byte[] bytes = Base64.getDecoder().decode(encoded);
        if (bytes.length < 32) throw new IllegalArgumentException("JWT secret must contain at least 32 bytes");
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKey key) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    @Bean
    public JwtDecoder jwtDecoder(SecretKey key, AuthSessionRepository sessions,
            @Value("${app.security.issuer}") String issuer,
            @Value("${app.security.audience}") String audience) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> standard = JwtValidators.createDefaultWithIssuer(issuer);
        decoder.setJwtValidator(jwt -> {
            OAuth2TokenValidatorResult result = standard.validate(jwt);
            if (result.hasErrors()) return result;
            try {
                UUID userId = UUID.fromString(jwt.getSubject());
                UUID sessionId = UUID.fromString(jwt.getClaimAsString("sid"));
                boolean valid = jwt.getAudience().contains(audience) && sessions.findById(sessionId)
                        .filter(s -> s.activeAt(Instant.now()) && s.getUser().getId().equals(userId)
                                && s.getUser().isActive())
                        .isPresent();
                if (valid) return OAuth2TokenValidatorResult.success();
            } catch (RuntimeException ignored) { /* Invalid claim values fail closed. */ }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        });
        return decoder;
    }
}
