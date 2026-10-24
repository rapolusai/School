package com.akshara.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.MessageTag;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;

/**
 * Sends the EMAIL channel through Amazon SES ({@code akshara.notifications.email.sender=ses}, EMAIL_SENDER in AWS),
 * from {@code akshara.notifications.email.from} with the SES configuration set (TLS required, bounces and complaints
 * suppressed). Credentials come from the API task's role, which may only send from that address (email.tf). SMS and
 * WhatsApp stay with the next sender (simulated until their providers are chosen).
 *
 * <p>The text is the rendered template, sent as plain text. A refusal SES will repeat (a suppressed or invalid
 * address, a message too large) fails at once; throttling, outages and network trouble are retried by the
 * dispatcher. Only the message id and the SES error code are ever logged.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "akshara.notifications.email.sender", havingValue = "ses")
class SesEmailSender implements MessageSender, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SesEmailSender.class);

    private final SesV2Client ses;
    private final String from;
    private final String configurationSet;

    @Autowired
    SesEmailSender(@Value("${akshara.notifications.email.from:}") String from,
            @Value("${akshara.notifications.email.configuration-set:}") String configurationSet,
            @Value("${akshara.notifications.email.region:}") String region) {
        this(clientFor(region), from, configurationSet);
    }

    SesEmailSender(SesV2Client ses, String from, String configurationSet) {
        if (from == null || !from.contains("@")) {
            throw new IllegalStateException("akshara.notifications.email.sender is ses but EMAIL_FROM is not set");
        }
        this.ses = ses;
        this.from = from;
        this.configurationSet = configurationSet == null || configurationSet.isBlank() ? null : configurationSet;
    }

    private static SesV2Client clientFor(String region) {
        var builder = SesV2Client.builder();
        if (region != null && !region.isBlank()) {
            builder.region(Region.of(region));
        }
        return builder.build();
    }

    @Override
    public boolean supports(Channel channel) {
        return channel == Channel.EMAIL;
    }

    @Override
    public SendResult send(OutgoingMessage message) throws MessageSendException {
        SendEmailRequest.Builder request = SendEmailRequest.builder()
                .fromEmailAddress(from)
                .destination(Destination.builder().toAddresses(message.recipient()).build())
                .content(EmailContent.builder().simple(Message.builder()
                        .subject(text(subject(message)))
                        .body(Body.builder().text(text(message.body())).build())
                        .build()).build())
                .emailTags(MessageTag.builder().name("template").value(tagValue(message.templateKey())).build());
        if (configurationSet != null) {
            request.configurationSetName(configurationSet);
        }
        try {
            String id = ses.sendEmail(request.build()).messageId();
            return SendResult.sent(id);
        } catch (SesV2Exception e) {
            String code = e.awsErrorDetails() == null ? "Unknown" : e.awsErrorDetails().errorCode();
            int status = e.statusCode();
            boolean retryable = status == 429 || status >= 500 || "TooManyRequestsException".equals(code)
                    || "LimitExceededException".equals(code);
            log.warn("SES refused message {}: {} (HTTP {})", message.id(), code, status);
            throw new MessageSendException("Email refused by SES (" + code + ")", retryable);
        } catch (SdkClientException e) {
            log.warn("SES could not be reached for message {} ({})", message.id(), e.getClass().getSimpleName());
            throw new MessageSendException("Email service unreachable", true);
        }
    }

    /** A short subject; the text itself is in the school's or the recipient's language. */
    static String subject(OutgoingMessage message) {
        if (MessageTemplates.PASSWORD_RESET.equals(message.templateKey())) {
            return "Your Akshara password reset code";
        }
        String school = message.params() == null ? null : message.params().get("school");
        return school == null || school.isBlank() ? "Message from Akshara" : "Message from " + school;
    }

    private static Content text(String value) {
        return Content.builder().data(value).charset("UTF-8").build();
    }

    /** SES tag values allow letters, digits, "_" and "-". */
    private static String tagValue(String templateKey) {
        return templateKey == null ? "none" : templateKey.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    @Override
    public void destroy() {
        ses.close();
    }
}
