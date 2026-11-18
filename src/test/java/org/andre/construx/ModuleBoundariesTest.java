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
import java.util.function.Predicate;
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

    private static final Set<String> ALLOWED = Set.of();


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

    /** The Labor rate reaches the catalog from {@code project.service} only (docs/pricing.md §1.12). */
    @Test
    void projectReachesPricingOnlyFromItsServices() {
        Set<String> leaks = leaksOutsideServices("project",
                target -> target.startsWith(ROOT + ".modules.pricing."));
        assertTrue(leaks.isEmpty(), "project reaches pricing outside its services:\n" + String.join("\n", leaks));
    }

    /**
     * The labor accrual reaches attendance, project and pricing from {@code finance.service} only
     * (docs/pricing.md §1.12). The expense entity's JPA links to the Site and task and the controllers' request
     * scope ({@code project.access}) predate it and stay allowed.
     */
    @Test
    void financeReachesAttendanceProjectAndPricingOnlyFromItsServices() {
        Set<String> leaks = leaksOutsideServices("finance",
                target -> target.startsWith(ROOT + ".modules.attendance.")
                        || target.startsWith(ROOT + ".modules.pricing.")
                        || target.equals(ROOT + ".modules.project.service"));
        assertTrue(leaks.isEmpty(), "finance reaches other modules outside its services:\n" + String.join("\n", leaks));
    }

    /**
     * {@code origin -> target} for every class of {@code module} outside its {@code service} package that depends
     * on a class whose package matches {@code forbidden}.
     */
    private static Set<String> leaksOutsideServices(String module, Predicate<String> forbidden) {
        String modulePackage = ROOT + ".modules." + module;
        Set<String> leaks = new TreeSet<>();
        for (JavaClass origin : classes) {
            if (!origin.getPackageName().startsWith(modulePackage + ".")
                    || origin.getPackageName().equals(modulePackage + ".service")) {
                continue;
            }
            for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                if (forbidden.test(dependency.getTargetClass().getPackageName())) {
                    leaks.add(shortName(topLevel(origin)) + " -> " + shortName(topLevel(dependency.getTargetClass())));
                }
            }
        }
        return leaks;
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
