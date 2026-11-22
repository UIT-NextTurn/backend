-- ==============================================================================
-- Migration: V20261122030001__add_counter_label_and_qc_indexes.sql
-- Description: Add counter_label for lobby display announcements and composite
--              indexes for REQ-QC-01..04 weighted priority calling, specialist
--              load checking, and auto-completion safety net queries.
-- ==============================================================================

ALTER TABLE `tenant__queue_tickets`
    ADD COLUMN `counter_label` VARCHAR(50) NULL DEFAULT NULL AFTER `provider_id`;

CREATE INDEX `idx_tenant__ticket_qc_priority`
    ON `tenant__queue_tickets` (`work_date`, `service_id`, `status`, `issued_at`);

CREATE INDEX `idx_tenant__ticket_serving_provider`
    ON `tenant__queue_tickets` (`work_date`, `provider_id`, `status`);
