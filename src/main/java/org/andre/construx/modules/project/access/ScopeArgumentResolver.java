package org.andre.construx.modules.project.access;

import jakarta.servlet.http.HttpServletRequest;
import org.andre.construx.modules.organization.access.OrgScope;
import org.andre.construx.modules.organization.access.OrgScopes;
import org.andre.construx.shared.security.SecurityUtils;
import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;
import java.util.UUID;

/**
 * Lets a controller method declare an {@link OrgScope} or {@link SiteScope} parameter. The scope is built from the
 * authenticated caller and the {@code {organizationId}} / {@code {projectId}} path variables, so a non-member or a
 * Site outside the organization is a 404 before the handler runs.
 */
public class ScopeArgumentResolver implements HandlerMethodArgumentResolver {
    private final OrgScopes orgs;
    private final SiteScopes sites;

    public ScopeArgumentResolver(OrgScopes orgs, SiteScopes sites) {
        this.orgs = orgs;
        this.sites = sites;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        Class<?> type = parameter.getParameterType();
        return type == SiteScope.class || type == OrgScope.class;
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        UUID userId = callerId();
        UUID organizationId = pathId(webRequest, "organizationId");
        if (parameter.getParameterType() == OrgScope.class) {
            return orgs.org(userId, organizationId);
        }
        return sites.site(userId, organizationId, pathId(webRequest, "projectId"));
    }

    private static UUID callerId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AuthenticationCredentialsNotFoundException("Authentication required");
        }
        return SecurityUtils.userId(authentication);
    }

    @SuppressWarnings("unchecked")
    private static UUID pathId(NativeWebRequest webRequest, String name) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        Map<String, String> variables = request == null ? null
                : (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String value = variables == null ? null : variables.get(name);
        if (value == null) {
            throw new IllegalStateException("Path variable {" + name + "} is required to build an access scope");
        }
        return UUID.fromString(value);
    }
}
