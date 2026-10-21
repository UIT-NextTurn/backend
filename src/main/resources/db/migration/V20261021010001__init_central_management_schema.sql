-- ==============================================================================
-- NextTurn Relational Database Migration (Flyway V20261021010001)
-- Schema: Central Management Schema (nextturn_master)
-- Description: Core platform tables for tenant orchestration, quotas, IAM, and global users
-- Target Engine: MySQL 8.4 LTS (Oracle HeatWave Compatible) / InnoDB
-- ==============================================================================

-- ------------------------------------------------------------------------------
-- 1. central__tenants: Registered tenants and organization profiles
-- ------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `central__tenants` (
    `id` CHAR(36) NOT NULL,
    `tenant_code` VARCHAR(50) NOT NULL,
    `tenant_name` VARCHAR(255) NOT NULL,
    `admin_email` VARCHAR(255) NOT NULL,
    `phone_number` VARCHAR(20) NULL,
    `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    `registration_id` CHAR(36) NULL,
    `is_deleted` BOOLEAN NOT NULL DEFAULT FALSE,
    `widget_api_key` VARCHAR(128) NULL,
    `widget_secret_key` VARCHAR(255) NULL,
    `organisation_id` CHAR(36) NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT `pk_central__tenants` PRIMARY KEY (`id`),
    CONSTRAINT `uk_central__tenants_tenant_code` UNIQUE (`tenant_code`),
    CONSTRAINT `uk_central__tenants_admin_email` UNIQUE (`admin_email`),
    CONSTRAINT `uk_central__tenants_organisation_id` UNIQUE (`organisation_id`),
    INDEX `idx_central__tenants_status` (`status`, `is_deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ------------------------------------------------------------------------------
-- 2. central__users: Global public end-users across all tenant venues
-- ------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `central__users` (
    `id` CHAR(36) NOT NULL,
    `phone_number` VARCHAR(20) NOT NULL,
    `email` VARCHAR(255) NULL,
    `recovery_email` VARCHAR(255) NULL,
    `full_name` VARCHAR(150) NOT NULL,
    `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    `is_deleted` BOOLEAN NOT NULL DEFAULT FALSE,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT `pk_central__users` PRIMARY KEY (`id`),
    CONSTRAINT `uk_central__users_phone_number` UNIQUE (`phone_number`),
    CONSTRAINT `uk_central__users_email` UNIQUE (`email`),
    INDEX `idx_central__users_status` (`status`, `is_deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ------------------------------------------------------------------------------
-- 3. central__tenant_quotas: Operational capacity and resource limits
-- ------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `central__tenant_quotas` (
    `id` CHAR(36) NOT NULL,
    `tenant_id` CHAR(36) NOT NULL,
    `max_staffs` INT NOT NULL DEFAULT 5,
    `daily_ticket_quota` INT NOT NULL DEFAULT 200,
    `max_services` INT NOT NULL DEFAULT 20,
    `max_appointments` INT NOT NULL DEFAULT 500,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT `pk_central__tenant_quotas` PRIMARY KEY (`id`),
    CONSTRAINT `uk_central__tenant_quotas_tenant_id` UNIQUE (`tenant_id`),
    CONSTRAINT `fk_central__tenant_quotas_tenant` FOREIGN KEY (`tenant_id`)
        REFERENCES `central__tenants` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ------------------------------------------------------------------------------
-- 4. central__system_configs: Dynamic global key-value configuration
-- ------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `central__system_configs` (
    `id` CHAR(36) NOT NULL,
    `config_key` VARCHAR(100) NOT NULL,
    `config_value` TEXT NOT NULL,
    `description` VARCHAR(255) NULL,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT `pk_central__system_configs` PRIMARY KEY (`id`),
    CONSTRAINT `uk_central__system_configs_config_key` UNIQUE (`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ------------------------------------------------------------------------------
-- 5. central__global_audit_logs: Platform-level administrative and security audit
-- ------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `central__global_audit_logs` (
    `id` CHAR(36) NOT NULL,
    `tenant_id` CHAR(36) NULL,
    `actor_id` VARCHAR(100) NOT NULL,
    `action` VARCHAR(100) NOT NULL,
    `details` TEXT NULL,
    `ip_address` VARCHAR(45) NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT `pk_central__global_audit_logs` PRIMARY KEY (`id`),
    CONSTRAINT `fk_central__global_audit_logs_tenant` FOREIGN KEY (`tenant_id`)
        REFERENCES `central__tenants` (`id`) ON DELETE SET NULL,
    INDEX `idx_central__audit_tenant_action` (`tenant_id`, `action`),
    INDEX `idx_central__audit_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ------------------------------------------------------------------------------
-- 6. central__allowed_domains: Whitelisted web domains & widget origins per tenant
-- ------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `central__allowed_domains` (
    `id` CHAR(36) NOT NULL,
    `tenant_id` CHAR(36) NOT NULL,
    `domain_name` VARCHAR(255) NOT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT `pk_central__allowed_domains` PRIMARY KEY (`id`, `tenant_id`),
    CONSTRAINT `uk_central__allowed_domains_tenant_domain` UNIQUE (`tenant_id`, `domain_name`),
    CONSTRAINT `fk_central__allowed_domains_tenant` FOREIGN KEY (`tenant_id`)
        REFERENCES `central__tenants` (`id`) ON DELETE CASCADE,
    INDEX `idx_central__allowed_domains_name` (`domain_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ------------------------------------------------------------------------------
-- 7. central__global_user_appointments: High-speed cross-tenant appointment index
-- ------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `central__global_user_appointments` (
    `id` CHAR(36) NOT NULL,
    `user_id` CHAR(36) NOT NULL,
    `tenant_id` CHAR(36) NOT NULL,
    `appointment_id` CHAR(36) NOT NULL,
    `tenant_code` VARCHAR(50) NOT NULL,
    `appointment_code` VARCHAR(50) NOT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT `pk_central__global_user_appointments` PRIMARY KEY (`id`),
    CONSTRAINT `fk_central__gua_user` FOREIGN KEY (`user_id`)
        REFERENCES `central__users` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_central__gua_tenant` FOREIGN KEY (`tenant_id`)
        REFERENCES `central__tenants` (`id`) ON DELETE CASCADE,
    INDEX `idx_central__gua_user_created` (`user_id`, `created_at`),
    INDEX `idx_central__gua_appointment` (`tenant_code`, `appointment_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
