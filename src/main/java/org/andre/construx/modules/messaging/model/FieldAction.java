package org.andre.construx.modules.messaging.model;

import org.andre.construx.modules.project.model.ActivityStatus;

import java.util.Set;

/** What a Field update does to the chosen Task (contract §5 step C, D1). Stored by name (at most 10 characters). */
public enum FieldAction {
    START(Set.of(ActivityStatus.TODO), false),
    FINISH(Set.of(ActivityStatus.TODO, ActivityStatus.IN_PROGRESS), false),
    /** A Progress note on the Task, which changes nothing else. */
    NOTE(Set.of(ActivityStatus.TODO, ActivityStatus.IN_PROGRESS), true);

    private final Set<ActivityStatus> from;
    private final boolean writesNote;

    FieldAction(Set<ActivityStatus> from, boolean writesNote) {
        this.from = from;
        this.writesNote = writesNote;
    }

    /** Whether YES writes a Progress note even without photos (with photos, every action does). */
    public boolean writesNote() {
        return writesNote;
    }

    /** Whether the action can be offered (and applied) to a Task in this status. */
    public boolean appliesTo(ActivityStatus status) {
        return from.contains(status);
    }
}
