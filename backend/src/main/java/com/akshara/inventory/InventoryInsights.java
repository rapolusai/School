package com.akshara.inventory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.inventory.InventoryViews.InventoryCard;
import com.akshara.shared.TenantContext;

/** What other modules may read from the inventory: the dashboard card for storekeepers and the School Admin. */
@Service
@Transactional(readOnly = true)
public class InventoryInsights {

    private final InventoryReportService reports;
    private final ProcurementService procurement;
    private final VerificationRoundRepository rounds;

    InventoryInsights(InventoryReportService reports, ProcurementService procurement,
            VerificationRoundRepository rounds) {
        this.reports = reports;
        this.procurement = procurement;
        this.rounds = rounds;
    }

    /** Low-stock items, requisitions and orders waiting for approval, open rounds and assets due for verification. */
    public InventoryCard card() {
        TenantContext.require();
        return new InventoryCard(reports.lowStockCount(), procurement.requisitionsWaiting(),
                procurement.ordersWaiting(), rounds.countOpen(), reports.verificationDue());
    }
}
