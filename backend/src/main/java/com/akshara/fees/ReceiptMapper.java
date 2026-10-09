package com.akshara.fees;

import java.util.List;

import com.akshara.fees.FeeViews.ReceiptLine;
import com.akshara.fees.FeeViews.ReceiptSummary;
import com.akshara.fees.FeeViews.ReceiptView;
import com.akshara.platform.SchoolProfile;

/** Turns receipts into API views. */
final class ReceiptMapper {

    private ReceiptMapper() {
    }

    static ReceiptSummary summary(Receipt r) {
        return new ReceiptSummary(r.getId(), r.getReceiptNo(), r.getReceivedOn(), r.getReceivedAt(), r.getStudentId(),
                r.getStudentName(), r.getAdmissionNo(), r.getClassLabel(), r.getMode(), r.getSource(),
                r.getAmountPaise(), r.getLateFeePaise(), r.getStatus(), r.getCollectedByName());
    }

    /** The receipt as printed: only the original allocations, never the reversal rows of a cancellation. */
    static ReceiptView view(Receipt r, List<PaymentAllocation> allocations, SchoolProfile school) {
        List<ReceiptLine> lines = allocations.stream()
                .filter(a -> PaymentAllocation.ALLOCATION.equals(a.getEntry()))
                .map(a -> new ReceiptLine(a.getKind(), a.getInstalmentLabel(), a.getHeadName(), a.getAmountPaise()))
                .toList();
        return new ReceiptView(r.getId(), r.getReceiptNo(), r.getFinancialYear(), r.getReceivedOn(),
                r.getReceivedAt(), r.getStudentId(), r.getStudentName(), r.getAdmissionNo(), r.getClassLabel(),
                r.getMode(), r.getChequeNo(), r.getBankName(), r.getReference(), r.getSource(),
                r.getGatewayPaymentId(), r.getAmountPaise(), r.getLateFeePaise(),
                AmountInWords.rupees(r.getAmountPaise()), r.getRemarks(), r.getCollectedByName(), r.getStatus(),
                r.getCancelledAt(), r.getCancelledByName(), r.getCancelReason(), lines, school);
    }
}
