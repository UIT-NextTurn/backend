-- ==============================================================================
-- NextTurn Relational Database Seed Script
-- Target Schema: nextturn_master
-- Description: Seed initial platform tenants matching Keycloak Single Realm Organizations (shop-b, spa-a)
-- Usage:
--   Run manually against nextturn_master schema:
--     mysql -h localhost -P 3306 -u nextturn -pnextturn_dev_secret nextturn_master < 01_seed_central_tenants.sql
--   Or with Docker/Podman:
--     docker exec -i nextturn-mysql mysql -u nextturn -pnextturn_dev_secret nextturn_master < 01_seed_central_tenants.sql
-- ==============================================================================

USE `nextturn_master`;

INSERT INTO `central__tenants` (
    `id`,
    `tenant_code`,
    `tenant_name`,
    `admin_email`,
    `status`,
    `is_deleted`,
    `organisation_id`,
    `created_at`,
    `updated_at`
) VALUES
(
    '018f0000-0000-7000-8000-000000000001',
    'shop-b',
    'Shop B Retail Facility',
    'admin@shop-b.internal',
    'ACTIVE',
    FALSE,
    'shop-b',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
),
(
    '018f0000-0000-7000-8000-000000000002',
    'spa-a',
    'Spa A Beauty Center',
    'admin@spa-a.internal',
    'ACTIVE',
    FALSE,
    'spa-a',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
)
ON DUPLICATE KEY UPDATE
    `tenant_name` = VALUES(`tenant_name`),
    `status` = VALUES(`status`),
    `organisation_id` = VALUES(`organisation_id`),
    `updated_at` = CURRENT_TIMESTAMP;
