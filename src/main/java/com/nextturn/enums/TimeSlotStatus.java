package com.nextturn.enums;

/**
 * Availability state of an appointment booking time slot (*trạng thái khung giờ đặt hẹn*).
 */
public enum TimeSlotStatus {

    /**
     * Slot has remaining capacity and is open for booking (*khả dụng*).
     */
    AVAILABLE,

    /**
     * Slot is temporarily held during checkout or checkout lock (*tạm giữ chỗ*).
     */
    HOLDING,

    /**
     * Slot reached full capacity or is fully reserved (*đã kín chỗ*).
     */
    BOOKED
}

