/**
 * Inventory: the item master with categories and locations, consumable stock with its movement ledger and stock
 * takes, the fixed-asset register with tags, custodians, transfers, disposal and verification rounds, and procurement
 * (requisitions, RFQs, purchase orders with GST, approvals within limits, partial receipts and invoices). Vendors are
 * the accounts module's. Other modules use {@link com.akshara.inventory.InventoryInsights} (the dashboard card) and
 * listen for {@link com.akshara.inventory.PurchaseReceived}.
 */
package com.akshara.inventory;
