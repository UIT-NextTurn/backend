-- ==============================================================================
-- NextTurn Relational Database Migration (Flyway V20261021010001)
-- Schema: Central Management Schema (nextturn_master)
-- Description: Core platform tables for tenant orchestration, quotas, IAM, global users,
--              and centralized analytics metrics with table partitioning.
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
    CONSTRAINT `chk_central__tenants_status` CHECK (`status` IN ('ACTIVE', 'PENDING', 'BLOCKED', 'DELETED')),
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
    CONSTRAINT `chk_central__users_status` CHECK (`status` IN ('ACTIVE', 'BLOCKED', 'DELETED')),
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
        REFERENCES `central__tenants` (`id`) ON DELETE CASCADE,
    CONSTRAINT `chk_central__quotas_max_staffs` CHECK (`max_staffs` >= 1),
    CONSTRAINT `chk_central__quotas_daily_ticket` CHECK (`daily_ticket_quota` >= 1),
    CONSTRAINT `chk_central__quotas_max_services` CHECK (`max_services` >= 1),
    CONSTRAINT `chk_central__quotas_max_appointments` CHECK (`max_appointments` >= 1)
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
-- 6. central__allowed_domains: Whitelisted web domains and widget origins per tenant
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

-- ------------------------------------------------------------------------------
-- 8. central__analytics_daily_platform: Platform-wide daily metrics with Range Partitioning
-- ------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `central__analytics_daily_platform` (
    `id` CHAR(36) NOT NULL,
    `report_date` DATE NOT NULL,
    `active_tenants_count` INT NOT NULL DEFAULT 0,
    `suspended_tenants_count` INT NOT NULL DEFAULT 0,
    `new_registrations_count` INT NOT NULL DEFAULT 0,
    `total_tickets_issued` INT NOT NULL DEFAULT 0,
    `total_tickets_completed` INT NOT NULL DEFAULT 0,
    `total_active_users` INT NOT NULL DEFAULT 0,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT `pk_central__analytics_daily_platform` PRIMARY KEY (`id`, `report_date`),
    CONSTRAINT `uk_central__analytics_daily_platform_date` UNIQUE (`report_date`),
    CONSTRAINT `chk_central__adp_active_tenants` CHECK (`active_tenants_count` >= 0),
    CONSTRAINT `chk_central__adp_suspended_tenants` CHECK (`suspended_tenants_count` >= 0),
    CONSTRAINT `chk_central__adp_new_registrations` CHECK (`new_registrations_count` >= 0),
    CONSTRAINT `chk_central__adp_tickets_issued` CHECK (`total_tickets_issued` >= 0),
    CONSTRAINT `chk_central__adp_tickets_completed` CHECK (`total_tickets_completed` >= 0),
    CONSTRAINT `chk_central__adp_active_users` CHECK (`total_active_users` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
PARTITION BY RANGE COLUMNS (`report_date`) (
    PARTITION p_before_2026 VALUES LESS THAN ('2026-01-01'),
    PARTITION p_2026_q1 VALUES LESS THAN ('2026-04-01'),
    PARTITION p_2026_q2 VALUES LESS THAN ('2026-07-01'),
    PARTITION p_2026_q3 VALUES LESS THAN ('2026-10-01'),
    PARTITION p_2026_q4 VALUES LESS THAN ('2027-01-01'),
    PARTITION p_2027_q1 VALUES LESS THAN ('2027-04-01'),
    PARTITION p_2027_q2 VALUES LESS THAN ('2027-07-01'),
    PARTITION p_2027_q3 VALUES LESS THAN ('2027-10-01'),
    PARTITION p_2027_q4 VALUES LESS THAN ('2028-01-01')
);

-- ------------------------------------------------------------------------------
-- 9. central__analytics_tenant_summaries: Daily summary per tenant with Range Partitioning
-- Note: MySQL InnoDB does not support foreign key constraints on partitioned tables.
-- Logical reference is maintained to central__tenants(id) and optimized with an index.
-- ------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `central__analytics_tenant_summaries` (
    `id` CHAR(36) NOT NULL,
    `tenant_id` CHAR(36) NOT NULL COMMENT 'Logical reference to central__tenants.id',
    `report_date` DATE NOT NULL,
    `active_staff_count` INT NOT NULL DEFAULT 0,
    `active_services_count` INT NOT NULL DEFAULT 0,
    `daily_tickets_count` INT NOT NULL DEFAULT 0,
    `is_quota_exceeded` BOOLEAN NOT NULL DEFAULT FALSE,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT `pk_central__analytics_tenant_summaries` PRIMARY KEY (`id`, `report_date`),
    CONSTRAINT `uk_central__tenant_summaries_tenant_date` UNIQUE (`tenant_id`, `report_date`),
    CONSTRAINT `chk_central__ats_active_staff` CHECK (`active_staff_count` >= 0),
    CONSTRAINT `chk_central__ats_active_services` CHECK (`active_services_count` >= 0),
    CONSTRAINT `chk_central__ats_daily_tickets` CHECK (`daily_tickets_count` >= 0),
    INDEX `idx_central__tenant_summaries_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
PARTITION BY RANGE COLUMNS (`report_date`) (
    PARTITION p_before_2026 VALUES LESS THAN ('2026-01-01'),
    PARTITION p_2026_q1 VALUES LESS THAN ('2026-04-01'),
    PARTITION p_2026_q2 VALUES LESS THAN ('2026-07-01'),
    PARTITION p_2026_q3 VALUES LESS THAN ('2026-10-01'),
    PARTITION p_2026_q4 VALUES LESS THAN ('2027-01-01'),
    PARTITION p_2027_q1 VALUES LESS THAN ('2027-04-01'),
    PARTITION p_2027_q2 VALUES LESS THAN ('2027-07-01'),
    PARTITION p_2027_q3 VALUES LESS THAN ('2027-10-01'),
    PARTITION p_2027_q4 VALUES LESS THAN ('2028-01-01')
);

-- ------------------------------------------------------------------------------
-- 10. Automated Partition Maintenance Routine and Monthly Event
-- ------------------------------------------------------------------------------
DELIMITER $$

CREATE PROCEDURE IF NOT EXISTS `maintain_table_partition`(IN p_table_name VARCHAR(64))
BEGIN
    DECLARE v_last_part_desc VARCHAR(255);
    DECLARE v_last_date DATE;
    DECLARE v_next_date DATE;
    DECLARE v_new_part_name VARCHAR(64);
    DECLARE v_part_exists INT DEFAULT 0;

    -- Retrieve latest partition boundary definition
    SELECT partition_description
    INTO v_last_part_desc
    FROM information_schema.partitions
    WHERE table_schema = DATABASE()
      AND table_name = p_table_name
      AND partition_name IS NOT NULL
    ORDER BY partition_ordinal_position DESC
    LIMIT 1;

    -- Calculate subsequent quarterly milestones and execute dynamic SQL
    IF v_last_part_desc IS NOT NULL AND v_last_part_desc != 'MAXVALUE' THEN
        SET v_last_date = STR_TO_DATE(REPLACE(v_last_part_desc, '''', ''), '%Y-%m-%d');

        WHILE v_last_date <= DATE_ADD(CURDATE(), INTERVAL 6 MONTH) DO
            SET v_new_part_name = CONCAT('p_', YEAR(v_last_date), '_q', ((MONTH(v_last_date) - 1) DIV 3) + 1);
            SET v_next_date = DATE_ADD(v_last_date, INTERVAL 3 MONTH);

            SELECT COUNT(*) INTO v_part_exists
            FROM information_schema.partitions
            WHERE table_schema = DATABASE()
              AND table_name = p_table_name
              AND partition_name = v_new_part_name;

            IF v_part_exists = 0 THEN
                SET @sql_cmd = CONCAT(
                    'ALTER TABLE `', p_table_name, '` ADD PARTITION (PARTITION `',
                    v_new_part_name, '` VALUES LESS THAN (''',
                    DATE_FORMAT(v_next_date, '%Y-%m-%d'), '''))'
                );
                PREPARE stmt FROM @sql_cmd;
                EXECUTE stmt;
                DEALLOCATE PREPARE stmt;
            END IF;

            SET v_last_date = v_next_date;
        END WHILE;
    END IF;
END$$

CREATE PROCEDURE IF NOT EXISTS `auto_create_next_partition`()
BEGIN
    CALL maintain_table_partition('central__analytics_daily_platform');
    CALL maintain_table_partition('central__analytics_tenant_summaries');
END$$

DELIMITER ;

-- Schedule to run on the 1st of every month at 00:00:00
CREATE EVENT IF NOT EXISTS `evt_manage_partitions`
ON SCHEDULE EVERY 1 MONTH
STARTS '2026-11-01 00:00:00'
DO
    CALL auto_create_next_partition();
