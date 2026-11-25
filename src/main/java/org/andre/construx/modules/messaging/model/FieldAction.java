package org.andre.construx.modules.messaging.model;

import org.andre.construx.modules.project.model.ActivityStatus;

import java.util.Set;

/** What a Field update does to the chosen Task (contract §5 step C, D1). Stored by name (at most 10 characters). */
public enum FieldAction {
    START(Set.of(ActivityStatus.TODO)),
    FINISH(Set.of(ActivityStatus.TODO, ActivityStatus.IN_PROGRESS)),
    /** A Progress note on the Task, which changes nothing else. */
    NOTE(Set.of(ActivityStatus.TODO, ActivityStatus.IN_PROGRESS));

    private final Set<ActivityStatus> from;

    FieldAction(Set<ActivityStatus> from) {
        this.from = from;
    }

    /** Whether the action can be offered (and applied) to a Task in this status. */
    public boolean appliesTo(ActivityStatus status) {
        return from.contains(status);
    }
}
