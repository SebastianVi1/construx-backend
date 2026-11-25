package org.andre.construx.modules.messaging.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.andre.construx.modules.messaging.model.Language;
import org.andre.construx.modules.messaging.service.WhatsAppLinkService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;

import static org.andre.construx.shared.security.SecurityUtils.userId;

/** The signed-in user's Linked number (Field updates contract §4.1). */
@RestController
@RequestMapping("/api/profile/whatsapp")
public class WhatsAppProfileController {
    private final WhatsAppLinkService links;

    public WhatsAppProfileController(WhatsAppLinkService links) {
        this.links = links;
    }

    public record LanguageRequest(@NotNull @Pattern(regexp = "es|en", message = "must be es or en") String language) {}

    public record StatusResponse(boolean linked, String number, String language, Instant linkedAt,
            String construxNumber) {
        static StatusResponse from(WhatsAppLinkService.LinkStatus status) {
            return new StatusResponse(status.linked(), status.number(),
                    status.language() == null ? null : status.language().code(), status.linkedAt(),
                    status.construxNumber());
        }
    }

    public record LinkCodeResponse(String code, Instant expiresAt, String construxNumber, URI waMeUrl) {}

    @GetMapping
    public StatusResponse status(Authentication authentication) {
        return StatusResponse.from(links.status(userId(authentication)));
    }

    @PostMapping("/link-code")
    @ResponseStatus(HttpStatus.CREATED)
    public LinkCodeResponse createCode(@Valid @RequestBody LanguageRequest body, Authentication authentication) {
        WhatsAppLinkService.LinkCodeData code = links.createCode(userId(authentication), Language.of(body.language()));
        return new LinkCodeResponse(code.code(), code.expiresAt(), code.construxNumber(), code.waMeUrl());
    }

    @PutMapping("/language")
    public StatusResponse changeLanguage(@Valid @RequestBody LanguageRequest body, Authentication authentication) {
        return StatusResponse.from(links.changeLanguage(userId(authentication), Language.of(body.language())));
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlink(Authentication authentication) {
        links.unlink(userId(authentication));
    }
}
