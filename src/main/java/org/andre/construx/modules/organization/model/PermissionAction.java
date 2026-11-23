package org.andre.construx.modules.organization.model;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.andre.construx.modules.organization.model.PermissionScope.ORGANIZATION;
import static org.andre.construx.modules.organization.model.PermissionScope.OWN;
import static org.andre.construx.modules.organization.model.PermissionScope.PROJECT;

/**
 * The permission catalog's structure: each action's group, the scopes it may be granted at, and what it requires
 * (contract roles §4). Codes are permanent ids; labels live in {@code permission-catalog.yml}. Declaration order is
 * the catalog order.
 */
public enum PermissionAction {
    MANAGE_ORGANIZATION(PermissionGroup.COMPANY, EnumSet.of(ORGANIZATION)),
    MANAGE_ROLES(PermissionGroup.COMPANY, EnumSet.of(ORGANIZATION)),
    MANAGE_MEMBERS(PermissionGroup.COMPANY, EnumSet.of(ORGANIZATION)),
    VIEW_PROJECT(PermissionGroup.SITES, EnumSet.of(PROJECT, ORGANIZATION)),
    MANAGE_PROJECT(PermissionGroup.SITES, EnumSet.of(PROJECT, ORGANIZATION)),
    VIEW_GOAL(PermissionGroup.PHASES, EnumSet.of(PROJECT, ORGANIZATION)),
    MANAGE_GOAL(PermissionGroup.PHASES, EnumSet.of(PROJECT, ORGANIZATION)),
    VIEW_ACTIVITY(PermissionGroup.TASKS, EnumSet.of(OWN, PROJECT, ORGANIZATION)),
    MANAGE_ACTIVITY(PermissionGroup.TASKS, EnumSet.of(OWN, PROJECT, ORGANIZATION)),
    ASSIGN_ACTIVITY(PermissionGroup.TASKS, EnumSet.of(PROJECT, ORGANIZATION)),
    VIEW_PRICES(PermissionGroup.PRICES, EnumSet.of(ORGANIZATION)),
    MANAGE_PRICES(PermissionGroup.PRICES, EnumSet.of(ORGANIZATION)),
    VIEW_BUDGET(PermissionGroup.BUDGETS, EnumSet.of(ORGANIZATION)),
    MANAGE_BUDGET(PermissionGroup.BUDGETS, EnumSet.of(ORGANIZATION)),
    VIEW_PLAN(PermissionGroup.PLANS, EnumSet.of(PROJECT, ORGANIZATION)),
    MANAGE_PLAN(PermissionGroup.PLANS, EnumSet.of(PROJECT, ORGANIZATION));

    /** How wide the required action must be held, relative to the scope the dependent action is granted at. */
    public enum MinScope {
        /** At least the granted scope. */
        SAME,
        /** At least the granted scope and at least {@code PROJECT}. */
        PROJECT,
        /** Company-wide. */
        ORGANIZATION;

        /** The narrowest scope the required action must be held at when the dependent is granted at {@code granted}. */
        public PermissionScope floorFor(PermissionScope granted) {
            return switch (this) {
                case SAME -> granted;
                case PROJECT -> granted == OWN ? PermissionScope.PROJECT : granted;
                case ORGANIZATION -> PermissionScope.ORGANIZATION;
            };
        }
    }

    public record Requirement(PermissionAction action, MinScope minScope) {}

    private final PermissionGroup group;
    private final Set<PermissionScope> scopes;

    PermissionAction(PermissionGroup group, Set<PermissionScope> scopes) {
        this.group = group;
        this.scopes = scopes;
    }

    public PermissionGroup group() {
        return group;
    }

    public Set<PermissionScope> scopes() {
        return EnumSet.copyOf(scopes);
    }

    /** Contract §4.2. A switch, not a constructor argument: enum constants cannot reference later constants. */
    public List<Requirement> requires() {
        Requirement viewProject = new Requirement(VIEW_PROJECT, MinScope.PROJECT);
        return switch (this) {
            case MANAGE_PROJECT -> List.of(new Requirement(VIEW_PROJECT, MinScope.SAME));
            case VIEW_GOAL, VIEW_ACTIVITY, VIEW_PLAN -> List.of(viewProject);
            case MANAGE_GOAL -> List.of(new Requirement(VIEW_GOAL, MinScope.SAME), viewProject);
            case MANAGE_ACTIVITY, ASSIGN_ACTIVITY -> List.of(new Requirement(VIEW_ACTIVITY, MinScope.SAME), viewProject);
            case MANAGE_PLAN -> List.of(new Requirement(VIEW_PLAN, MinScope.SAME), viewProject);
            case MANAGE_PRICES -> List.of(new Requirement(VIEW_PRICES, MinScope.SAME));
            case MANAGE_BUDGET -> List.of(new Requirement(VIEW_BUDGET, MinScope.SAME));
            default -> List.of();
        };
    }
}
