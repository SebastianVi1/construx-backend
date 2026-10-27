package org.andre.construx.modules.project.model;

/** Lifecycle of a site. There is no hard delete in v1: archive instead. */
public enum ProjectStatus {
    PLANNED,
    ACTIVE,
    ON_HOLD,
    COMPLETED,
    ARCHIVED
}
