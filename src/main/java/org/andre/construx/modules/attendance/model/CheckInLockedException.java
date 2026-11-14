package org.andre.construx.modules.attendance.model;

/** A Check-in of another day was asked to change: past days are locked (ADR 0007). Answered 409. */
public class CheckInLockedException extends IllegalStateException {
    public CheckInLockedException() {
        super("A Check-in can only be removed on its own day");
    }
}
