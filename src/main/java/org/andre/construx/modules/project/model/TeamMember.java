package org.andre.construx.modules.project.model;

/** A requested place in a task's team: who, and in which task role. */
public record TeamMember(ProjectAccess access, TaskRole taskRole) {}
