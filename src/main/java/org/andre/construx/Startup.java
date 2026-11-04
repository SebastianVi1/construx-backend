package org.andre.construx;

import org.andre.construx.modules.finance.model.Expense;
import org.andre.construx.modules.finance.model.ExpenseCategory;
import org.andre.construx.modules.finance.repository.ExpenseRepository;
import org.andre.construx.modules.identity.model.UserAccount;
import org.andre.construx.modules.identity.repository.UserAccountRepository;
import org.andre.construx.modules.identity.service.AccountService;
import org.andre.construx.modules.organization.model.Organization;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.OrganizationRole;
import org.andre.construx.modules.organization.repository.OrganizationMembershipRepository;
import org.andre.construx.modules.documents.service.DocumentService;
import org.andre.construx.modules.organization.repository.OrganizationRepository;
import org.andre.construx.modules.project.model.Activity;
import org.andre.construx.modules.project.model.Goal;
import org.andre.construx.modules.project.model.Project;
import org.andre.construx.modules.project.model.ProjectAccess;
import org.andre.construx.modules.project.model.ProjectStatus;
import org.andre.construx.modules.project.repository.ActivityRepository;
import org.andre.construx.modules.project.repository.GoalRepository;
import org.andre.construx.modules.project.repository.ProjectAccessRepository;
import org.andre.construx.modules.project.repository.ProjectRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

@Component
public class Startup {

    @Autowired AccountService accountService;
    @Autowired DocumentService documentService;
    @Autowired UserAccountRepository userAccounts;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired OrganizationMembershipRepository membershipRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectAccessRepository accessRepository;
    @Autowired GoalRepository goalRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired ExpenseRepository expenseRepository;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void onApplicationReady() {
        System.out.println("[Startup] Server initialized and ready to accept requests.");
        seedDemoCompany();
    }

    private void seedDemoCompany() {
        accountService.register("sebastianvh86@gmail.com", "infinityss12", "Sebastián Vera");
        accountService.register("carmen.ruiz@demo.construx",  "Demo1234!", "Carmen Ruiz");
        accountService.register("luis.garcia@demo.construx",   "Demo1234!", "Luis García");
        accountService.register("elena.torres@demo.construx",  "Demo1234!", "Elena Torres");
        accountService.register("marcos.pizarro@demo.construx","Demo1234!", "Marcos Pizarro");
        accountService.register("andrea.silva@demo.construx",  "Demo1234!", "Andrea Silva");
        accountService.register("roberto.vega@demo.construx",  "Demo1234!", "Roberto Vega");

        verifyEmail("sebastianvh86@gmail.com");
        verifyEmail("carmen.ruiz@demo.construx");
        verifyEmail("luis.garcia@demo.construx");
        verifyEmail("elena.torres@demo.construx");
        verifyEmail("marcos.pizarro@demo.construx");
        verifyEmail("andrea.silva@demo.construx");
        verifyEmail("roberto.vega@demo.construx");

        if (organizationRepository.existsByName("Constructora del Norte S.A.")) {
            System.out.println("[Startup] Seed data already present — skipping organization setup.");
            return;
        }

        UserAccount owner   = user("sebastianvh86@gmail.com");
        UserAccount carmen  = user("carmen.ruiz@demo.construx");
        UserAccount luis    = user("luis.garcia@demo.construx");
        UserAccount elena   = user("elena.torres@demo.construx");
        UserAccount marcos  = user("marcos.pizarro@demo.construx");
        UserAccount andrea  = user("andrea.silva@demo.construx");
        UserAccount roberto = user("roberto.vega@demo.construx");

        Organization org = organizationRepository.save(new Organization("Constructora del Norte S.A.", owner));

        OrganizationMembership mCarmen  = membershipRepository.save(new OrganizationMembership(org, carmen,  role(org, "SITE_MANAGER")));
        OrganizationMembership mLuis    = membershipRepository.save(new OrganizationMembership(org, luis,    role(org, "SUPERINTENDENT")));
        OrganizationMembership mElena   = membershipRepository.save(new OrganizationMembership(org, elena,   role(org, "FINANCE")));
        OrganizationMembership mMarcos  = membershipRepository.save(new OrganizationMembership(org, marcos,  role(org, "WORKER")));
        OrganizationMembership mAndrea  = membershipRepository.save(new OrganizationMembership(org, andrea,  role(org, "WORKER")));
        OrganizationMembership mRoberto = membershipRepository.save(new OrganizationMembership(org, roberto, role(org, "MEMBER")));

        seedTorreResidencialNorte(org, mCarmen, mLuis, mMarcos, mAndrea, mRoberto);
        seedCentroComercialBelgrano(org, mLuis, mElena, mMarcos, mRoberto);
        seedComplejoLasAcacias(org, mCarmen, mAndrea);

        System.out.println("[Startup] Demo company seeded successfully.");
    }

