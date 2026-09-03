package com.heysaz.erp.inventory.api;

/**
 * FR-INV-003: Every movement must declare its type.
 */
public enum MovementType {
    RECEIPT,
    SALE,
    RETURN,
    ADJUSTMENT,
    COUNT_CORRECTION,
    TRANSFER_OUT,
    TRANSFER_IN,
    WRITE_OFF
}
