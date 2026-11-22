-- ==============================================================================
-- Migration: V20261122010001__enhance_queue_tickets_sequence_and_customer.sql
-- Description: Add customer contact fields, monotonic sequence_number, and
--              unique (work_date, ticket_code) constraint to tenant__queue_tickets
--              (REQ-TICKET-01, REQ-TICKET-04, REQ-CHECK-IN-02)
-- ==============================================================================

ALTER TABLE `tenant__queue_tickets`
    ADD COLUMN `sequence_number` INT NOT NULL DEFAULT 1 AFTER `ticket_code`,
    ADD COLUMN `customer_name` VARCHAR(150) NULL DEFAULT NULL AFTER `appointment_id`,
    ADD COLUMN `customer_phone` VARCHAR(20) NULL DEFAULT NULL AFTER `customer_name`;

ALTER TABLE `tenant__queue_tickets`
    ADD CONSTRAINT `uk_tenant__tickets_date_code` UNIQUE (`work_date`, `ticket_code`),
    ADD CONSTRAINT `chk_tenant__tickets_sequence_number` CHECK (`sequence_number` >= 1);

CREATE INDEX `idx_tenant__ticket_phone_date`
    ON `tenant__queue_tickets` (`work_date`, `customer_phone`, `status`);

CREATE INDEX `idx_tenant__ticket_service_seq`
    ON `tenant__queue_tickets` (`service_id`, `work_date`, `sequence_number`);
