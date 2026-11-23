-- ==============================================================================
-- NextTurn Relational Database Migration (Flyway V20261123010001)
-- Schema: Tenant Dedicated Schema (tenant_{code})
-- Description: Persist feedback session tokens and 24-hour TTL expiry on
--              completed queue tickets (REQ-FEEDBACK-01), plus search indexing
--              on tenant__feedbacks (REQ-FEEDBACK-04).
-- Target Engine: MySQL 8.4 LTS (Oracle HeatWave Compatible) / InnoDB
-- ==============================================================================

ALTER TABLE `tenant__queue_tickets`
    ADD COLUMN `feedback_token` VARCHAR(64) NULL AFTER `approaching_alert_sent`,
    ADD COLUMN `feedback_token_expires_at` DATETIME NULL AFTER `feedback_token`,
    ADD CONSTRAINT `uk_tenant__tickets_feedback_token` UNIQUE (`feedback_token`);

CREATE INDEX `idx_tenant__feedbacks_created_at`
    ON `tenant__feedbacks` (`created_at`, `rating_score`);
