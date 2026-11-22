-- ==============================================================================
-- Migration: V20261122020001__add_reception_points_for_checkin.sql
-- Description: Add tenant__reception_points table for REQ-CHECK-IN-03
--              (Static / 60s Dynamic Reception QR Code management) and link
--              reception_point_id on tenant__queue_tickets.
-- ==============================================================================

CREATE TABLE IF NOT EXISTS `tenant__reception_points` (
    `id` CHAR(36) NOT NULL,
    `point_code` VARCHAR(30) NOT NULL,
    `point_name` VARCHAR(150) NOT NULL,
    `location_label` VARCHAR(150) NULL,
    `service_id` CHAR(36) NULL,
    `qr_mode` VARCHAR(20) NOT NULL DEFAULT 'DYNAMIC',
    `static_token` VARCHAR(128) NULL,
    `qr_secret` VARCHAR(128) NOT NULL,
    `rotation_seconds` INT NOT NULL DEFAULT 60,
    `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    `is_deleted` BOOLEAN NOT NULL DEFAULT FALSE,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT `pk_tenant__reception_points` PRIMARY KEY (`id`),
    CONSTRAINT `uk_tenant__reception_code` UNIQUE (`point_code`),
    CONSTRAINT `uk_tenant__reception_static_token` UNIQUE (`static_token`),
    CONSTRAINT `fk_tenant__reception_service` FOREIGN KEY (`service_id`)
        REFERENCES `tenant__services` (`id`) ON DELETE SET NULL,
    CONSTRAINT `chk_tenant__reception_qr_mode` CHECK (`qr_mode` IN ('DYNAMIC', 'STATIC')),
    CONSTRAINT `chk_tenant__reception_status` CHECK (`status` IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT `chk_tenant__reception_rotation` CHECK (`rotation_seconds` > 0),
    INDEX `idx_tenant__reception_status` (`status`, `is_deleted`),
    INDEX `idx_tenant__reception_service` (`service_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE `tenant__queue_tickets`
    ADD COLUMN `reception_point_id` CHAR(36) NULL AFTER `appointment_id`,
    ADD CONSTRAINT `fk_tenant__ticket_reception` FOREIGN KEY (`reception_point_id`)
        REFERENCES `tenant__reception_points` (`id`) ON DELETE SET NULL;
