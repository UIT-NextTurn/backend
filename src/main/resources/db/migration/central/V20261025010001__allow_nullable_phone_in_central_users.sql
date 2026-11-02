-- ==============================================================================
-- NextTurn Relational Database Migration (Flyway V20261025010001)
-- Schema: Central Management Schema (nextturn_master)
-- Description: Allow nullable phone_number for passwordless email+OTP customer registration
-- ==============================================================================

ALTER TABLE `central__users` MODIFY COLUMN `phone_number` VARCHAR(20) NULL;
