package com.akshara.frontoffice;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.frontoffice.FrontOfficeForms.CallForm;
import com.akshara.frontoffice.FrontOfficeForms.CloseForm;
import com.akshara.frontoffice.FrontOfficeForms.ConvertForm;
import com.akshara.frontoffice.FrontOfficeForms.EnquiryForm;
import com.akshara.frontoffice.FrontOfficeViews.CallView;
import com.akshara.frontoffice.FrontOfficeViews.EnquiryView;
import com.akshara.frontoffice.FrontOfficeViews.FollowUpQueue;

/** The call log, the enquiry log and the follow-up queue. Reading needs frontoffice.read; changes frontoffice.manage. */
@RestController
@RequestMapping("/api/frontoffice")
public class EnquiryController {

    public record ReopenForm(LocalDate followUpOn) {
    }

    private final EnquiryService enquiries;

    EnquiryController(EnquiryService enquiries) {
        this.enquiries = enquiries;
    }

    @GetMapping("/calls")
    @PreAuthorize(FrontOfficeAuth.READ)
    public List<CallView> calls(@RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate date) {
        return enquiries.calls(date);
    }

    @PostMapping("/calls")
    @PreAuthorize(FrontOfficeAuth.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public CallView logCall(@Valid @RequestBody CallForm form) {
        return enquiries.logCall(form, FrontOfficeAuth.actor());
    }

    @PutMapping("/calls/{id}")
    @PreAuthorize(FrontOfficeAuth.MANAGE)
    public CallView updateCall(@PathVariable UUID id, @Valid @RequestBody CallForm form) {
        return enquiries.updateCall(id, form, FrontOfficeAuth.actor());
    }

    @PostMapping("/calls/{id}/close")
    @PreAuthorize(FrontOfficeAuth.MANAGE)
    public CallView closeCall(@PathVariable UUID id, @RequestBody(required = false) @Valid CloseForm form) {
        return enquiries.closeCall(id, form, FrontOfficeAuth.actor());
    }

    @GetMapping("/enquiries")
    @PreAuthorize(FrontOfficeAuth.READ)
    public List<EnquiryView> enquiries(
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate date) {
        return enquiries.enquiries(date);
    }

    @GetMapping("/enquiries/{id}")
    @PreAuthorize(FrontOfficeAuth.READ)
    public EnquiryView enquiry(@PathVariable UUID id) {
        return enquiries.enquiry(id);
    }

    @PostMapping("/enquiries")
    @PreAuthorize(FrontOfficeAuth.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public EnquiryView logEnquiry(@Valid @RequestBody EnquiryForm form) {
        return enquiries.logEnquiry(form, FrontOfficeAuth.actor());
    }

    @PutMapping("/enquiries/{id}")
    @PreAuthorize(FrontOfficeAuth.MANAGE)
    public EnquiryView updateEnquiry(@PathVariable UUID id, @Valid @RequestBody EnquiryForm form) {
        return enquiries.updateEnquiry(id, form, FrontOfficeAuth.actor());
    }

    @PostMapping("/enquiries/{id}/close")
    @PreAuthorize(FrontOfficeAuth.MANAGE)
    public EnquiryView closeEnquiry(@PathVariable UUID id, @RequestBody(required = false) @Valid CloseForm form) {
        return enquiries.closeEnquiry(id, form, FrontOfficeAuth.actor());
    }

    @PostMapping("/enquiries/{id}/reopen")
    @PreAuthorize(FrontOfficeAuth.MANAGE)
    public EnquiryView reopenEnquiry(@PathVariable UUID id, @RequestBody(required = false) ReopenForm form) {
        return enquiries.reopenEnquiry(id, form == null ? null : form.followUpOn(), FrontOfficeAuth.actor());
    }

    /** Converts an admission enquiry into an application (needs admissions.manage as well). */
    @PostMapping("/enquiries/{id}/convert")
    @PreAuthorize("hasAuthority('frontoffice.manage') and hasAuthority('admissions.manage')")
    public EnquiryView convert(@PathVariable UUID id, @Valid @RequestBody ConvertForm form) {
        return enquiries.convert(id, form, FrontOfficeAuth.actor());
    }

    @GetMapping("/follow-ups")
    @PreAuthorize(FrontOfficeAuth.READ)
    public FollowUpQueue followUps() {
        return enquiries.followUps();
    }
}
