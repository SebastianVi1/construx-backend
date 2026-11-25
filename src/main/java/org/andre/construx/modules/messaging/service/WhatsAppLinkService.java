package org.andre.construx.modules.messaging.service;

import org.andre.construx.modules.identity.service.AccountService;
import org.andre.construx.modules.identity.service.PersonDirectory;
import org.andre.construx.modules.messaging.config.WhatsAppProperties;
import org.andre.construx.modules.messaging.conversation.Language;
import org.andre.construx.modules.messaging.conversation.LinkedNumber;
import org.andre.construx.modules.messaging.conversation.LinkedNumbers;
import org.andre.construx.modules.messaging.model.LinkCode;
import org.andre.construx.modules.messaging.model.WhatsAppLink;
import org.andre.construx.modules.messaging.repository.LinkCodeRepository;
import org.andre.construx.modules.messaging.repository.WhatsAppLinkRepository;
import org.andre.construx.shared.audit.AuditService;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Linked numbers (Field updates contract §2, §4.1, §5 rules 1–3): a user makes a one-time {@code CX-1234} code in the
 * dashboard and sends it from WhatsApp, which links that sender id to them. One number per user, one user per number.
 * A disabled account's number is treated as unlinked. Linking and unlinking are audited as {@code WHATSAPP_LINK} with
 * the user's account id as the entity id.
 */
@Service
public class WhatsAppLinkService implements LinkedNumbers {
    static final String AUDIT_ENTITY = "WHATSAPP_LINK";
    private static final Pattern CODE = Pattern.compile("CX-\\d{4}");
    private static final int CODE_ATTEMPTS = 20;

    public enum Source { APP, WHATSAPP }

    /** {@code number} is the full {@code +}-prefixed number; the dashboard masks it. */
    public record LinkStatus(boolean linked, String number, Language language, Instant linkedAt,
            String construxNumber) {}

    public record LinkCodeData(String code, Instant expiresAt, String construxNumber, URI waMeUrl) {}

    /** Who just linked their number, to greet them by name in the language of their code. */
    public record Linked(String name, Language language) {}

