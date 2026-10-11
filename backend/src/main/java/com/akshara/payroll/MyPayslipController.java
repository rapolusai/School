package com.akshara.payroll;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.payroll.PayrollViews.MyPayslipRow;
import com.akshara.payroll.PayrollViews.PayslipView;
import com.akshara.shared.CurrentUser;

/**
 * "My payslips" for any member of staff: their own payslips of approved and paid months. Someone else's payslip and
 * drafts are 404; parents and students get 403.
 */
@RestController
@RequestMapping("/api/me/payslips")
public class MyPayslipController {

    private final PayslipService payslips;

    MyPayslipController(PayslipService payslips) {
        this.payslips = payslips;
    }

    @GetMapping
    public List<MyPayslipRow> mine() {
        return payslips.mine(CurrentUser.requireId());
    }

    @GetMapping("/{id}")
    public PayslipView payslip(@PathVariable UUID id) {
        return payslips.mine(CurrentUser.requireId(), id);
    }
}