    // ── Site 1 ───────────────────────────────────────────────────────────────

    private void seedTorreResidencialNorte(Organization org,
            OrganizationMembership mCarmen, OrganizationMembership mLuis,
            OrganizationMembership mMarcos, OrganizationMembership mAndrea,
            OrganizationMembership mRoberto) {

        Project site = new Project(org, "Torre Residencial Norte");
        site.describe("Av. Libertador 4520, Buenos Aires", ld(2025, 3, 15), ld(2026, 6, 30));
        site.changeStatus(ProjectStatus.ACTIVE);
        site.assignBudget(new BigDecimal("4500000.00"), BigDecimal.ZERO);
        site = projectRepository.save(site);
        documentService.seedStandardFolders(site.getId(), site);

        ProjectAccess aCarmen  = accessRepository.save(new ProjectAccess(site, mCarmen));
        ProjectAccess aLuis    = accessRepository.save(new ProjectAccess(site, mLuis));
        ProjectAccess aMarcos  = accessRepository.save(new ProjectAccess(site, mMarcos));
        ProjectAccess aAndrea  = accessRepository.save(new ProjectAccess(site, mAndrea));
        ProjectAccess aRoberto = accessRepository.save(new ProjectAccess(site, mRoberto));

        // Phase 1 — DONE
        Goal g1 = goalRepository.save(new Goal(site, "Excavación y fundaciones", instant(2025, 3, 15), instant(2025, 8, 31)));
        done(task(g1, "Relevamiento topográfico del terreno",    aCarmen,  instant(2025, 3, 15), instant(2025, 3, 28)));
        done(task(g1, "Ensayo de suelos y estudios previos",      aLuis,   instant(2025, 3, 20), instant(2025, 4, 10)));
        done(task(g1, "Excavación general — zona A",              aMarcos, instant(2025, 4, 1),  instant(2025, 5, 15)));
        done(task(g1, "Excavación general — zona B",              aAndrea, instant(2025, 4, 15), instant(2025, 5, 30)));
        done(task(g1, "Encofrado y hormigonado de fundaciones",   aMarcos, instant(2025, 6, 1),  instant(2025, 8, 10)));
        done(task(g1, "Impermeabilización de fundaciones",        aRoberto,instant(2025, 8, 1),  instant(2025, 8, 31)));

        // Phase 2 — mostly done
        Goal g2 = goalRepository.save(new Goal(site, "Estructura y losa", instant(2025, 9, 1), instant(2026, 1, 31)));
        done(task(g2,  "Armadura columnas piso 1 al 4",           aMarcos, instant(2025, 9, 1),  instant(2025, 10, 15)));
        done(task(g2,  "Hormigonado columnas y vigas — tramo 1",  aLuis,   instant(2025, 10, 1), instant(2025, 11, 20)));
        done(task(g2,  "Armadura y hormigonado losa piso 2",      aAndrea, instant(2025, 11, 1), instant(2025, 12, 15)));
        prog(task(g2,  "Armadura y hormigonado losa piso 3",      aMarcos, instant(2025, 12, 1), instant(2026, 1, 15)));
        prog(task(g2,  "Montaje escalera metálica — tramo 1",     aRoberto,instant(2025, 12, 15),instant(2026, 1, 31)));

        // Phase 3 — in progress
        Goal g3 = goalRepository.save(new Goal(site, "Instalaciones", instant(2026, 2, 1), instant(2026, 4, 30)));
        prog(task(g3, "Plomería — red de agua fría piso 1-3",     aAndrea, instant(2026, 2, 1),  instant(2026, 3, 15)));
        prog(task(g3, "Instalación eléctrica BT — tablero gral",  aMarcos, instant(2026, 2, 15), instant(2026, 3, 30)));
        task(g3,      "Instalación gas natural — troncal",        aLuis,   instant(2026, 3, 1),  instant(2026, 4, 10));
        task(g3,      "Plomería — red de agua caliente",           aAndrea, instant(2026, 3, 15), instant(2026, 4, 20));
        task(g3,      "Cableado estructurado y telecomunicaciones",aRoberto,instant(2026, 3, 20), instant(2026, 4, 30));

        // Phase 4 — pending
        Goal g4 = goalRepository.save(new Goal(site, "Terminaciones y entrega", instant(2026, 5, 1), instant(2026, 6, 30)));
        task(g4, "Revestimiento de fachada — paneles",            aCarmen, instant(2026, 5, 1),  instant(2026, 5, 20));
        task(g4, "Pintura interior — piso 1 al 4",                aAndrea, instant(2026, 5, 10), instant(2026, 6, 5));
        task(g4, "Colocación de aberturas y carpintería",         aMarcos, instant(2026, 5, 15), instant(2026, 6, 10));
        task(g4, "Limpieza final y entrega de obra",              aCarmen, instant(2026, 6, 15), instant(2026, 6, 30));

        // Expenses
        expenseRepository.save(expense(site, "320000.00", ld(2025, 4, 10), "Hormigón premezclado — fundaciones",    ExpenseCategory.MATERIALS));
        expenseRepository.save(expense(site, "185000.00", ld(2025, 5, 20), "Hierro corrugado Ø18mm — 40 ton",       ExpenseCategory.MATERIALS));
        expenseRepository.save(expense(site, "420000.00", ld(2025, 6, 5),  "Mano de obra — excavación y hormigón",  ExpenseCategory.LABOR));
        expenseRepository.save(expense(site, "95000.00",  ld(2025, 7, 14), "Alquiler grúa torre — 2 semanas",       ExpenseCategory.EQUIPMENT));
        expenseRepository.save(expense(site, "210000.00", ld(2025, 9, 3),  "Acero estructural columnas",            ExpenseCategory.MATERIALS));
        expenseRepository.save(expense(site, "380000.00", ld(2025, 10, 15),"Mano de obra — estructura",             ExpenseCategory.LABOR));
        expenseRepository.save(expense(site, "68000.00",  ld(2025, 11, 20),"Encofrados metálicos",                  ExpenseCategory.EQUIPMENT));
        expenseRepository.save(expense(site, "130000.00", ld(2026, 2, 8),  "Instalación eléctrica BT",              ExpenseCategory.MATERIALS));
        expenseRepository.save(expense(site, "54000.00",  ld(2026, 2, 20), "Cañerías PVC y accesorios plomería",    ExpenseCategory.MATERIALS));
        expenseRepository.save(expense(site, "92000.00",  ld(2026, 3, 5),  "Mano de obra — instalaciones",          ExpenseCategory.LABOR));
    }