    private final WhatsAppLinkRepository links;
    private final LinkCodeRepository codes;
    private final AccountService accounts;
    private final PersonDirectory people;
    private final AuditService audit;
    private final WhatsAppProperties properties;
    private final NumberMovedMailer movedMailer;
    private final ConversationSessions sessions;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public WhatsAppLinkService(WhatsAppLinkRepository links, LinkCodeRepository codes, AccountService accounts,
            PersonDirectory people, AuditService audit, WhatsAppProperties properties, NumberMovedMailer movedMailer,
            ConversationSessions sessions, Clock clock) {
        this.links = links;
        this.codes = codes;
        this.accounts = accounts;
        this.people = people;
        this.audit = audit;
        this.properties = properties;
        this.movedMailer = movedMailer;
        this.sessions = sessions;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public LinkStatus status(UUID userId) {
        return links.findByUserId(userId).map(this::status)
                .orElseGet(() -> new LinkStatus(false, null, null, null, properties.displayNumber()));
    }

    /** A new code for the user, replacing any code they had; it works once, for ten minutes. */
    @Transactional
    public LinkCodeData createCode(UUID userId, Language language) {
        Instant now = clock.instant();
        String code = freshCode(userId, now);
        LinkCode stored = codes.findById(userId).orElse(null);
        if (stored == null) {
            stored = codes.save(new LinkCode(userId, hash(code), language, now));
        } else {
            stored.replace(hash(code), language, now);
        }
        String construx = properties.displayNumber();
        URI waMe = URI.create("https://wa.me/" + construx.replaceAll("\\D", "") + "?text=" + code);
        return new LinkCodeData(code, stored.getExpiresAt(), construx, waMe);
    }

    @Transactional
    public LinkStatus changeLanguage(UUID userId, Language language) {
        WhatsAppLink link = links.findByUserId(userId).orElseThrow(WhatsAppLinkService::notLinked);
        link.changeLanguage(language);
        return status(link);
    }

    /** Unlinks the user's number from the dashboard; nothing happens when there is none. */
    @Transactional
    public void unlink(UUID userId) {
        links.findByUserId(userId).ifPresent(link -> remove(link, Source.APP));
    }

    /** BAJA / STOP from the number: unlinks it and returns the language it had, empty when it was not linked. */
    @Transactional
    public Optional<Language> unlinkNumber(String waId) {
        return links.findByWaId(waId).map(link -> {
            remove(link, Source.WHATSAPP);
            return link.getLanguage();
        });
    }

    /** Whether a message is meant as a link code ({@code CX-} and 4 digits, any case, spaces around ignored). */
    public static boolean looksLikeCode(String text) {
        return text != null && CODE.matcher(normalize(text)).matches();
    }

    /**
     * Links the sender to the owner of the code. Empty when the code is wrong, expired, already used, or its owner is
     * disabled. The code is used up; a number the user had before is replaced.
     */
    @Transactional
    public Optional<Linked> linkByCode(String waId, String code) {
        Instant now = clock.instant();
        List<LinkCode> matching = codes.findByCodeHash(hash(normalize(code))).stream()
                .filter(candidate -> candidate.isValidAt(now)).toList();
        if (matching.size() != 1) {
            return Optional.empty();
        }
        LinkCode used = matching.getFirst();
        codes.delete(used);
        UUID userId = used.getUserId();
        if (!accounts.isEnabled(userId)) {
            return Optional.empty();
        }
        Optional<WhatsAppLink> moved = links.findByWaId(waId).filter(other -> !other.getUserId().equals(userId));
        moved.ifPresent(other -> {
            // The previous owner's menu session on this number goes with it.
            sessions.end(waId);
            links.delete(other);
            links.flush();
        });
        WhatsAppLink link = links.findByUserId(userId).orElse(null);
        if (link == null) {
            link = links.save(new WhatsAppLink(userId, waId, used.getLanguage(), now));
        } else {
            // The number the user had before stops talking to the bot, so its menu session ends.
            sessions.end(link.getWaId());
            link.relink(waId, used.getLanguage(), now);
        }
        Map<String, Object> linked = auditState(link, Source.WHATSAPP);
        moved.ifPresent(other -> recordMove(other, userId, linked));
        audit.record(AUDIT_ENTITY, link.getUserId(), "LINK", null, null, linked);
        return Optional.of(new Linked(nameOf(userId), link.getLanguage()));
    }

    /** The sender's link, unless there is none or the account is disabled (contract §5 rules 1–2). */
    @Override
    @Transactional(readOnly = true)
    public Optional<LinkedNumber> find(String waId) {
        return links.findByWaId(waId)
                .filter(link -> accounts.isEnabled(link.getUserId()))
                .map(link -> new LinkedNumber(link.getWaId(), link.getUserId(), link.getLanguage()));
    }

    /** Daily cleanup: codes past their ten minutes. */
    @Transactional
    public int deleteExpiredCodes() {
        return codes.deleteExpired(clock.instant());
    }

    /**
     * A recycled number linked by someone else: the previous owner's audit trail records {@code MOVE} (to whom, never
     * the full number) and they are emailed in the language of the link they lost.
     */
    private void recordMove(WhatsAppLink previous, UUID newOwner, Map<String, Object> linked) {
        Map<String, Object> after = new LinkedHashMap<>(linked);
        after.put("movedToUserId", newOwner.toString());
        audit.record(AUDIT_ENTITY, previous.getUserId(), "MOVE", null, auditState(previous, Source.WHATSAPP), after);
        movedMailer.send(accounts.account(previous.getUserId()), lastDigits(previous.getWaId()),
                previous.getLanguage());
    }

    /** Unlinking ends the number's menu session too. */
    private void remove(WhatsAppLink link, Source source) {
        sessions.end(link.getWaId());
        links.delete(link);
        audit.record(AUDIT_ENTITY, link.getUserId(), "UNLINK", null, auditState(link, source), null);
    }

    private LinkStatus status(WhatsAppLink link) {
        return new LinkStatus(true, "+" + link.getWaId(), link.getLanguage(), link.getLinkedAt(),
                properties.displayNumber());
    }

    /** A code no one else holds right now, so a code always points at one user. */
    private String freshCode(UUID userId, Instant now) {
        for (int attempt = 0; attempt < CODE_ATTEMPTS; attempt++) {
            String code = "CX-%04d".formatted(random.nextInt(10_000));
            if (!codes.existsByCodeHashAndExpiresAtAfterAndUserIdNot(hash(code), now, userId)) {
                return code;
            }
        }
        throw new IllegalStateException("No free link code");
    }

    private String nameOf(UUID userId) {
        String name = people.of(userId).fullName();
        return name != null && !name.isBlank() ? name : accounts.account(userId).getEmail();
    }

    /** The number is kept masked in the audit trail. */
    private static Map<String, Object> auditState(WhatsAppLink link, Source source) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("number", "…" + lastDigits(link.getWaId()));
        state.put("language", link.getLanguage().code());
        state.put("source", source.name());
        return state;
    }

    private static String lastDigits(String waId) {
        return waId.substring(Math.max(0, waId.length() - 4));
    }

    private static ResourceNotFoundException notLinked() {
        return new ResourceNotFoundException("WHATSAPP_NOT_LINKED", "No WhatsApp number is linked to this account.");
    }

    private static String normalize(String text) {
        return text.strip().toUpperCase(Locale.ROOT);
    }

    private static String hash(String code) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
