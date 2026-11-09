package org.andre.construx.shared.demo;

import org.andre.construx.modules.finance.model.ExpenseCategory;
import org.andre.construx.modules.finance.service.ExpenseService;
import org.andre.construx.modules.identity.service.AccountService;
import org.andre.construx.modules.organization.service.InvitationService;
import org.andre.construx.modules.organization.service.OrganizationService;
import org.andre.construx.modules.project.model.ProjectStatus;
import org.andre.construx.modules.project.model.TaskRole;
import org.andre.construx.modules.project.service.ActivityService;
import org.andre.construx.modules.project.service.ActivityService.TeamInput;
import org.andre.construx.modules.project.service.GoalService;
import org.andre.construx.modules.project.service.ProjectAccessService;
import org.andre.construx.modules.project.service.ProjectService;
import org.andre.construx.modules.project.service.ProjectService.ProjectInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Seeds a demo company on startup, only under the {@code demo} profile (the dev stack). Everything is created
 * through the domain services, acting as the seeded owner, so the data obeys the same invariants as real data.
 *
 * <p>All demo accounts share the password in {@code construx.demo.password}; without it nothing is seeded.
 * Seeding is skipped when the owner already belongs to the demo company, and runs in one transaction.</p>
 */
@Component
@Profile("demo")
public class DemoSeeder {
    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);

    static final String OWNER_EMAIL = "owner@demo.construx";
    static final String COMPANY = "Constructora del Norte S.A.";

    /** Demo staff: key, email, full name, organization role. */
    private record Person(String key, String email, String fullName, String roleCode) {}

    private static final List<Person> STAFF = List.of(
            new Person("carmen", "carmen.ruiz@demo.construx", "Carmen Ruiz", "SITE_MANAGER"),
            new Person("luis", "luis.garcia@demo.construx", "Luis García", "SUPERINTENDENT"),
            new Person("elena", "elena.torres@demo.construx", "Elena Torres", "FINANCE"),
            new Person("marcos", "marcos.pizarro@demo.construx", "Marcos Pizarro", "WORKER"),
            new Person("andrea", "andrea.silva@demo.construx", "Andrea Silva", "WORKER"),
            new Person("roberto", "roberto.vega@demo.construx", "Roberto Vega", "MEMBER"));

    private final AccountService accounts;
    private final OrganizationService organizations;
    private final InvitationService invitations;
    private final ProjectService projects;
    private final ProjectAccessService accesses;
    private final GoalService goals;
    private final ActivityService activities;
    private final ExpenseService expenses;
    private final String password;

    public DemoSeeder(AccountService accounts, OrganizationService organizations, InvitationService invitations,
                      ProjectService projects, ProjectAccessService accesses, GoalService goals,
                      ActivityService activities, ExpenseService expenses,
                      @Value("${construx.demo.password:}") String password) {
        this.accounts = accounts;
        this.organizations = organizations;
        this.invitations = invitations;
        this.projects = projects;
        this.accesses = accesses;
        this.goals = goals;
        this.activities = activities;
        this.expenses = expenses;
        this.password = password;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seed() {
        if (password == null || password.isBlank()) {
            log.warn("Demo profile is active but construx.demo.password is not set; skipping demo seed");
            return;
        }
        UUID owner = accounts.provisionVerified(OWNER_EMAIL, password, "Valeria Ortiz");
        if (organizations.listMine(owner).stream().anyMatch(o -> o.name().equals(COMPANY))) {
            log.info("Demo company already present; skipping demo seed");
            return;
        }
        UUID org = organizations.create(owner, COMPANY).id();
        Map<String, UUID> memberships = new HashMap<>();
        for (Person person : STAFF) {
            UUID user = accounts.provisionVerified(person.email(), password, person.fullName());
            memberships.put(person.key(), invitations.enroll(owner, org, user, person.roleCode()));
        }
        Seeding seeding = new Seeding(owner, org, memberships);
        seeding.torreResidencialNorte();
        seeding.centroComercialBelgrano();
        seeding.complejoLasAcacias();
        log.info("Demo company seeded");
    }

    /** One seeding run: every call acts as the owner inside the demo company. */
    private final class Seeding {
        private final UUID owner;
        private final UUID org;
        private final Map<String, UUID> memberships;
        private UUID site;
        private UUID goal;
        private final Map<String, UUID> siteAccess = new HashMap<>();

        Seeding(UUID owner, UUID org, Map<String, UUID> memberships) {
            this.owner = owner;
            this.org = org;
            this.memberships = memberships;
        }

        void torreResidencialNorte() {
            site("Torre Residencial Norte", "Av. Libertador 4520, Buenos Aires", ld(2025, 3, 15), ld(2026, 6, 30),
                    ProjectStatus.ACTIVE, "4500000.00", "carmen", "luis", "marcos", "andrea", "roberto");

            goal("Excavación y fundaciones", instant(2025, 3, 15), instant(2025, 8, 31));
            done("Relevamiento topográfico del terreno", "carmen", instant(2025, 3, 15), instant(2025, 3, 28));
            done("Ensayo de suelos y estudios previos", "luis", instant(2025, 3, 20), instant(2025, 4, 10));
            done("Excavación general — zona A", "marcos", instant(2025, 4, 1), instant(2025, 5, 15));
            done("Excavación general — zona B", "andrea", instant(2025, 4, 15), instant(2025, 5, 30));
            done("Encofrado y hormigonado de fundaciones", "marcos", instant(2025, 6, 1), instant(2025, 8, 10));
            done("Impermeabilización de fundaciones", "roberto", instant(2025, 8, 1), instant(2025, 8, 31));

            goal("Estructura y losa", instant(2025, 9, 1), instant(2026, 1, 31));
            done("Armadura columnas piso 1 al 4", "marcos", instant(2025, 9, 1), instant(2025, 10, 15));
            done("Hormigonado columnas y vigas — tramo 1", "luis", instant(2025, 10, 1), instant(2025, 11, 20));
            done("Armadura y hormigonado losa piso 2", "andrea", instant(2025, 11, 1), instant(2025, 12, 15));
            inProgress("Armadura y hormigonado losa piso 3", "marcos", instant(2025, 12, 1), instant(2026, 1, 15));
            inProgress("Montaje escalera metálica — tramo 1", "roberto", instant(2025, 12, 15), instant(2026, 1, 31));

            goal("Instalaciones", instant(2026, 2, 1), instant(2026, 4, 30));
            inProgress("Plomería — red de agua fría piso 1-3", "andrea", instant(2026, 2, 1), instant(2026, 3, 15));
            inProgress("Instalación eléctrica BT — tablero gral", "marcos", instant(2026, 2, 15), instant(2026, 3, 30));
            task("Instalación gas natural — troncal", "luis", instant(2026, 3, 1), instant(2026, 4, 10));
            task("Plomería — red de agua caliente", "andrea", instant(2026, 3, 15), instant(2026, 4, 20));
            task("Cableado estructurado y telecomunicaciones", "roberto", instant(2026, 3, 20), instant(2026, 4, 30));

            goal("Terminaciones y entrega", instant(2026, 5, 1), instant(2026, 6, 30));
            task("Revestimiento de fachada — paneles", "carmen", instant(2026, 5, 1), instant(2026, 5, 20));
            task("Pintura interior — piso 1 al 4", "andrea", instant(2026, 5, 10), instant(2026, 6, 5));
            task("Colocación de aberturas y carpintería", "marcos", instant(2026, 5, 15), instant(2026, 6, 10));
            task("Limpieza final y entrega de obra", "carmen", instant(2026, 6, 15), instant(2026, 6, 30));

            expense("320000.00", ld(2025, 4, 10), "Hormigón premezclado — fundaciones", ExpenseCategory.MATERIALS);
            expense("185000.00", ld(2025, 5, 20), "Hierro corrugado Ø18mm — 40 ton", ExpenseCategory.MATERIALS);
            expense("420000.00", ld(2025, 6, 5), "Mano de obra — excavación y hormigón", ExpenseCategory.LABOR);
            expense("95000.00", ld(2025, 7, 14), "Alquiler grúa torre — 2 semanas", ExpenseCategory.EQUIPMENT);
            expense("210000.00", ld(2025, 9, 3), "Acero estructural columnas", ExpenseCategory.MATERIALS);
            expense("380000.00", ld(2025, 10, 15), "Mano de obra — estructura", ExpenseCategory.LABOR);
            expense("68000.00", ld(2025, 11, 20), "Encofrados metálicos", ExpenseCategory.EQUIPMENT);
            expense("130000.00", ld(2026, 2, 8), "Instalación eléctrica BT", ExpenseCategory.MATERIALS);
            expense("54000.00", ld(2026, 2, 20), "Cañerías PVC y accesorios plomería", ExpenseCategory.MATERIALS);
            expense("92000.00", ld(2026, 3, 5), "Mano de obra — instalaciones", ExpenseCategory.LABOR);
        }

        void centroComercialBelgrano() {
            site("Centro Comercial Belgrano", "Cabildo 2134, Buenos Aires", ld(2025, 7, 1), ld(2026, 12, 31),
                    ProjectStatus.ACTIVE, "2200000.00", "luis", "elena", "marcos", "roberto");

            goal("Demolición y preparación", instant(2025, 7, 1), instant(2025, 10, 31));
            done("Relevamiento del local existente", "elena", instant(2025, 7, 1), instant(2025, 7, 15));
            done("Demolición controlada estructura interior", "marcos", instant(2025, 7, 10), instant(2025, 8, 20));
            done("Retiro y disposición de escombros", "roberto", instant(2025, 8, 1), instant(2025, 9, 5));
            done("Preparación de losa para nueva estructura", "luis", instant(2025, 9, 1), instant(2025, 10, 31));

            goal("Estructura y cerramientos", instant(2025, 11, 1), instant(2026, 5, 31));
            done("Columnas y vigas de acero — nivel 0", "luis", instant(2025, 11, 1), instant(2025, 12, 15));
            done("Cerramientos exteriores — fachada sur", "marcos", instant(2025, 12, 1), instant(2026, 1, 31));
            inProgress("Cerramientos exteriores — fachada norte", "roberto", instant(2026, 1, 15), instant(2026, 3, 15));
            inProgress("Mezzanine estructura metálica", "luis", instant(2026, 2, 1), instant(2026, 4, 30));
            task("Terminación de fachada vidriada", "marcos", instant(2026, 3, 1), instant(2026, 5, 31));

            goal("Interiores e instalaciones", instant(2026, 6, 1), instant(2026, 12, 31));
            task("Tabiques interiores — planta baja", "marcos", instant(2026, 6, 1), instant(2026, 7, 31));
            task("Instalación eléctrica — locales comerciales", "roberto", instant(2026, 6, 15), instant(2026, 8, 31));
            task("Sistema contra incendios", "luis", instant(2026, 7, 1), instant(2026, 9, 30));
            task("Climatización HVAC — planta baja y mezzanine", "marcos", instant(2026, 8, 1), instant(2026, 10, 31));
            task("Pisos y revestimientos locales", "roberto", instant(2026, 9, 1), instant(2026, 11, 30));
            task("Señalética y cartelería comercial", "luis", instant(2026, 10, 1), instant(2026, 12, 31));

            expense("155000.00", ld(2025, 7, 18), "Demolición controlada local existente", ExpenseCategory.LABOR);
            expense("90000.00", ld(2025, 8, 5), "Retiro y disposición de escombros", ExpenseCategory.OTHER);
            expense("270000.00", ld(2025, 11, 12), "Bloques de hormigón — muros exteriores", ExpenseCategory.MATERIALS);
            expense("310000.00", ld(2025, 12, 20), "Mano de obra — estructura metálica", ExpenseCategory.LABOR);
            expense("48000.00", ld(2026, 1, 15), "Alquiler andamios modulares", ExpenseCategory.EQUIPMENT);
            expense("180000.00", ld(2026, 2, 1), "Perfiles de acero — cerramientos", ExpenseCategory.MATERIALS);
            expense("75000.00", ld(2026, 3, 10), "Vidrios templados fachada norte", ExpenseCategory.MATERIALS);
        }

        void complejoLasAcacias() {
            site("Complejo Las Acacias", "Ruta 8 km 42, Pilar, Buenos Aires", ld(2026, 2, 1), ld(2027, 9, 30),
                    ProjectStatus.PLANNED, "6800000.00", "carmen", "andrea");

            goal("Planificación y licitaciones", instant(2026, 2, 1), instant(2026, 6, 30));
            done("Estudio de suelos y relevamiento topográfico", "carmen", instant(2026, 2, 1), instant(2026, 3, 15));
            done("Anteproyecto arquitectónico", "carmen", instant(2026, 2, 15), instant(2026, 4, 30));
            inProgress("Redacción de pliegos y bases de licitación", "andrea", instant(2026, 4, 1), instant(2026, 5, 31));
            task("Publicación y apertura de sobres", "carmen", instant(2026, 5, 1), instant(2026, 6, 30));

            goal("Movimiento de suelos", instant(2026, 7, 1), instant(2026, 12, 31));
            task("Desmonte y limpieza del terreno", "andrea", instant(2026, 7, 1), instant(2026, 8, 31));
            task("Excavación para infraestructura principal", "carmen", instant(2026, 8, 1), instant(2026, 10, 31));
            task("Compactación y nivelación general", "andrea", instant(2026, 10, 1), instant(2026, 12, 31));

            expense("42000.00", ld(2026, 2, 10), "Estudio de suelos y relevamiento topográfico", ExpenseCategory.OTHER);
            expense("28000.00", ld(2026, 3, 5), "Honorarios consultoría técnica — anteproyecto", ExpenseCategory.OTHER);
            expense("15000.00", ld(2026, 4, 12), "Redacción de pliegos licitación", ExpenseCategory.OTHER);
        }

        private void site(String name, String address, LocalDate start, LocalDate end, ProjectStatus status,
                          String budget, String... staff) {
            site = projects.create(owner, org, new ProjectInput(name, address, start, end, status, null)).id();
            projects.assignBudget(owner, org, site, new BigDecimal(budget));
            siteAccess.clear();
            for (String key : staff) {
                siteAccess.put(key, accesses.add(owner, org, site, memberships.get(key)).accessId());
            }
        }

        private void goal(String name, Instant startAt, Instant dueAt) {
            goal = goals.create(owner, org, site, name, startAt, dueAt, null).id();
        }

        /** A task to do, led by the given site staff member. */
        private UUID task(String name, String lead, Instant startAt, Instant dueAt) {
            return activities.create(owner, org, site, goal, name, startAt, dueAt,
                    List.of(new TeamInput(siteAccess.get(lead), TaskRole.LEAD)), null, null).id();
        }

        private void inProgress(String name, String lead, Instant startAt, Instant dueAt) {
            activities.start(owner, org, site, goal, task(name, lead, startAt, dueAt));
        }

        private void done(String name, String lead, Instant startAt, Instant dueAt) {
            UUID activity = task(name, lead, startAt, dueAt);
            activities.start(owner, org, site, goal, activity);
            activities.complete(owner, org, site, goal, activity);
        }

        private void expense(String amount, LocalDate spentOn, String description, ExpenseCategory category) {
            expenses.create(owner, org, site, new BigDecimal(amount), spentOn, description, category);
        }
    }

    private static Instant instant(int year, int month, int day) {
        return LocalDate.of(year, month, day).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static LocalDate ld(int year, int month, int day) {
        return LocalDate.of(year, month, day);
    }
}
