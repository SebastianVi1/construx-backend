package org.andre.construx;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.andre.construx.modules.project.model.Project;
import org.andre.construx.modules.project.service.ProjectService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Module seams of the modular monolith ({@code modules/<name>} + {@code shared}):
 * <ul>
 *   <li>a module never reads another module's {@code repository} package; it calls that module's services;</li>
 *   <li>{@code shared} depends on no module.</li>
 * </ul>
 * {@link #ALLOWED} lists the known violations as {@code Origin -> Target} (top-level classes, relative to {@code org.andre.construx}). It may only
 * shrink: a new violation fails the build, and so does an entry that no longer occurs.
 */
class ModuleBoundariesTest {
    private static final String ROOT = "org.andre.construx";
    private static final Pattern MODULE = Pattern.compile("^" + Pattern.quote(ROOT) + "\\.modules\\.([a-z]+)\\.(.*)$");

    private static final Set<String> ALLOWED = Set.of(
            "modules.floorplan.service.AssignmentViews -> modules.organization.repository.OrganizationMembershipRepository",
            "modules.floorplan.service.ZoneAssignmentService -> modules.organization.repository.OrganizationMembershipRepository",
            "modules.organization.service.InvitationService -> modules.identity.repository.UserAccountRepository",
            "modules.organization.service.InvitationService -> modules.project.repository.ProjectAccessRepository",
            "modules.organization.service.InvitationService -> modules.project.repository.ProjectRepository",
            "modules.organization.service.OrganizationService -> modules.identity.repository.UserAccountRepository",
            "shared.security.DomainAuthorization -> modules.organization.model.MembershipStatus",
            "shared.security.DomainAuthorization -> modules.organization.model.Organization",
            "shared.security.DomainAuthorization -> modules.organization.model.OrganizationMembership",
            "shared.security.DomainAuthorization -> modules.organization.model.OrganizationRole",
            "shared.security.DomainAuthorization -> modules.organization.model.Permission",
            "shared.security.DomainAuthorization -> modules.organization.model.PermissionAction",
            "shared.security.DomainAuthorization -> modules.organization.model.PermissionScope",
            "shared.security.DomainAuthorization -> modules.organization.repository.OrganizationMembershipRepository",
            "shared.security.DomainAuthorization -> modules.project.model.Project",
            "shared.security.DomainAuthorization -> modules.project.repository.ProjectAccessRepository",
            "shared.security.DomainAuthorization -> modules.project.repository.ProjectRepository");


    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
    }

    @Test
    void modulesOnlyReachOtherModulesThroughTheirServices() {
        assertOnlyAllowed(violations());
    }

    @Test
    void allowListOnlyNamesViolationsThatStillExist() {
        Set<String> stale = new TreeSet<>(ALLOWED);
        stale.removeAll(violations());
        assertTrue(stale.isEmpty(), "Fixed violations, remove them from ALLOWED:\n" + String.join("\n", stale));
    }

    @Test
    void projectServiceHandsOutNoProjectEntity() {
        Set<String> leaks = new TreeSet<>();
        for (Method method : ProjectService.class.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers())
                    && method.getGenericReturnType().getTypeName().contains(Project.class.getName())) {
                leaks.add(method.getName());
            }
        }
        assertTrue(leaks.isEmpty(), "ProjectService methods returning the Project entity: " + leaks);
    }

    private static void assertOnlyAllowed(Set<String> found) {
        Set<String> unexpected = new TreeSet<>(found);
        unexpected.removeAll(ALLOWED);
        assertTrue(unexpected.isEmpty(), "Module boundary violations:\n" + String.join("\n", unexpected));
    }

    private static Set<String> violations() {
        Set<String> found = new TreeSet<>();
        for (JavaClass origin : classes) {
            for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                JavaClass target = dependency.getTargetClass();
                if (crossesSeam(origin.getName(), target.getName())) {
                    found.add(shortName(topLevel(origin)) + " -> " + shortName(topLevel(target)));
                }
            }
        }
        return found;
    }

    private static boolean crossesSeam(String origin, String target) {
        if (!target.startsWith(ROOT + ".modules.")) {
            return false;
        }
        if (origin.startsWith(ROOT + ".shared.")) {
            return true;
        }
        Matcher from = MODULE.matcher(origin);
        Matcher to = MODULE.matcher(target);
        return from.matches() && to.matches()
                && !from.group(1).equals(to.group(1))
                && to.group(2).startsWith("repository.");
    }

    private static String shortName(JavaClass javaClass) {
        return javaClass.getName().substring(ROOT.length() + 1);
    }

    private static JavaClass topLevel(JavaClass javaClass) {
        JavaClass current = javaClass;
        while (current.getEnclosingClass().isPresent()) {
            current = current.getEnclosingClass().get();
        }
        return current;
    }
}
