package com.heysaz.erp.inventory.api;

/**
 * FR-INV-010: Inter-shop transfer lifecycle states.
 */
public enum TransferStatus {
    DRAFT,
    DISPATCHED,
    PARTIALLY_RECEIVED,
    RECEIVED,
    CANCELLED
}
