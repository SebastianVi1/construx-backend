package org.andre.construx.modules.messaging.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * WhatsApp Cloud API settings, all from environment variables ({@code WHATSAPP_*}). Left empty, the app still starts:
 * the webhook then refuses every call and nothing can be sent.
 *
 * @param phoneNumberId Meta's id of the Construx number, used to send
 * @param accessToken   bearer token for the Graph API
 * @param appSecret     signs every webhook post ({@code X-Hub-Signature-256})
 * @param verifyToken   echoed back by Meta when the webhook is registered
 * @param displayNumber the Construx number as people dial it, shown in the dashboard
 * @param graphApiUrl   Graph API base URL including its version
 * @param synchronous   process inbound messages on the request thread (tests only)
 */
@ConfigurationProperties("app.whatsapp")
public record WhatsAppProperties(String phoneNumberId, String accessToken, String appSecret, String verifyToken,
        String displayNumber, String graphApiUrl, boolean synchronous) {
}
