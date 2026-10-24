package com.akshara.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.akshara.notifications.MessageSender.OutgoingMessage;

import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;

/** Email through SES: what is sent, and which refusals are retried. */
class SesEmailSenderTest {

    private final SesV2Client ses = mock(SesV2Client.class);
    private final SesEmailSender sender = new SesEmailSender(ses, "no-reply@school.example", "akshara-staging");

    private static OutgoingMessage email(String template, Map<String, String> params) {
        return new OutgoingMessage(UUID.randomUUID(), UUID.randomUUID(), Channel.EMAIL, "asha@school.example",
                template, "hi", params, "आपका कोड 042817 है।");
    }

    @Test
    void sendsOnlyEmailFromTheConfiguredAddressWithTheConfigurationSet() throws Exception {
        when(ses.sendEmail(any(SendEmailRequest.class))).thenReturn(SendEmailResponse.builder().messageId("m-1").build());

        MessageSender.SendResult result = sender.send(email(MessageTemplates.PASSWORD_RESET,
                Map.of("school", "Sunrise", "code", "042817")));

        assertThat(result.status()).isEqualTo(MessageStatus.SENT);
        assertThat(result.providerRef()).isEqualTo("m-1");
        ArgumentCaptor<SendEmailRequest> sent = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(ses).sendEmail(sent.capture());
        SendEmailRequest request = sent.getValue();
        assertThat(request.fromEmailAddress()).isEqualTo("no-reply@school.example");
        assertThat(request.destination().toAddresses()).containsExactly("asha@school.example");
        assertThat(request.configurationSetName()).isEqualTo("akshara-staging");
        assertThat(request.content().simple().subject().data()).isEqualTo("Your Akshara password reset code");
        assertThat(request.content().simple().body().text().data()).isEqualTo("आपका कोड 042817 है।");
        assertThat(request.content().simple().body().text().charset()).isEqualTo("UTF-8");
        assertThat(request.emailTags().get(0).value()).isEqualTo("identity_password_reset");

        assertThat(sender.supports(Channel.EMAIL)).isTrue();
        assertThat(sender.supports(Channel.SMS)).isFalse();
        assertThat(sender.supports(Channel.WHATSAPP)).isFalse();
    }

    @Test
    void namesTheSchoolInTheSubjectOfOtherMessages() {
        assertThat(SesEmailSender.subject(email("fees.receipt", Map.of("school", "Sunrise Public School"))))
                .isEqualTo("Message from Sunrise Public School");
        assertThat(SesEmailSender.subject(email("fees.receipt", Map.of()))).isEqualTo("Message from Akshara");
    }

    @Test
    void aRejectedAddressFailsAtOnceButThrottlingAndOutagesAreRetried() {
        when(ses.sendEmail(any(SendEmailRequest.class))).thenThrow(sesError(400, "MessageRejected"));
        assertThatThrownBy(() -> sender.send(email("fees.receipt", Map.of())))
                .isInstanceOfSatisfying(MessageSendException.class, e -> {
                    assertThat(e.retryable()).isFalse();
                    assertThat(e.getMessage()).contains("MessageRejected").doesNotContain("asha@");
                });

        when(ses.sendEmail(any(SendEmailRequest.class))).thenThrow(sesError(429, "TooManyRequestsException"));
        assertThatThrownBy(() -> sender.send(email("fees.receipt", Map.of())))
                .isInstanceOfSatisfying(MessageSendException.class, e -> assertThat(e.retryable()).isTrue());

        when(ses.sendEmail(any(SendEmailRequest.class))).thenThrow(SdkClientException.create("connect timed out"));
        assertThatThrownBy(() -> sender.send(email("fees.receipt", Map.of())))
                .isInstanceOfSatisfying(MessageSendException.class, e -> assertThat(e.retryable()).isTrue());
    }

    @Test
    void refusesToStartWithoutASenderAddress() {
        assertThatThrownBy(() -> new SesEmailSender(ses, "", null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("EMAIL_FROM");
    }

    private static SesV2Exception sesError(int status, String code) {
        return (SesV2Exception) SesV2Exception.builder().statusCode(status)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).build()).build();
    }
}
