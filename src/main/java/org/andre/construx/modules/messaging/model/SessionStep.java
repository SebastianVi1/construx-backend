package org.andre.construx.modules.messaging.model;

/**
 * Where a menu conversation is (Field updates contract §5 steps A–G): what the bot last asked, so it knows what input
 * to accept next. Stored by name in {@code whatsapp_session.step} (at most 30 characters).
 */
public enum SessionStep {
    /** A: a list of Sites. */
    PICK_SITE,
    /** B: a page of open Tasks of the chosen Site. */
    PICK_TASK,
    /** C: buttons with what can be done to the chosen Task. */
    PICK_ACTION,
    /** D: waiting for the text of a Note. */
    WRITE_NOTE,
    /** G: the summary, with Yes / No. */
    CONFIRM
}
