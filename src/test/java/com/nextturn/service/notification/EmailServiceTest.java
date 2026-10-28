package com.nextturn.service.notification;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailServiceTest {

    @Test
    @DisplayName("Should detect unconfigured SMTP when host or credentials are blank")
    void shouldDetectUnconfiguredSmtp() {
        EmailService service = new EmailService(null, "localhost", "", "no-reply@nextturn.com", "NextTurn");
        assertThat(service.isConfigured()).isFalse();

        boolean sent = service.sendOtpEmail("test@example.com", "123456", 300).join();
        assertThat(sent).isFalse();
    }

    @Test
    @DisplayName("Should detect configured SMTP when credentials and valid host are provided")
    void shouldDetectConfiguredSmtp() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        EmailService service = new EmailService(mailSender, "smtp.gmail.com", "admin@nextturn.com", "no-reply@nextturn.com", "NextTurn");
        assertThat(service.isConfigured()).isTrue();
    }

    @Test
    @DisplayName("Should invoke JavaMailSender when SMTP is properly configured")
    void shouldSendRealEmailWhenConfigured() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        MimeMessage mimeMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);

        EmailService service = new EmailService(mailSender, "smtp.gmail.com", "admin@nextturn.com", "no-reply@nextturn.com", "NextTurn");

        boolean sent = service.sendOtpEmail("recipient@example.com", "888888", 300).join();

        assertThat(sent).isTrue();
        verify(mailSender).send(any(MimeMessage.class));
    }
}
