-- ==============================================================================
-- NextTurn Relational Database Migration (Flyway V20261101030001)
-- Schema: Central Management Schema (nextturn_master)
-- Description: Add rejection_reason column to central__tenants table (REQ-TNT-06)
-- ==============================================================================

ALTER TABLE `central__tenants`
    ADD COLUMN `rejection_reason` VARCHAR(500) NULL AFTER `organisation_id`;
