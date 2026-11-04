package com.nextturn.enums;

/**
 * Operational lifecycle status of an encounter staff shift (*trạng thái ca làm việc của nhân viên*).
 */
public enum ShiftStatus {

    /**
     * Shift is active and scheduled for upcoming operations (*đã lên lịch*).
     */
    SCHEDULED,

    /**
     * Shift has been executed and completed (*đã hoàn thành*).
     */
    COMPLETED,

    /**
     * Shift was cancelled (*đã hủy*).
     */
    CANCELLED
}

