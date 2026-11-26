package org.andre.construx.modules.messaging.conversation;

import java.util.Optional;

/**
 * Who is behind a sender id (contract §5 rules 1–2). Empty means the sender is treated as not linked: no link, or the
 * linked account is disabled.
 */
public interface LinkedNumbers {
    Optional<LinkedNumber> find(String waId);
}
