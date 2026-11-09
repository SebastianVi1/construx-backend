package org.andre.construx.shared.config;

import org.andre.construx.shared.security.AccessScopes;
import org.andre.construx.shared.security.ScopeArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/** Registers {@link ScopeArgumentResolver} so controllers can take {@code OrgScope} / {@code SiteScope}. */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    private final AccessScopes scopes;

    public WebMvcConfig(AccessScopes scopes) {
        this.scopes = scopes;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new ScopeArgumentResolver(scopes));
    }
}
