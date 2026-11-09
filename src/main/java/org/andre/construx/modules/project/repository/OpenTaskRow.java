package org.andre.construx.modules.project.repository;

import org.andre.construx.modules.project.model.ActivityStatus;
import org.andre.construx.modules.project.model.TaskRole;

import java.time.Instant;
import java.util.UUID;

/** An unfinished task of a site, keyed by one membership in its team ({@link ActivityRepository#findOpenTasksOf}). */
public record OpenTaskRow(UUID membershipId, UUID activityId, UUID goalId, String activityName, String goalName,
                          ActivityStatus status, TaskRole taskRole, Instant dueAt) {}
