package org.andre.construx.shared.security;

import org.springframework.security.core.Authentication;

import java.util.UUID;

/** Shared utilities for extracting identity from a Spring Security {@link Authentication}. */
public final class SecurityUtils {

    private SecurityUtils() {}

    /** Returns the authenticated user's UUID from the principal name. */
    public static UUID userId(Authentication auth) {
        return UUID.fromString(auth.getName());
    }
}
