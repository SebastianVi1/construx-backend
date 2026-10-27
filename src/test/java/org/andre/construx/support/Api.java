package org.andre.construx.support;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.andre.construx.modules.identity.model.UserAccount;
import org.andre.construx.modules.identity.repository.UserAccountRepository;
import org.andre.construx.modules.identity.service.TokenService;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Signed-in HTTP calls for flow tests: real cookies, real CSRF, real endpoints. */
public final class Api {
    private final MockMvc mvc;
    private final UserAccountRepository users;
    private final TokenService tokens;

    public Api(MockMvc mvc, UserAccountRepository users, TokenService tokens) {
        this.mvc = mvc;
        this.users = users;
        this.tokens = tokens;
    }

    public record Csrf(Cookie cookie, String header, String token) {}

    /** A verified account and its signed-in session. */
    public record Session(UserAccount user, Cookie access, Csrf csrf) {}

    public Session signUp() throws Exception {
        UserAccount user = new UserAccount(UUID.randomUUID() + "@example.test", "test-hash");
        user.verifyEmail();
        user = users.save(user);
        return new Session(user, new Cookie("access_token", tokens.start(user).access()), csrf());
    }

    public Csrf csrf() throws Exception {
        MvcResult result = mvc.perform(MockMvcRequestBuilders.get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        String body = result.getResponse().getContentAsString();
        return new Csrf(result.getResponse().getCookie("XSRF-TOKEN"),
                JsonPath.read(body, "$.headerName"), JsonPath.read(body, "$.token"));
    }

    public ResultActions get(Session s, String path) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get(path).cookie(s.access()));
    }

    public ResultActions post(Session s, String path, String json) throws Exception {
        return send(MockMvcRequestBuilders.post(path), s, json);
    }

    public ResultActions patch(Session s, String path, String json) throws Exception {
        return send(MockMvcRequestBuilders.patch(path), s, json);
    }

    public ResultActions put(Session s, String path, String json) throws Exception {
        return send(MockMvcRequestBuilders.put(path), s, json);
    }

    public ResultActions delete(Session s, String path) throws Exception {
        return send(MockMvcRequestBuilders.delete(path), s, null);
    }

    private ResultActions send(MockHttpServletRequestBuilder request, Session s, String json) throws Exception {
        request.cookie(s.access(), s.csrf().cookie()).header(s.csrf().header(), s.csrf().token());
        if (json != null) {
            request.contentType("application/json").content(json);
        }
        return mvc.perform(request);
    }

    /** Creates an organization owned by the session's user and returns its id. */
    public UUID createOrganization(Session owner, String name) throws Exception {
        MvcResult created = post(owner, "/api/organizations", "{\"name\":\"" + name + "\"}")
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(JsonPath.read(created.getResponse().getContentAsString(), "$.id"));
    }

    public static String id(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
    }
}
