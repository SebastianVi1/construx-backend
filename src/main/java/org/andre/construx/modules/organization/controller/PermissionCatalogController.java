package org.andre.construx.modules.organization.controller;

import org.andre.construx.modules.organization.model.PermissionCatalog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The permission catalog, the same for every company and static per deploy. Any signed-in user may read it. */
@RestController
@RequestMapping("/api/permissions")
public class PermissionCatalogController {

    @GetMapping("/catalog")
    public PermissionCatalog.CatalogData catalog() {
        return PermissionCatalog.get();
    }
}
