package org.andre.construx.shared.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A transactional email in the Construx layout: logo, heading, paragraphs, one call-to-action
 * button and a closing note. Renders to HTML (from {@code mail/layout.html}, with the logo inline)
 * plus a plain-text alternative. Every value is HTML-escaped, so user input such as a company name
 * is safe to pass.
 */
public record BrandedEmail(String subject, String preheader, String heading, List<String> paragraphs,
                           String actionLabel, String actionUrl, String note) {
    static final String LOGO_CID = "construx-logo";
    private static final String LAYOUT = read("mail/layout.html");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");
    private static final ClassPathResource LOGO = new ClassPathResource("mail/logo.png");

    public BrandedEmail {
        paragraphs = List.copyOf(paragraphs);
    }

    public String html() {
        String body = paragraphs.stream()
                .map(p -> "<p style=\"margin:0 0 16px 0;font-size:15px;line-height:24px;color:#1f2937;\">"
                        + escape(p) + "</p>")
                .collect(Collectors.joining("\n                  "));
        Map<String, String> values = Map.of(
                "subject", escape(subject), "preheader", escape(preheader), "heading", escape(heading),
                "note", escape(note), "actionLabel", escape(actionLabel), "actionUrl", escape(actionUrl),
                "paragraphs", body);
        // One pass, so placeholder-like text inside a value is never expanded.
        return PLACEHOLDER.matcher(LAYOUT).replaceAll(m -> Matcher.quoteReplacement(values.get(m.group(1))));
    }

    public String text() {
        return heading + "\n\n"
                + String.join("\n\n", paragraphs) + "\n\n"
                + actionLabel + ":\n" + actionUrl + "\n\n"
                + note + "\n\n"
                + "--\nConstrux · Gestión de obras para empresas constructoras\n"
                + "Este es un correo automático, por favor no respondas a este mensaje.";
    }

    /** Builds the multipart message (text + HTML + inline logo) ready for {@code sender.send}. */
    public MimeMessage toMimeMessage(JavaMailSender sender, String from, String to) throws MessagingException {
        MimeMessage message = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED,
                StandardCharsets.UTF_8.name());
        helper.setFrom(from);
        helper.setTo(to);
        helper.setSubject(subject);
        helper.setText(text(), html());
        helper.addInline(LOGO_CID, LOGO, "image/png");
        return message;
    }

    private static String escape(String value) {
        return HtmlUtils.htmlEscape(value, StandardCharsets.UTF_8.name());
    }

    private static String read(String path) {
        try (var in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException("Missing email layout " + path, ex);
        }
    }
}
