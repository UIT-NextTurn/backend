-- ==============================================================================
-- NextTurn Relational Database Migration (Flyway V20261021010002)
-- Schema: Tenant Dedicated Schema (tenant_{code})
-- Description: Isolated business tables for services, staff shifts, capacity slots,
--              appointments, real-time queue tickets, audits, and customer feedbacks.
-- Target Engine: MySQL 8.4 LTS (Oracle HeatWave Compatible) / InnoDB
-- ==============================================================================

-- ------------------------------------------------------------------------------
-- 1. tenant__services: Service catalog and durations
-- ------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `tenant__services` (
    `id` CHAR(36) NOT NULL,
    `service_code` VARCHAR(30) NOT NULL,
    `service_name` VARCHAR(150) NOT NULL,
    `standard_duration` INT NOT NULL DEFAULT 15,
    `price` DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    `is_deleted` BOOLEAN NOT NULL DEFAULT FALSE,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT `pk_tenant__services` PRIMARY KEY (`id`),
    CONSTRAINT `uk_tenant__services_service_code` UNIQUE (`service_code`),
    CONSTRAINT `uk_tenant__services_service_name` UNIQUE (`service_name`),
    INDEX `idx_tenant__services_status` (`status`, `is_deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ------------------------------------------------------------------------------
-- 2. tenant__staffs: Internal staff, tellers, specialists, and managers
-- ------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `tenant__staffs` (
    `id` CHAR(36) NOT NULL,
    `staff_code` VARCHAR(30) NOT NULL,
    `keycloak_user_id` CHAR(36) NULL,
    `full_name` VARCHAR(150) NOT NULL,
    `email` VARCHAR(255) NOT NULL,
    `phone_number` VARCHAR(20) NOT NULL,
    `role` VARCHAR(30) NOT NULL,
    `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    `is_deleted` BOOLEAN NOT NULL DEFAULT FALSE,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT `pk_tenant__staffs` PRIMARY KEY (`id`),
    CONSTRAINT `uk_tenant__staffs_staff_code` UNIQUE (`staff_code`),
    CONSTRAINT `uk_tenant__staffs_email` UNIQUE (`email`),
    CONSTRAINT `uk_tenant__staffs_phone_number` UNIQUE (`phone_number`),
    INDEX `idx_tenant__staffs_role_status` (`role`, `status`, `is_deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ------------------------------------------------------------------------------
-- 3. tenant__staff_services: Junction table for staff service certifications
-- ------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `tenant__staff_services` (
    `id` CHAR(36) NOT NULL,
    `staff_id` CHAR(36) NOT NULL,
    `service_id` CHAR(36) NOT NULL,
    `assigned_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT `pk_tenant__staff_services` PRIMARY KEY (`id`),
    CONSTRAINT `uk_tenant__staff_service` UNIQUE (`staff_id`, `service_id`),
    CONSTRAINT `fk_tenant__ss_staff` FOREIGN KEY (`staff_id`)
        REFERENCES `tenant__staffs` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_tenant__ss_service` FOREIGN KEY (`service_id`)
        REFERENCES `tenant__services` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ------------------------------------------------------------------------------
-- 4. tenant__shifts: Operational work schedule for staff
-- ------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `tenant__shifts` (
    `id` CHAR(36) NOT NULL,
    `staff_id` CHAR(36) NOT NULL,
    `service_id` CHAR(36) NULL,
    `work_date` DATE NOT NULL,
    `shift_start` TIME NOT NULL,
    `shift_end` TIME NOT NULL,
    `status` VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT `pk_tenant__shifts` PRIMARY KEY (`id`),
    CONSTRAINT `fk_tenant__shifts_staff` FOREIGN KEY (`staff_id`)
        REFERENCES `tenant__staffs` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_tenant__shifts_service` FOREIGN KEY (`service_id`)
        REFERENCES `tenant__services` (`id`) ON DELETE SET NULL,
    INDEX `idx_tenant__shifts_date_staff` (`work_date`, `staff_id`),
    INDEX `idx_tenant__shifts_date_service` (`work_date`, `service_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ------------------------------------------------------------------------------
-- 5. tenant__time_slots: Generated capacity booking windows
-- ------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `tenant__time_slots` (
    `id` CHAR(36) NOT NULL,
    `service_id` CHAR(36) NOT NULL,
    `work_date` DATE NOT NULL,
    `slot_start` TIME NOT NULL,
    `slot_end` TIME NOT NULL,
    `capacity_per_slot` INT NOT NULL DEFAULT 1,
    `booked_count` INT NOT NULL DEFAULT 0,
    `status` VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    `holding_token` VARCHAR(64) NULL,
    `holding_expires_at` DATETIME NULL,
    CONSTRAINT `pk_tenant__time_slots` PRIMARY KEY (`id`),
    CONSTRAINT `fk_tenant__slots_service` FOREIGN KEY (`service_id`)
        REFERENCES `tenant__services` (`id`) ON DELETE CASCADE,
    INDEX `idx_tenant__slots_query` (`service_id`, `work_date`, `status`),
    INDEX `idx_tenant__slots_hold` (`holding_token`, `holding_expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ------------------------------------------------------------------------------
-- 6. tenant__appointments: Customer online pre-bookings
-- ------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `tenant__appointments` (
    `id` CHAR(36) NOT NULL,
    `appointment_code` VARCHAR(30) NOT NULL,
    `user_id` CHAR(36) NULL COMMENT 'Logical FK to central__users.id in nextturn_master',
    `service_id` CHAR(36) NOT NULL,
    `slot_id` CHAR(36) NOT NULL,
    `customer_name` VARCHAR(150) NOT NULL,
    `customer_phone` VARCHAR(20) NOT NULL,
    `customer_email` VARCHAR(255) NULL,
    `notes` TEXT NULL,
    `qr_token` VARCHAR(128) NOT NULL,
    `status` VARCHAR(30) NOT NULL DEFAULT 'CONFIRMED',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT `pk_tenant__appointments` PRIMARY KEY (`id`),
    CONSTRAINT `uk_tenant__appointments_code` UNIQUE (`appointment_code`),
    CONSTRAINT `uk_tenant__appointments_qr` UNIQUE (`qr_token`),
    CONSTRAINT `fk_tenant__apt_service` FOREIGN KEY (`service_id`)
        REFERENCES `tenant__services` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `fk_tenant__apt_slot` FOREIGN KEY (`slot_id`)
        REFERENCES `tenant__time_slots` (`id`) ON DELETE RESTRICT,
    INDEX `idx_tenant__apt_user` (`user_id`),
    INDEX `idx_tenant__apt_phone` (`customer_phone`),
    INDEX `idx_tenant__apt_status` (`status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ------------------------------------------------------------------------------
-- 7. tenant__queue_tickets: Real-time virtual queue ticketing engine
-- ------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `tenant__queue_tickets` (
    `id` CHAR(36) NOT NULL,
    `ticket_code` VARCHAR(20) NOT NULL,
    `work_date` DATE NOT NULL,
    `service_id` CHAR(36) NOT NULL,
    `appointment_id` CHAR(36) NULL,
    `counter_staff_id` CHAR(36) NULL,
    `provider_id` CHAR(36) NULL,
    `customer_type` VARCHAR(20) NOT NULL DEFAULT 'WALK_IN',
    `status` VARCHAR(20) NOT NULL DEFAULT 'WAITING',
    `recall_count` INT NOT NULL DEFAULT 0,
    `version` INT NOT NULL DEFAULT 0 COMMENT 'Optimistic lock for concurrent Kanban drag-drop and counter calls',
    `auto_completed` BOOLEAN NOT NULL DEFAULT FALSE,
    `approaching_alert_sent` BOOLEAN NOT NULL DEFAULT FALSE,
    `issued_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `called_at` DATETIME NULL,
    `served_at` DATETIME NULL,
    `completed_at` DATETIME NULL,
    `actual_duration` INT NULL COMMENT 'Actual service duration in seconds',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT `pk_tenant__queue_tickets` PRIMARY KEY (`id`),
    CONSTRAINT `fk_tenant__ticket_service` FOREIGN KEY (`service_id`)
        REFERENCES `tenant__services` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `fk_tenant__ticket_appointment` FOREIGN KEY (`appointment_id`)
        REFERENCES `tenant__appointments` (`id`) ON DELETE SET NULL,
    CONSTRAINT `fk_tenant__ticket_counter_staff` FOREIGN KEY (`counter_staff_id`)
        REFERENCES `tenant__staffs` (`id`) ON DELETE SET NULL,
    CONSTRAINT `fk_tenant__ticket_provider` FOREIGN KEY (`provider_id`)
        REFERENCES `tenant__staffs` (`id`) ON DELETE SET NULL,
    INDEX `idx_tenant__ticket_live_queue` (`work_date`, `service_id`, `status`),
    INDEX `idx_tenant__ticket_called` (`counter_staff_id`, `status`),
    INDEX `idx_tenant__ticket_provider` (`provider_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ------------------------------------------------------------------------------
-- 8. tenant__ticket_audit_logs: Non-destructive state transitions
-- ------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `tenant__ticket_audit_logs` (
    `id` CHAR(36) NOT NULL,
    `ticket_id` CHAR(36) NOT NULL,
    `actor_id` VARCHAR(100) NOT NULL,
    `previous_state` VARCHAR(20) NOT NULL,
    `new_state` VARCHAR(20) NOT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT `pk_tenant__ticket_audit_logs` PRIMARY KEY (`id`),
    CONSTRAINT `fk_tenant__audit_ticket` FOREIGN KEY (`ticket_id`)
        REFERENCES `tenant__queue_tickets` (`id`) ON DELETE CASCADE,
    INDEX `idx_tenant__ticket_audit_time` (`ticket_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ------------------------------------------------------------------------------
-- 9. tenant__feedbacks: Post-service reviews and star ratings
-- ------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `tenant__feedbacks` (
    `id` CHAR(36) NOT NULL,
    `ticket_id` CHAR(36) NOT NULL,
    `service_id` CHAR(36) NOT NULL,
    `provider_id` CHAR(36) NULL,
    `rating_score` TINYINT NOT NULL,
    `criteria_ratings` JSON NULL,
    `comment` TEXT NULL,
    `feedback_token` VARCHAR(64) NOT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT `pk_tenant__feedbacks` PRIMARY KEY (`id`),
    CONSTRAINT `uk_tenant__feedbacks_ticket` UNIQUE (`ticket_id`),
    CONSTRAINT `uk_tenant__feedbacks_token` UNIQUE (`feedback_token`),
    CONSTRAINT `fk_tenant__fb_ticket` FOREIGN KEY (`ticket_id`)
        REFERENCES `tenant__queue_tickets` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_tenant__fb_service` FOREIGN KEY (`service_id`)
        REFERENCES `tenant__services` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_tenant__fb_provider` FOREIGN KEY (`provider_id`)
        REFERENCES `tenant__staffs` (`id`) ON DELETE SET NULL,
    INDEX `idx_tenant__feedbacks_provider_score` (`provider_id`, `rating_score`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ------------------------------------------------------------------------------
-- 10. tenant__notification_logs: SMS/Email/Push delivery and SSE audit
-- ------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `tenant__notification_logs` (
    `id` CHAR(36) NOT NULL,
    `appointment_id` CHAR(36) NULL,
    `ticket_id` CHAR(36) NULL,
    `recipient_contact` VARCHAR(255) NOT NULL,
    `channel` VARCHAR(20) NOT NULL,
    `template_code` VARCHAR(50) NOT NULL,
    `message_payload` TEXT NOT NULL,
    `status` VARCHAR(20) NOT NULL DEFAULT 'SUCCESS',
    `error_message` TEXT NULL,
    `sent_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT `pk_tenant__notification_logs` PRIMARY KEY (`id`),
    CONSTRAINT `fk_tenant__notif_appointment` FOREIGN KEY (`appointment_id`)
        REFERENCES `tenant__appointments` (`id`) ON DELETE SET NULL,
    CONSTRAINT `fk_tenant__notif_ticket` FOREIGN KEY (`ticket_id`)
        REFERENCES `tenant__queue_tickets` (`id`) ON DELETE SET NULL,
    INDEX `idx_tenant__notif_status_time` (`status`, `sent_at`),
    INDEX `idx_tenant__notif_ticket` (`ticket_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ------------------------------------------------------------------------------
-- 11. tenant__tenant_settings: Tenant-level advance booking rules
-- ------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `tenant__tenant_settings` (
    `id` CHAR(36) NOT NULL,
    `min_lead_time_hours` INT NOT NULL DEFAULT 2,
    `max_advance_booking_days` INT NOT NULL DEFAULT 30,
    `cancellation_deadline_hours` INT NOT NULL DEFAULT 12,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT `pk_tenant__tenant_settings` PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
