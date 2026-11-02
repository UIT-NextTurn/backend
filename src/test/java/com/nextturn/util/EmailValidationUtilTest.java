package com.nextturn.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class EmailValidationUtilTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "admin@tamduc.vn",
            "admin.clinic@tamduc.vn",
            "first.middle.last@hospital.com",
            "user_name-123@sub.example.com",
            "support@healthcare.org.vn"
    })
    @DisplayName("Should accept valid email addresses without '+' and with properly placed dots")
    void shouldAcceptValidEmails(String email) {
        assertThat(EmailValidationUtil.isValid(email)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "admin+1@tamduc.vn",
            "admin+alias@gmail.com",
            "+admin@gmail.com",
            "user+test+more@domain.com",
            "contact+tag@sub.domain.org"
    })
    @DisplayName("Should strictly reject email addresses containing '+' character")
    void shouldRejectEmailsWithPlus(String email) {
        assertThat(EmailValidationUtil.isValid(email)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            ".admin@tamduc.vn",          // Leading dot in local part
            "admin.@tamduc.vn",          // Trailing dot in local part
            "admin..test@tamduc.vn",     // Consecutive dots in local part
            "admin...test@tamduc.vn",    // Multiple consecutive dots in local part
            "admin@tamduc..vn",          // Consecutive dots in domain
            "admin@.tamduc.vn",          // Leading dot in domain
            "admin@tamduc.vn.",          // Trailing dot in domain
            "admin@tamduc",              // Missing top-level domain dot
            "admin@localhost"            // Missing TLD dot
    })
    @DisplayName("Should strictly reject email addresses with malformed '.' placement")
    void shouldRejectEmailsWithImproperDots(String email) {
        assertThat(EmailValidationUtil.isValid(email)).isFalse();
    }

    @Test
    @DisplayName("Should reject null or blank email addresses")
    void shouldRejectNullOrBlank() {
        assertThat(EmailValidationUtil.isValid(null)).isFalse();
        assertThat(EmailValidationUtil.isValid("")).isFalse();
        assertThat(EmailValidationUtil.isValid("   ")).isFalse();
    }
}