    // ── Site 2 ───────────────────────────────────────────────────────────────

    private void seedCentroComercialBelgrano(Organization org,
            OrganizationMembership mLuis, OrganizationMembership mElena,
            OrganizationMembership mMarcos, OrganizationMembership mRoberto) {

        Project site = new Project(org, "Centro Comercial Belgrano");
        site.describe("Cabildo 2134, Buenos Aires", ld(2025, 7, 1), ld(2026, 12, 31));
        site.changeStatus(ProjectStatus.ACTIVE);
        site.assignBudget(new BigDecimal("2200000.00"), BigDecimal.ZERO);
        site = projectRepository.save(site);
        documentService.seedStandardFolders(site.getId(), site);

        ProjectAccess aLuis    = accessRepository.save(new ProjectAccess(site, mLuis));
        ProjectAccess aElena   = accessRepository.save(new ProjectAccess(site, mElena));
        ProjectAccess aMarcos  = accessRepository.save(new ProjectAccess(site, mMarcos));
        ProjectAccess aRoberto = accessRepository.save(new ProjectAccess(site, mRoberto));

        // Phase 1 — DONE
        Goal g1 = goalRepository.save(new Goal(site, "Demolición y preparación", instant(2025, 7, 1), instant(2025, 10, 31)));
        done(task(g1, "Relevamiento del local existente",          aElena,  instant(2025, 7, 1),  instant(2025, 7, 15)));
        done(task(g1, "Demolición controlada estructura interior", aMarcos, instant(2025, 7, 10), instant(2025, 8, 20)));
        done(task(g1, "Retiro y disposición de escombros",         aRoberto,instant(2025, 8, 1),  instant(2025, 9, 5)));
        done(task(g1, "Preparación de losa para nueva estructura", aLuis,   instant(2025, 9, 1),  instant(2025, 10, 31)));

        // Phase 2 — in progress
        Goal g2 = goalRepository.save(new Goal(site, "Estructura y cerramientos", instant(2025, 11, 1), instant(2026, 5, 31)));
        done(task(g2, "Columnas y vigas de acero — nivel 0",       aLuis,   instant(2025, 11, 1), instant(2025, 12, 15)));
        done(task(g2, "Cerramientos exteriores — fachada sur",     aMarcos, instant(2025, 12, 1), instant(2026, 1, 31)));
        prog(task(g2, "Cerramientos exteriores — fachada norte",   aRoberto,instant(2026, 1, 15), instant(2026, 3, 15)));
        prog(task(g2, "Mezzanine estructura metálica",             aLuis,   instant(2026, 2, 1),  instant(2026, 4, 30)));
        task(g2,      "Terminación de fachada vidriada",           aMarcos, instant(2026, 3, 1),  instant(2026, 5, 31));

        // Phase 3 — pending
        Goal g3 = goalRepository.save(new Goal(site, "Interiores e instalaciones", instant(2026, 6, 1), instant(2026, 12, 31)));
        task(g3, "Tabiques interiores — planta baja",              aMarcos, instant(2026, 6, 1),  instant(2026, 7, 31));
        task(g3, "Instalación eléctrica — locales comerciales",    aRoberto,instant(2026, 6, 15), instant(2026, 8, 31));
        task(g3, "Sistema contra incendios",                       aLuis,   instant(2026, 7, 1),  instant(2026, 9, 30));
        task(g3, "Climatización HVAC — planta baja y mezzanine",   aMarcos, instant(2026, 8, 1),  instant(2026, 10, 31));
        task(g3, "Pisos y revestimientos locales",                 aRoberto,instant(2026, 9, 1),  instant(2026, 11, 30));
        task(g3, "Señalética y cartelería comercial",              aLuis,   instant(2026, 10, 1), instant(2026, 12, 31));

        // Expenses
        expenseRepository.save(expense(site, "155000.00", ld(2025, 7, 18),  "Demolición controlada local existente",  ExpenseCategory.LABOR));
        expenseRepository.save(expense(site, "90000.00",  ld(2025, 8, 5),   "Retiro y disposición de escombros",      ExpenseCategory.OTHER));
        expenseRepository.save(expense(site, "270000.00", ld(2025, 11, 12), "Bloques de hormigón — muros exteriores", ExpenseCategory.MATERIALS));
        expenseRepository.save(expense(site, "310000.00", ld(2025, 12, 20), "Mano de obra — estructura metálica",     ExpenseCategory.LABOR));
        expenseRepository.save(expense(site, "48000.00",  ld(2026, 1, 15),  "Alquiler andamios modulares",            ExpenseCategory.EQUIPMENT));
        expenseRepository.save(expense(site, "180000.00", ld(2026, 2, 1),   "Perfiles de acero — cerramientos",       ExpenseCategory.MATERIALS));
        expenseRepository.save(expense(site, "75000.00",  ld(2026, 3, 10),  "Vidrios templados fachada norte",        ExpenseCategory.MATERIALS));
    }

