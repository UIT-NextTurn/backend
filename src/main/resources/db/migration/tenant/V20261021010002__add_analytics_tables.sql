-- ==============================================================================
-- NextTurn Relational Database Migration (Flyway V20261021010002)
-- Schema: Tenant Dedicated Schema (tenant_{code})
-- Description: Add partitioned analytics tables for daily service rollups,
--              daily staff evaluations, and hourly traffic profiling.
-- Target Engine: MySQL 8.4 LTS (Oracle HeatWave Compatible) / InnoDB
-- ==============================================================================

-- ------------------------------------------------------------------------------
-- 1. tenant__analytics_daily_services: Daily breakdown per service with Range Partitioning
-- Note: MySQL InnoDB does not support foreign key constraints on partitioned tables.
-- Logical reference is maintained to tenant__services(id) and indexed.
-- ------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `tenant__analytics_daily_services` (
    `id` CHAR(36) NOT NULL,
    `service_id` CHAR(36) NOT NULL COMMENT 'Logical reference to tenant__services.id',
    `report_date` DATE NOT NULL,
    `total_tickets` INT NOT NULL DEFAULT 0,
    `appointment_tickets` INT NOT NULL DEFAULT 0,
    `walk_in_tickets` INT NOT NULL DEFAULT 0,
    `completed_tickets` INT NOT NULL DEFAULT 0,
    `no_show_tickets` INT NOT NULL DEFAULT 0,
    `cancelled_appts` INT NOT NULL DEFAULT 0,
    `total_wait_minutes` BIGINT NOT NULL DEFAULT 0,
    `total_serve_minutes` BIGINT NOT NULL DEFAULT 0,
    `rating_1_count` INT NOT NULL DEFAULT 0,
    `rating_2_count` INT NOT NULL DEFAULT 0,
    `rating_3_count` INT NOT NULL DEFAULT 0,
    `rating_4_count` INT NOT NULL DEFAULT 0,
    `rating_5_count` INT NOT NULL DEFAULT 0,
    `feedback_count` INT NOT NULL DEFAULT 0,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT `pk_tenant__analytics_daily_services` PRIMARY KEY (`id`, `report_date`),
    CONSTRAINT `uk_tenant__ads_service_date` UNIQUE (`service_id`, `report_date`),
    CONSTRAINT `chk_tenant__ads_total_tickets` CHECK (`total_tickets` >= 0),
    CONSTRAINT `chk_tenant__ads_appointment_tickets` CHECK (`appointment_tickets` >= 0),
    CONSTRAINT `chk_tenant__ads_walk_in_tickets` CHECK (`walk_in_tickets` >= 0),
    CONSTRAINT `chk_tenant__ads_completed_tickets` CHECK (`completed_tickets` >= 0),
    CONSTRAINT `chk_tenant__ads_no_show_tickets` CHECK (`no_show_tickets` >= 0),
    CONSTRAINT `chk_tenant__ads_cancelled_appts` CHECK (`cancelled_appts` >= 0),
    CONSTRAINT `chk_tenant__ads_wait_minutes` CHECK (`total_wait_minutes` >= 0),
    CONSTRAINT `chk_tenant__ads_serve_minutes` CHECK (`total_serve_minutes` >= 0),
    CONSTRAINT `chk_tenant__ads_rating_1` CHECK (`rating_1_count` >= 0),
    CONSTRAINT `chk_tenant__ads_rating_2` CHECK (`rating_2_count` >= 0),
    CONSTRAINT `chk_tenant__ads_rating_3` CHECK (`rating_3_count` >= 0),
    CONSTRAINT `chk_tenant__ads_rating_4` CHECK (`rating_4_count` >= 0),
    CONSTRAINT `chk_tenant__ads_rating_5` CHECK (`rating_5_count` >= 0),
    CONSTRAINT `chk_tenant__ads_feedback_count` CHECK (`feedback_count` >= 0),
    INDEX `idx_tenant__ads_service` (`service_id`)
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
-- 2. tenant__analytics_daily_staffs: Daily staff evaluation per service with Range Partitioning
-- Note: Logical references to tenant__staffs(id) and tenant__services(id).
-- ------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `tenant__analytics_daily_staffs` (
    `id` CHAR(36) NOT NULL,
    `staff_id` CHAR(36) NOT NULL COMMENT 'Logical reference to tenant__staffs.id',
    `service_id` CHAR(36) NOT NULL COMMENT 'Logical reference to tenant__services.id',
    `report_date` DATE NOT NULL,
    `served_count` INT NOT NULL DEFAULT 0,
    `total_duration_minutes` INT NOT NULL DEFAULT 0,
    `avg_duration_minutes` DECIMAL(8, 2) NOT NULL DEFAULT 0.00,
    `overtime_cases_count` INT NOT NULL DEFAULT 0,
    `positive_feedback_count` INT NOT NULL DEFAULT 0,
    `total_feedback_count` INT NOT NULL DEFAULT 0,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT `pk_tenant__analytics_daily_staffs` PRIMARY KEY (`id`, `report_date`),
    CONSTRAINT `uk_tenant__adst_staff_service_date` UNIQUE (`staff_id`, `service_id`, `report_date`),
    CONSTRAINT `chk_tenant__adst_served_count` CHECK (`served_count` >= 0),
    CONSTRAINT `chk_tenant__adst_total_duration` CHECK (`total_duration_minutes` >= 0),
    CONSTRAINT `chk_tenant__adst_avg_duration` CHECK (`avg_duration_minutes` >= 0.00),
    CONSTRAINT `chk_tenant__adst_overtime_cases` CHECK (`overtime_cases_count` >= 0),
    CONSTRAINT `chk_tenant__adst_positive_feedback` CHECK (`positive_feedback_count` >= 0),
    CONSTRAINT `chk_tenant__adst_total_feedback` CHECK (`total_feedback_count` >= 0),
    INDEX `idx_tenant__adst_staff` (`staff_id`),
    INDEX `idx_tenant__adst_service` (`service_id`)
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
-- 3. tenant__analytics_hourly_traffic: Hourly traffic profile per service with Range Partitioning
-- Note: Logical reference to tenant__services(id).
-- ------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `tenant__analytics_hourly_traffic` (
    `id` CHAR(36) NOT NULL,
    `service_id` CHAR(36) NOT NULL COMMENT 'Logical reference to tenant__services.id',
    `report_date` DATE NOT NULL,
    `hour_slot` TINYINT NOT NULL,
    `ticket_count` INT NOT NULL DEFAULT 0,
    `completed_count` INT NOT NULL DEFAULT 0,
    `avg_wait_minutes` DECIMAL(8, 2) NOT NULL DEFAULT 0.00,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT `pk_tenant__analytics_hourly_traffic` PRIMARY KEY (`id`, `report_date`),
    CONSTRAINT `uk_tenant__aht_service_date_hour` UNIQUE (`service_id`, `report_date`, `hour_slot`),
    CONSTRAINT `chk_tenant__aht_hour_slot` CHECK (`hour_slot` BETWEEN 0 AND 23),
    CONSTRAINT `chk_tenant__aht_ticket_count` CHECK (`ticket_count` >= 0),
    CONSTRAINT `chk_tenant__aht_completed_count` CHECK (`completed_count` >= 0),
    CONSTRAINT `chk_tenant__aht_avg_wait` CHECK (`avg_wait_minutes` >= 0.00),
    INDEX `idx_tenant__aht_service` (`service_id`)
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
-- 4. Automated Tenant Partition Maintenance Routine and Monthly Event
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
    CALL maintain_table_partition('tenant__analytics_daily_services');
    CALL maintain_table_partition('tenant__analytics_daily_staffs');
    CALL maintain_table_partition('tenant__analytics_hourly_traffic');
END$$

DELIMITER ;

-- Schedule to run on the 1st of every month at 00:00:00
CREATE EVENT IF NOT EXISTS `evt_manage_partitions`
ON SCHEDULE EVERY 1 MONTH
STARTS '2026-11-01 00:00:00'
DO
    CALL auto_create_next_partition();
