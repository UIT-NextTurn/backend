package com.nextturn.service.notification;

import com.nextturn.service.notification.impl.EmailServiceImpl;
import jakarta.mail.BodyPart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
        EmailService service = new EmailServiceImpl(null, "localhost", "", "no-reply@nextturn.com", "NextTurn");
        assertThat(service.isConfigured()).isFalse();

        boolean sent = service.sendOtpEmail("test@example.com", "123456", 300).join();
        assertThat(sent).isFalse();
    }

    @Test
    @DisplayName("Should detect configured SMTP when credentials and valid host are provided")
    void shouldDetectConfiguredSmtp() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        EmailService service = new EmailServiceImpl(mailSender, "smtp.gmail.com", "admin@nextturn.com", "no-reply@nextturn.com", "NextTurn");
        assertThat(service.isConfigured()).isTrue();
    }

    @Test
    @DisplayName("Should invoke JavaMailSender when SMTP is properly configured")
    void shouldSendRealEmailWhenConfigured() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        MimeMessage mimeMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);

        EmailService service = new EmailServiceImpl(mailSender, "smtp.gmail.com", "admin@nextturn.com", "no-reply@nextturn.com", "NextTurn");

        boolean sent = service.sendOtpEmail("recipient@example.com", "888888", 300).join();

        assertThat(sent).isTrue();
        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("Should send tenant admin activation email using template when configured")
    void shouldSendTenantAdminActivationEmailWhenConfigured() throws Exception {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        MimeMessage mimeMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);

        EmailService service = new EmailServiceImpl(mailSender, "smtp.gmail.com", "admin@nextturn.com", "no-reply@nextturn.com", "NextTurn");

        String adminEmail = "tenant.admin@pk-alpha.vn";
        String tenantName = "Phòng khám Đa khoa Alpha";
        String activationUrl = "https://auth.nextturn.com/auth/realms/nextturn/login-actions/action-token?key=abc123xyz";

        boolean sent = service.sendTenantAdminActivationEmail(adminEmail, tenantName, activationUrl).join();

        assertThat(sent).isTrue();
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());

        MimeMessage sentMessage = captor.getValue();
        assertThat(sentMessage.getSubject()).contains("Kích hoạt tài khoản Quản trị viên cơ sở");

        String body = extractTextContent(sentMessage.getContent());
        assertThat(body).contains("Phòng khám Đa khoa Alpha");
        assertThat(body).contains(activationUrl);
    }

    private String extractTextContent(Object content) throws Exception {
        if (content instanceof String str) {
            return str;
        } else if (content instanceof MimeMultipart multipart) {
            StringBuilder sb = new StringBuilder();
            int count = multipart.getCount();
            for (int i = 0; i < count; i++) {
                BodyPart part = multipart.getBodyPart(i);
                sb.append(extractTextContent(part.getContent()));
            }
            return sb.toString();
        }
        return "";
    }
}
