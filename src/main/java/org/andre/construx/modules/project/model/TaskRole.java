package org.andre.construx.modules.project.model;

/** Position in a task's team, most senior first; a task has exactly one LEAD. */
public enum TaskRole {
    LEAD,
    FOREMAN,
    CREW
}
