package com.akshara.inventory;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Published (as a Spring application event, inside the receiving transaction) when goods are received against a
 * purchase order, for the accounts module to record the vendor's bill. {@code amountPaise} is this receipt's value
 * including GST; {@code invoiceNumber} is the vendor's invoice as recorded on the receipt (or on the order), null
 * when none was given. Listeners that must not act on a rolled-back receipt should use
 * {@code @TransactionalEventListener}.
 */
public record PurchaseReceived(UUID tenantId, UUID purchaseOrderId, String purchaseOrderNumber, UUID goodsReceiptId,
        String goodsReceiptNumber, UUID vendorId, String vendorName, long amountPaise, String invoiceNumber,
        LocalDate receivedOn, boolean complete, List<Item> items, UUID receivedById, Instant receivedAt) {

    /** One line received: the item (null for a free-text line), what it is, how many and the unit price before GST. */
    public record Item(UUID itemId, String description, int quantity, String unit, long unitPaise, int gstRateBp) {
    }
}
