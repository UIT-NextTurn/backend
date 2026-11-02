-- ==============================================================================
-- NextTurn Relational Database Seed Script
-- Target Schema: tenant_{code} (e.g. tenant_shop_b or tenant_spa_a)
-- Description: Sample business catalog data (services, counters, staffs)
-- Usage:
--   Replace `tenant_shop_b` with your target tenant schema name, then run:
--     mysql -h localhost -P 3306 -u nextturn -pnextturn_dev_secret tenant_shop_b < 01_seed_tenant_sample_data.sql
-- ==============================================================================

-- Example target schema (uncomment and adjust as needed):
-- USE `tenant_shop_b`;

-- 1. Sample Services Catalog
INSERT INTO `tenant__services` (
    `id`,
    `service_code`,
    `service_name`,
    `standard_duration`,
    `price`,
    `status`,
    `is_deleted`,
    `created_at`,
    `updated_at`
) VALUES
(
    '018f0000-0000-7000-8000-000000000101',
    'CONSULT_GENERAL',
    'General Consultation',
    15,
    50000.00,
    'ACTIVE',
    FALSE,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
),
(
    '018f0000-0000-7000-8000-000000000102',
    'FAST_CHECKOUT',
    'Express Counter Service',
    10,
    0.00,
    'ACTIVE',
    FALSE,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
)
ON DUPLICATE KEY UPDATE
    `service_name` = VALUES(`service_name`),
    `standard_duration` = VALUES(`standard_duration`),
    `price` = VALUES(`price`),
    `status` = VALUES(`status`),
    `updated_at` = CURRENT_TIMESTAMP;

-- 2. Sample Counters
INSERT INTO `tenant__counters` (
    `id`,
    `counter_code`,
    `counter_name`,
    `status`,
    `is_deleted`,
    `created_at`,
    `updated_at`
) VALUES
(
    '018f0000-0000-7000-8000-000000000201',
    'COUNTER_01',
    'Quầy số 01',
    'ACTIVE',
    FALSE,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
),
(
    '018f0000-0000-7000-8000-000000000202',
    'COUNTER_02',
    'Quầy số 02',
    'ACTIVE',
    FALSE,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
)
ON DUPLICATE KEY UPDATE
    `counter_name` = VALUES(`counter_name`),
    `status` = VALUES(`status`),
    `updated_at` = CURRENT_TIMESTAMP;

-- 3. Sample Staff Members
INSERT INTO `tenant__staffs` (
    `id`,
    `staff_code`,
    `keycloak_user_id`,
    `full_name`,
    `email`,
    `phone_number`,
    `role`,
    `status`,
    `is_deleted`,
    `created_at`,
    `updated_at`
) VALUES
(
    '018f0000-0000-7000-8000-000000000301',
    'STAFF_ADMIN_01',
    NULL,
    'Quản trị viên Chi nhánh',
    'admin@facility.internal',
    '0901000001',
    'ADMIN',
    'ACTIVE',
    FALSE,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
),
(
    '018f0000-0000-7000-8000-000000000302',
    'STAFF_COUNTER_01',
    NULL,
    'Nhân viên Tiếp đón 01',
    'counter01@facility.internal',
    '0901000002',
    'COUNTER',
    'ACTIVE',
    FALSE,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
)
ON DUPLICATE KEY UPDATE
    `full_name` = VALUES(`full_name`),
    `role` = VALUES(`role`),
    `status` = VALUES(`status`),
    `updated_at` = CURRENT_TIMESTAMP;