    // ── Site 3 ───────────────────────────────────────────────────────────────

    private void seedComplejoLasAcacias(Organization org,
            OrganizationMembership mCarmen, OrganizationMembership mAndrea) {

        Project site = new Project(org, "Complejo Las Acacias");
        site.describe("Ruta 8 km 42, Pilar, Buenos Aires", ld(2026, 2, 1), ld(2027, 9, 30));
        site.changeStatus(ProjectStatus.PLANNED);
        site.assignBudget(new BigDecimal("6800000.00"), BigDecimal.ZERO);
        site = projectRepository.save(site);
        documentService.seedStandardFolders(site.getId(), site);

        ProjectAccess aCarmen = accessRepository.save(new ProjectAccess(site, mCarmen));
        ProjectAccess aAndrea = accessRepository.save(new ProjectAccess(site, mAndrea));

        // Phase 1 — in progress
        Goal g1 = goalRepository.save(new Goal(site, "Planificación y licitaciones", instant(2026, 2, 1), instant(2026, 6, 30)));
        done(task(g1, "Estudio de suelos y relevamiento topográfico", aCarmen, instant(2026, 2, 1),  instant(2026, 3, 15)));
        done(task(g1, "Anteproyecto arquitectónico",                  aCarmen, instant(2026, 2, 15), instant(2026, 4, 30)));
        prog(task(g1, "Redacción de pliegos y bases de licitación",   aAndrea, instant(2026, 4, 1),  instant(2026, 5, 31)));
        task(g1,      "Publicación y apertura de sobres",             aCarmen, instant(2026, 5, 1),  instant(2026, 6, 30));

        // Phase 2 — pending
        Goal g2 = goalRepository.save(new Goal(site, "Movimiento de suelos", instant(2026, 7, 1), instant(2026, 12, 31)));
        task(g2, "Desmonte y limpieza del terreno",                   aAndrea, instant(2026, 7, 1),  instant(2026, 8, 31));
        task(g2, "Excavación para infraestructura principal",         aCarmen, instant(2026, 8, 1),  instant(2026, 10, 31));
        task(g2, "Compactación y nivelación general",                 aAndrea, instant(2026, 10, 1), instant(2026, 12, 31));

        // Expenses
        expenseRepository.save(expense(site, "42000.00", ld(2026, 2, 10), "Estudio de suelos y relevamiento topográfico",  ExpenseCategory.OTHER));
        expenseRepository.save(expense(site, "28000.00", ld(2026, 3, 5),  "Honorarios consultoría técnica — anteproyecto", ExpenseCategory.OTHER));
        expenseRepository.save(expense(site, "15000.00", ld(2026, 4, 12), "Redacción de pliegos licitación",               ExpenseCategory.OTHER));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Creates and saves a task (TODO). Returns the saved Activity for further status changes. */
    private Activity task(Goal goal, String name, ProjectAccess lead, java.time.Instant startAt, java.time.Instant dueAt) {
        return activityRepository.save(new Activity(goal, name, lead, startAt, dueAt));
    }

    /** Marks the activity as IN_PROGRESS. */
    private void prog(Activity activity) {
        activity.start();
        activityRepository.save(activity);
    }

    /** Marks the activity as DONE. */
    private void done(Activity activity) {
        activity.start();
        activity.complete(activity.getDueAt());
        activityRepository.save(activity);
    }

    private void verifyEmail(String email) {
        UserAccount account = user(email);
        if (!account.isEmailVerified()) {
            account.verifyEmail();
            userAccounts.save(account);
        }
    }

    private UserAccount user(String email) {
        return userAccounts.findByEmail(email.toLowerCase()).orElseThrow(
                () -> new IllegalStateException("Expected seed user not found: " + email));
    }

    private OrganizationRole role(Organization org, String code) {
        return org.getRoles().stream()
                .filter(r -> r.getCode().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Role not found in org: " + code));
    }

    private static java.time.Instant instant(int year, int month, int day) {
        return LocalDate.of(year, month, day).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static LocalDate ld(int year, int month, int day) {
        return LocalDate.of(year, month, day);
    }

    private static Expense expense(Project site, String amount, LocalDate spentOn,
            String description, ExpenseCategory category) {
        return new Expense(site, new BigDecimal(amount), spentOn, description, category);
    }
}
