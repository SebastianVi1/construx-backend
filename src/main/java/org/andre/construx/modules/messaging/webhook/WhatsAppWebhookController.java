package org.andre.construx.modules.messaging.webhook;

import org.andre.construx.modules.messaging.config.WhatsAppProperties;
import org.andre.construx.modules.messaging.inbound.InboundDispatcher;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Meta's webhook for the Construx number. Public: outside the cookie session and CSRF (see {@code SecurityConfig}). */
@RestController
@RequestMapping("/api/webhooks/whatsapp")
public class WhatsAppWebhookController {
    private final WhatsAppProperties properties;
    private final InboundDispatcher dispatcher;

    public WhatsAppWebhookController(WhatsAppProperties properties, InboundDispatcher dispatcher) {
        this.properties = properties;
        this.dispatcher = dispatcher;
    }

    /** Subscription check: echo the challenge only to whoever knows the verify token. */
    @GetMapping(produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> verify(@RequestParam(name = "hub.verify_token", required = false) String token,
            @RequestParam(name = "hub.challenge", required = false) String challenge) {
        if (!matches(properties.verifyToken(), token) || challenge == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(challenge);
    }

    /** Signed by Meta or rejected; accepted posts answer 200 at once and their messages are handled later. */
    @PostMapping
    public ResponseEntity<Void> receive(@RequestBody(required = false) byte[] body,
            @RequestHeader(name = "X-Hub-Signature-256", required = false) String signature) {
        byte[] raw = body == null ? new byte[0] : body;
        if (!WebhookSignature.isValid(raw, signature, properties.appSecret())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        dispatcher.accept(MetaWebhookPayload.messages(raw));
        return ResponseEntity.ok().build();
    }

    private static boolean matches(String expected, String given) {
        if (expected == null || expected.isBlank() || given == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
    }
}
