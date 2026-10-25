package org.andre.construx.modules.identity.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthCookiesTest {

    private final AuthCookies cookies = new AuthCookies();

    @Test
    void tokenCookiesAreHttpOnlySecureSameSiteAndScoped() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        cookies.write(response, new TokenService.Tokens("access-jwt", "refresh-token"));
        List<String> headers = response.getHeaders(HttpHeaders.SET_COOKIE);
        assertEquals(2, headers.size());
        String access = headers.stream().filter(h -> h.startsWith("access_token=")).findFirst().orElseThrow();
        String refresh = headers.stream().filter(h -> h.startsWith("refresh_token=")).findFirst().orElseThrow();
        for (String header : headers) {
            assertTrue(header.contains("HttpOnly"), header);
            assertTrue(header.contains("Secure"), header);
            assertTrue(header.contains("SameSite=Lax"), header);
        }
        assertTrue(access.contains("Path=/;") && access.contains("Max-Age=900"), access);
        assertTrue(refresh.contains("Path=/api/auth") && refresh.contains("Max-Age=2592000"), refresh);
    }

    @Test
    void clearExpiresBothCookies() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        cookies.clear(response);
        for (String header : response.getHeaders(HttpHeaders.SET_COOKIE)) {
            assertTrue(header.contains("Max-Age=0"), header);
        }
    }
}
