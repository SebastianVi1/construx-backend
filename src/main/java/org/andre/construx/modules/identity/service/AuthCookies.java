package org.andre.construx.modules.identity.service;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/** Browser token transport. The refresh cookie is limited to auth endpoints. */
@Component
public class AuthCookies {
    public void write(HttpServletResponse response, TokenService.Tokens tokens) {
        response.addHeader("Set-Cookie", cookie("access_token", tokens.access(), "/", 900));
        response.addHeader("Set-Cookie", cookie("refresh_token", tokens.refresh(), "/api/auth", 2_592_000));
    }

    public void clear(HttpServletResponse response) {
        response.addHeader("Set-Cookie", cookie("access_token", "", "/", 0));
        response.addHeader("Set-Cookie", cookie("refresh_token", "", "/api/auth", 0));
    }

    private String cookie(String name, String value, String path, long maxAge) {
        return ResponseCookie.from(name, value).httpOnly(true).secure(true).sameSite("Lax")
                .path(path).maxAge(maxAge).build().toString();
    }
}
