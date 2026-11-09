package org.andre.construx.modules.project.access;

import org.andre.construx.modules.organization.access.OrgScopes;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/** Registers {@link ScopeArgumentResolver} so controllers can take {@code OrgScope} / {@code SiteScope}. */
@Configuration
public class ScopeWebConfig implements WebMvcConfigurer {
    private final OrgScopes orgs;
    private final SiteScopes sites;

    public ScopeWebConfig(OrgScopes orgs, SiteScopes sites) {
        this.orgs = orgs;
        this.sites = sites;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new ScopeArgumentResolver(orgs, sites));
    }
}
