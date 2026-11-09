package org.andre.construx.modules.documents.controller;

import org.andre.construx.modules.documents.service.DocumentService;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.project.access.SiteScope;
import org.andre.construx.modules.project.access.TestScopes;
import org.andre.construx.modules.project.model.Project;
import org.andre.construx.shared.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DocumentControllerTest {

    private DocumentService documentService;
    private MockMvc mvc;
    private SiteScope site;

    private final UUID orgId    = UUID.fromString("11111111-0000-0000-0000-000000000001");
    private final UUID siteId   = UUID.fromString("22222222-0000-0000-0000-000000000002");
    private final UUID folderId = UUID.fromString("33333333-0000-0000-0000-000000000003");
    private final UUID userId   = UUID.fromString("44444444-0000-0000-0000-000000000004");

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        Project project = mock(Project.class);
        when(project.getId()).thenReturn(siteId);
        site = TestScopes.fullAccess(orgId, project, mock(OrganizationMembership.class));
        mvc = MockMvcBuilders.standaloneSetup(new DocumentController(documentService))
                .setCustomArgumentResolvers(new FixedSiteScope(site))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    // ── GET /document-folders — financial folder filtering ───────────────

    @Test
    void listFoldersReturnsOnlyVisibleFolders() throws Exception {
        DocumentService.FolderData general  = folder("Reports", "Reportes", false, null);
        // financial folder is excluded by the service — service returns only visible folders
        when(documentService.listFolders(eq(site)))
                .thenReturn(List.of(general));

        mvc.perform(get("/api/organizations/{org}/projects/{site}/document-folders", orgId, siteId)
                        .principal(auth(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].nameEn").value("Reports"));
    }

    @Test
    void listFoldersIncludesAllFoldersForPrivilegedCaller() throws Exception {
        DocumentService.FolderData general   = folder("Reports",    "Reportes",          false, null);
        DocumentService.FolderData financial = folder("Budget & APU","Presupuesto y APU", true,  "VIEW_PRICES");
        when(documentService.listFolders(eq(site)))
                .thenReturn(List.of(general, financial));

        mvc.perform(get("/api/organizations/{org}/projects/{site}/document-folders", orgId, siteId)
                        .principal(auth(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    // ── DELETE /document-folders/{folderId} — standard folder protection ─

    @Test
    void deletingStandardFolderReturns403() throws Exception {
        doThrow(new AccessDeniedException("Standard folders cannot be deleted"))
                .when(documentService).deleteFolder(eq(site), eq(folderId));

        mvc.perform(delete("/api/organizations/{org}/projects/{site}/document-folders/{folderId}",
                        orgId, siteId, folderId)
                        .principal(auth(userId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void deletingCustomFolderReturns204() throws Exception {
        doNothing().when(documentService).deleteFolder(eq(site), eq(folderId));

        mvc.perform(delete("/api/organizations/{org}/projects/{site}/document-folders/{folderId}",
                        orgId, siteId, folderId)
                        .principal(auth(userId)))
                .andExpect(status().isNoContent());
    }

    // ── POST /document-folders — validation ──────────────────────────────

    @Test
    void createFolderWithBlankNameReturns400() throws Exception {
        String body = "{\"nameEn\":\"\",\"nameEs\":\"Nombre\"}";

        mvc.perform(post("/api/organizations/{org}/projects/{site}/document-folders", orgId, siteId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .principal(auth(userId)))
                .andExpect(status().isBadRequest());
    }

    // ── GET /document-folders/search ─────────────────────────────────────

    @Test
    void searchReturnsMatchingHits() throws Exception {
        DocumentService.SearchHit hit = new DocumentService.SearchHit(
                UUID.randomUUID(), "presupuesto-julio.pdf", folderId, "Budget & APU", "Presupuesto y APU");
        when(documentService.search(eq(site), eq("presupuesto")))
                .thenReturn(List.of(hit));

        mvc.perform(get("/api/organizations/{org}/projects/{site}/document-folders/search", orgId, siteId)
                        .param("q", "presupuesto")
                        .principal(auth(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].fileName").value("presupuesto-julio.pdf"))
                .andExpect(jsonPath("$[0].folderId").value(folderId.toString()));
    }

    @Test
    void searchWithMissingQueryReturns400() throws Exception {
        mvc.perform(get("/api/organizations/{org}/projects/{site}/document-folders/search", orgId, siteId)
                        .principal(auth(userId)))
                .andExpect(status().isBadRequest());
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** Stands in for the real resolver, which needs the database to build the scope. */
    private record FixedSiteScope(SiteScope scope) implements HandlerMethodArgumentResolver {
        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return parameter.getParameterType() == SiteScope.class;
        }

        @Override
        public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                      NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
            return scope;
        }
    }

    private static UsernamePasswordAuthenticationToken auth(UUID userId) {
        return new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of());
    }

    private static DocumentService.FolderData folder(String nameEn, String nameEs,
                                                      boolean standard, String requiresPermission) {
        return new DocumentService.FolderData(UUID.randomUUID(), nameEn, nameEs,
                standard, requiresPermission, 0, 0L);
    }
}
