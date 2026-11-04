package org.andre.construx.modules.documents.controller;

import org.andre.construx.modules.documents.service.DocumentService;
import org.andre.construx.shared.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

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

    private final UUID orgId    = UUID.fromString("11111111-0000-0000-0000-000000000001");
    private final UUID siteId   = UUID.fromString("22222222-0000-0000-0000-000000000002");
    private final UUID folderId = UUID.fromString("33333333-0000-0000-0000-000000000003");
    private final UUID userId   = UUID.fromString("44444444-0000-0000-0000-000000000004");

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new DocumentController(documentService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    // ── GET /document-folders — financial folder filtering ───────────────

    @Test
    void listFoldersReturnsOnlyVisibleFolders() throws Exception {
        DocumentService.FolderData general  = folder("Reports", "Reportes", false, null);
        // financial folder is excluded by the service — service returns only visible folders
        when(documentService.listFolders(eq(userId), eq(orgId), eq(siteId)))
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
        when(documentService.listFolders(eq(userId), eq(orgId), eq(siteId)))
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
                .when(documentService).deleteFolder(eq(userId), eq(orgId), eq(siteId), eq(folderId));

        mvc.perform(delete("/api/organizations/{org}/projects/{site}/document-folders/{folderId}",
                        orgId, siteId, folderId)
                        .principal(auth(userId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void deletingCustomFolderReturns204() throws Exception {
        doNothing().when(documentService).deleteFolder(eq(userId), eq(orgId), eq(siteId), eq(folderId));

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
        when(documentService.search(eq(userId), eq(orgId), eq(siteId), eq("presupuesto")))
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

    private static UsernamePasswordAuthenticationToken auth(UUID userId) {
        return new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of());
    }

    private static DocumentService.FolderData folder(String nameEn, String nameEs,
                                                      boolean standard, String requiresPermission) {
        return new DocumentService.FolderData(UUID.randomUUID(), nameEn, nameEs,
                standard, requiresPermission, 0, 0L);
    }
}
