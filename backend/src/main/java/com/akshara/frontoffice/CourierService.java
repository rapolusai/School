package com.akshara.frontoffice;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.frontoffice.FrontOfficeForms.CourierForm;
import com.akshara.frontoffice.FrontOfficeViews.CourierView;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/** The postal and courier register: inward and outward items and who signed for them (a typed name). Audited. */
@Service
@Transactional
public class CourierService {

    /** The longest date range the register lists at once. */
    static final int MAX_RANGE_DAYS = 400;

    private final CourierItemRepository items;
    private final AuditService audit;

    CourierService(CourierItemRepository items, AuditService audit) {
        this.items = items;
        this.audit = audit;
    }

    /** Items dated between two days (this month when neither is given), latest first. */
    @Transactional(readOnly = true)
    public List<CourierView> list(LocalDate from, LocalDate to) {
        TenantContext.require();
        LocalDate today = FrontOfficeText.today();
        LocalDate end = to == null ? today : to;
        LocalDate start = from == null ? end.withDayOfMonth(1) : from;
        if (end.isBefore(start)) {
            throw ApiException.badRequest("The end date is before the start date.", "to");
        }
        if (start.plusDays(MAX_RANGE_DAYS).isBefore(end)) {
            throw ApiException.badRequest("Choose a range of at most " + MAX_RANGE_DAYS + " days.", "to");
        }
        return items.between(start, end).stream().map(CourierService::view).toList();
    }

    public CourierView add(CourierForm form, Actor actor) {
        TenantContext.require();
        CourierItem item = items.save(new CourierItem(details(form), actor, Instant.now()));
        audit.record(actor, "courier.logged", "courier", item.getId(), auditDetails(item));
        items.flush();
        return view(item);
    }

    public CourierView update(UUID id, CourierForm form, Actor actor) {
        TenantContext.require();
        CourierItem item = require(id);
        item.apply(details(form));
        audit.record(actor, "courier.updated", "courier", id, auditDetails(item));
        items.flush();
        return view(item);
    }

    public void remove(UUID id, Actor actor) {
        TenantContext.require();
        CourierItem item = require(id);
        items.delete(item);
        audit.record(actor, "courier.removed", "courier", id, auditDetails(item));
        items.flush();
    }

    private CourierItem require(UUID id) {
        return items.findById(id).orElseThrow(() -> ApiException.notFound("Courier item"));
    }

    private static CourierItem.Details details(CourierForm form) {
        if (form.itemDate().isAfter(FrontOfficeText.today().plusDays(1))) {
            throw ApiException.badRequest("The date cannot be in the future.", "itemDate");
        }
        return new CourierItem.Details(form.direction(), form.item().trim(), form.sender().trim(),
                form.receiver().trim(), form.itemDate(), FrontOfficeText.clean(form.carrier()),
                FrontOfficeText.clean(form.trackingNo()), FrontOfficeText.clean(form.receivedBy()),
                FrontOfficeText.clean(form.note()));
    }

    private static Map<String, Object> auditDetails(CourierItem c) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("direction", c.getDirection().name());
        details.put("item", c.getItem());
        details.put("date", c.getItemDate().toString());
        if (c.getTrackingNo() != null) {
            details.put("trackingNo", c.getTrackingNo());
        }
        return details;
    }

    static CourierView view(CourierItem c) {
        return new CourierView(c.getId(), c.getDirection(), c.getItem(), c.getSender(), c.getReceiver(),
                c.getItemDate(), c.getCarrier(), c.getTrackingNo(), c.getReceivedBy(), c.getNote(),
                c.getLoggedByName(), c.getCreatedAt());
    }
}
