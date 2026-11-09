package org.andre.construx.modules.documents.service;

import org.andre.construx.modules.documents.model.DocumentEntry;
import org.andre.construx.modules.documents.model.DocumentFolder;
import org.andre.construx.modules.documents.repository.DocumentEntryRepository;
import org.andre.construx.modules.documents.repository.DocumentFolderRepository;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.model.PermissionScope;
import org.andre.construx.modules.project.access.SiteScope;
import org.andre.construx.modules.project.access.TestScopes;
import org.andre.construx.modules.project.model.Project;
import org.andre.construx.shared.storage.ObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DocumentServiceTest {

    private DocumentFolderRepository folderRepo;
    private DocumentEntryRepository entryRepo;
    private ObjectStorage storage;
    private DocumentService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID orgId  = UUID.randomUUID();
    private final UUID siteId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        folderRepo    = mock(DocumentFolderRepository.class);
        entryRepo     = mock(DocumentEntryRepository.class);
        storage       = mock(ObjectStorage.class);
        service = new DocumentService(folderRepo, entryRepo, storage);

        when(folderRepo.save(any(DocumentFolder.class))).thenAnswer(inv -> inv.getArgument(0));
        when(entryRepo.save(any(DocumentEntry.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ── Seed standard folders ────────────────────────────────────────────

    @Test
    void seedCreatesSevenFolders() {
        Project project = mockProject(siteId);
        when(folderRepo.existsByProjectIdAndNameEn(eq(siteId), any())).thenReturn(false);

        service.seedStandardFolders(siteId, project);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<DocumentFolder> captor = ArgumentCaptor.forClass(DocumentFolder.class);
        verify(folderRepo, times(7)).save(captor.capture());
        List<DocumentFolder> saved = captor.getAllValues();
        assertEquals(7, saved.size());
    }

    @Test
    void seedFolderNamesMatchSpec() {
        Project project = mockProject(siteId);
        when(folderRepo.existsByProjectIdAndNameEn(eq(siteId), any())).thenReturn(false);

        service.seedStandardFolders(siteId, project);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<DocumentFolder> captor = ArgumentCaptor.forClass(DocumentFolder.class);
        verify(folderRepo, times(7)).save(captor.capture());
        List<DocumentFolder> saved = captor.getAllValues();

        assertEquals("Logbook",                 saved.get(0).getNameEn());
        assertEquals("Photo Evidence",          saved.get(1).getNameEn());
        assertEquals("Reports",                 saved.get(2).getNameEn());
        assertEquals("Plans & Documents",       saved.get(3).getNameEn());
        assertEquals("Budget & APU",            saved.get(4).getNameEn());
        assertEquals("Purchases & Requisitions",saved.get(5).getNameEn());
        assertEquals("Estimates & Billing",     saved.get(6).getNameEn());

        assertEquals("Bitácora",                saved.get(0).getNameEs());
        assertEquals("Evidencia fotográfica",   saved.get(1).getNameEs());
        assertEquals("Reportes",                saved.get(2).getNameEs());
        assertEquals("Planos y documentos",     saved.get(3).getNameEs());
        assertEquals("Presupuesto y APU",       saved.get(4).getNameEs());
        assertEquals("Compras y requisiciones", saved.get(5).getNameEs());
        assertEquals("Estimaciones y cobranza", saved.get(6).getNameEs());
    }

    @Test
    void financialFoldersHaveViewPricesPermission() {
        Project project = mockProject(siteId);
        when(folderRepo.existsByProjectIdAndNameEn(eq(siteId), any())).thenReturn(false);

        service.seedStandardFolders(siteId, project);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<DocumentFolder> captor = ArgumentCaptor.forClass(DocumentFolder.class);
        verify(folderRepo, times(7)).save(captor.capture());
        List<DocumentFolder> saved = captor.getAllValues();

        assertNull(saved.get(0).getRequiresPermission(), "Logbook should have no permission requirement");
        assertNull(saved.get(1).getRequiresPermission());
        assertNull(saved.get(2).getRequiresPermission());
        assertNull(saved.get(3).getRequiresPermission());
        assertEquals("VIEW_PRICES", saved.get(4).getRequiresPermission(), "Budget & APU requires VIEW_PRICES");
        assertEquals("VIEW_PRICES", saved.get(5).getRequiresPermission(), "Purchases & Requisitions requires VIEW_PRICES");
        assertEquals("VIEW_PRICES", saved.get(6).getRequiresPermission(), "Estimates & Billing requires VIEW_PRICES");
    }

    @Test
    void allSeededFoldersAreStandard() {
        Project project = mockProject(siteId);
        when(folderRepo.existsByProjectIdAndNameEn(eq(siteId), any())).thenReturn(false);

        service.seedStandardFolders(siteId, project);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<DocumentFolder> captor = ArgumentCaptor.forClass(DocumentFolder.class);
        verify(folderRepo, times(7)).save(captor.capture());
        assertTrue(captor.getAllValues().stream().allMatch(DocumentFolder::isStandard),
                "All seeded folders must have is_standard = true");
    }

    @Test
    void seedIsIdempotentWhenFolderAlreadyExists() {
        Project project = mockProject(siteId);
        // First 6 exist, only the 7th needs saving
        when(folderRepo.existsByProjectIdAndNameEn(eq(siteId), any())).thenReturn(true);
        when(folderRepo.existsByProjectIdAndNameEn(eq(siteId), eq("Estimates & Billing"))).thenReturn(false);

        service.seedStandardFolders(siteId, project);

        verify(folderRepo, times(1)).save(any(DocumentFolder.class));
    }

    @Test
    void seedSkipsAllFoldersWhenAllExist() {
        Project project = mockProject(siteId);
        when(folderRepo.existsByProjectIdAndNameEn(any(), any())).thenReturn(true);

        service.seedStandardFolders(siteId, project);

        verify(folderRepo, never()).save(any(DocumentFolder.class));
    }

    // ── Permission-based folder filtering ────────────────────────────────

    @Test
    void listFoldersExcludesFinancialFoldersWhenCallerLacksViewPrices() {
        Project project = mockProject(siteId);
        SiteScope site = scope(project, false);

        DocumentFolder general  = folder(project, "Reports", "Reportes", false, null, 2);
        DocumentFolder financial = folder(project, "Budget & APU", "Presupuesto y APU", false, "VIEW_PRICES", 4);
        when(folderRepo.findByProjectIdOrderByPositionAscNameEnAsc(siteId))
                .thenReturn(List.of(general, financial));
        when(entryRepo.countByFolder_Id(any())).thenReturn(0L);

        List<DocumentService.FolderData> result = service.listFolders(site);

        assertEquals(1, result.size(), "Financial folder must be excluded");
        assertEquals("Reports", result.get(0).nameEn());
    }

    @Test
    void listFoldersIncludesFinancialFoldersWhenCallerHasViewPrices() {
        Project project = mockProject(siteId);
        SiteScope site = scope(project, true);

        DocumentFolder general  = folder(project, "Reports", "Reportes", false, null, 2);
        DocumentFolder financial = folder(project, "Budget & APU", "Presupuesto y APU", false, "VIEW_PRICES", 4);
        when(folderRepo.findByProjectIdOrderByPositionAscNameEnAsc(siteId))
                .thenReturn(List.of(general, financial));
        when(entryRepo.countByFolder_Id(any())).thenReturn(0L);

        List<DocumentService.FolderData> result = service.listFolders(site);

        assertEquals(2, result.size(), "Both folders must be visible");
    }

    // ── Standard folder delete protection ────────────────────────────────

    @Test
    void deleteFolderThrowsAccessDeniedForStandardFolder() {
        UUID folderId = UUID.randomUUID();
        Project project = mockProject(siteId);

        DocumentFolder standardFolder = folder(project, "Logbook", "Bitácora", true, null, 0);
        UUID standardFolderId = UUID.randomUUID();
        when(folderRepo.findById(any())).thenReturn(Optional.of(standardFolder));

        assertThrows(AccessDeniedException.class,
                () -> service.deleteFolder(scope(project, false), standardFolderId),
                "Deleting a standard folder must throw AccessDeniedException");
    }

    @Test
    void searchFiltersFinancialFolderHitsWhenCallerLacksViewPrices() {
        Project project = mockProject(siteId);
        SiteScope site = scope(project, false);

        DocumentFolder publicFolder   = folder(project, "Reports", "Reportes", true, null, 2);
        DocumentFolder financialFolder = folder(project, "Budget & APU", "Presupuesto y APU", true, "VIEW_PRICES", 4);

        UUID publicEntryId   = UUID.randomUUID();
        UUID financialEntryId = UUID.randomUUID();
        DocumentEntry publicEntry   = entry(publicEntryId, publicFolder, siteId, orgId, "presupuesto-public.pdf");
        DocumentEntry financialEntry = entry(financialEntryId, financialFolder, siteId, orgId, "presupuesto-budget.pdf");

        when(entryRepo.searchByFileName(siteId, "presupuesto"))
                .thenReturn(List.of(publicEntry, financialEntry));
        when(storage.presignDownload(any(), any(Duration.class)))
                .thenReturn(URI.create("https://example.test/download"));

        List<DocumentService.SearchHit> hits = service.search(site, "presupuesto");

        assertEquals(1, hits.size(), "Financial folder hits must be excluded");
        assertEquals("presupuesto-public.pdf", hits.get(0).fileName());
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** Caller who can view and manage the site; VIEW_PRICES only when asked. */
    private SiteScope scope(Project project, boolean viewPrices) {
        Map<PermissionAction, PermissionScope> grants = new EnumMap<>(PermissionAction.class);
        grants.put(PermissionAction.VIEW_PROJECT, PermissionScope.ORGANIZATION);
        grants.put(PermissionAction.MANAGE_PROJECT, PermissionScope.ORGANIZATION);
        if (viewPrices) grants.put(PermissionAction.VIEW_PRICES, PermissionScope.ORGANIZATION);
        return TestScopes.site(orgId, project, mock(OrganizationMembership.class), grants, true);
    }

    private Project mockProject(UUID id) {
        Project p = mock(Project.class);
        when(p.getId()).thenReturn(id);
        return p;
    }

    private DocumentFolder folder(Project project, String nameEn, String nameEs,
                                   boolean standard, String requiresPermission, int position) {
        return new DocumentFolder(project, nameEn, nameEs, standard, requiresPermission, position);
    }

    private DocumentEntry entry(UUID id, DocumentFolder folder, UUID projectId, UUID organizationId, String fileName) {
        return new DocumentEntry(id, folder, projectId, organizationId,
                "orgs/" + organizationId + "/projects/" + projectId + "/documents/" + id,
                fileName, null, null, "manual", userId);
    }

}
