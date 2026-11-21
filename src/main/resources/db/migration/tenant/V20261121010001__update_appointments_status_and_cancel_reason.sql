-- ==============================================================================
-- Migration: V20261121010001__update_appointments_status_and_cancel_reason.sql
-- Description: Add cancellation_reason column and COMPLETED status to tenant__appointments (REQ-APT-04, REQ-APT-05)
-- ==============================================================================

ALTER TABLE `tenant__appointments`
    ADD COLUMN `cancellation_reason` VARCHAR(500) NULL DEFAULT NULL AFTER `notes`;

ALTER TABLE `tenant__appointments`
    DROP CHECK `chk_tenant__appointments_status`;

ALTER TABLE `tenant__appointments`
    ADD CONSTRAINT `chk_tenant__appointments_status`
        CHECK (`status` IN ('PENDING', 'CONFIRMED', 'CHECKED_IN', 'COMPLETED', 'CANCELLED', 'NO_SHOW'));
