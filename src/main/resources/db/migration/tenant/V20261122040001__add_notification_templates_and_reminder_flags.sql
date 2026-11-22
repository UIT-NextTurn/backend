-- ==============================================================================
-- Migration: V20261122040001__add_notification_templates_and_reminder_flags.sql
-- Description: Add reminder tracking flags to tenant__appointments (REQ-NOTI-01),
--              create tenant__notification_templates (REQ-NOTI-04), and add
--              query indexes on tenant__notification_logs for recipient/ticket lookups.
-- ==============================================================================

ALTER TABLE `tenant__appointments`
    ADD COLUMN `reminder_24h_sent` BOOLEAN NOT NULL DEFAULT FALSE AFTER `status`,
    ADD COLUMN `reminder_1h_sent` BOOLEAN NOT NULL DEFAULT FALSE AFTER `reminder_24h_sent`,
    ADD INDEX `idx_tenant__apt_reminder` (`status`, `reminder_24h_sent`, `reminder_1h_sent`);

CREATE TABLE IF NOT EXISTS `tenant__notification_templates` (
    `id` CHAR(36) NOT NULL,
    `template_code` VARCHAR(50) NOT NULL,
    `template_name` VARCHAR(150) NOT NULL,
    `channel` VARCHAR(20) NOT NULL DEFAULT 'EMAIL',
    `subject_template` VARCHAR(255) NULL,
    `body_template` TEXT NOT NULL,
    `is_active` BOOLEAN NOT NULL DEFAULT TRUE,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT `pk_tenant__notification_templates` PRIMARY KEY (`id`),
    CONSTRAINT `uk_tenant__nt_code` UNIQUE (`template_code`),
    CONSTRAINT `chk_tenant__nt_channel` CHECK (`channel` IN ('EMAIL', 'PUSH', 'SMS')),
    INDEX `idx_tenant__nt_active` (`is_active`, `channel`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX `idx_tenant__notif_recipient`
    ON `tenant__notification_logs` (`recipient_contact`, `sent_at`);

CREATE INDEX `idx_tenant__notif_template`
    ON `tenant__notification_logs` (`template_code`, `sent_at`);
