package org.andre.construx.modules.pricing.repository;

import java.util.UUID;

/** How many rows point at one id, from a grouped count. */
public interface CountById {
    UUID getId();

    long getTotal();
}
