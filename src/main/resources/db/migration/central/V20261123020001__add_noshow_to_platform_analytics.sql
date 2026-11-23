-- ==============================================================================
-- NextTurn Relational Database Migration (Flyway V20261123020001)
-- Schema: Central Management Schema (nextturn_master)
-- Description: Track daily platform-wide NO_SHOW ticket totals for
--              REQ-DASHBOARD-05 monitoring and historical trend charts.
-- Target Engine: MySQL 8.4 LTS (Oracle HeatWave Compatible) / InnoDB
-- ==============================================================================

ALTER TABLE `central__analytics_daily_platform`
    ADD COLUMN `total_tickets_no_show` INT NOT NULL DEFAULT 0 AFTER `total_tickets_completed`,
    ADD CONSTRAINT `chk_central__adp_tickets_no_show` CHECK (`total_tickets_no_show` >= 0);
