package org.andre.construx.modules.messaging.config;

import org.andre.construx.modules.messaging.gateway.MetaCloudWhatsAppGateway;
import org.andre.construx.modules.messaging.gateway.WhatsAppGateway;
import org.andre.construx.modules.messaging.inbound.InboundQueue;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

@Configuration
@EnableConfigurationProperties(WhatsAppProperties.class)
public class WhatsAppConfig {
    static final String DEFAULT_GRAPH_API_URL = "https://graph.facebook.com/v23.0";

    @Bean
    WhatsAppGateway whatsAppGateway(WhatsAppProperties properties) {
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(Duration.ofSeconds(5));
        requests.setReadTimeout(Duration.ofSeconds(20));
        RestClient client = RestClient.builder()
                .requestFactory(requests)
                .baseUrl(properties.graphApiUrl() == null || properties.graphApiUrl().isBlank() ? DEFAULT_GRAPH_API_URL
                        : properties.graphApiUrl())
                .defaultHeaders(headers -> headers.setBearerAuth(properties.accessToken()))
                .build();
        return new MetaCloudWhatsAppGateway(client, properties.phoneNumberId());
    }

    /** Inbound messages are handled on a small pool of their own, or on the request thread when synchronous (tests). */
    @Bean(destroyMethod = "close")
    InboundQueue inboundQueue(WhatsAppProperties properties) {
        if (properties.synchronous()) {
            return new InboundQueue(Runnable::run);
        }
        ThreadFactory threads = Thread.ofPlatform().name("whatsapp-inbound-", 0).daemon(true).factory();
        return new InboundQueue(Executors.newFixedThreadPool(4, threads));
    }
}
