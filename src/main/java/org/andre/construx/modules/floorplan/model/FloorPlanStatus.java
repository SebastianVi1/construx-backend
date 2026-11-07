package org.andre.construx.modules.floorplan.model;

/** Tracks the lifecycle of a floor plan file: upload → PDF rendering → ready to view. */
public enum FloorPlanStatus {
    /** Row exists; original file not yet uploaded to storage. */
    PENDING,
    /** File confirmed in storage; PDF rendering in progress. */
    PROCESSING,
    /** Rendered PNG is in storage; view URL available. */
    READY,
    /** Rendering failed; {@code errorReason} has details. */
    ERROR
}
