package com.akshara.hostel;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.hostel.HostelForms.MealCount;
import com.akshara.hostel.HostelForms.MenuEntry;
import com.akshara.hostel.HostelForms.MenuForm;
import com.akshara.hostel.HostelForms.MessAttendanceForm;
import com.akshara.hostel.HostelViews.MealCountView;
import com.akshara.hostel.HostelViews.MenuSlot;
import com.akshara.hostel.HostelViews.MessDay;
import com.akshara.hostel.HostelViews.WeeklyMenu;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * The mess: a weekly menu per hostel (four meals a day, veg or non-veg) and the warden's daily head count per meal.
 * The menu is written by hostel.manage or the hostel's warden; the counts by the warden.
 */
@Service
@Transactional
public class MessService {

    private final MenuItemRepository menu;
    private final MessAttendanceRepository attendance;
    private final AllotmentRepository allotments;
    private final HostelScope scope;
    private final AuditService audit;

    MessService(MenuItemRepository menu, MessAttendanceRepository attendance, AllotmentRepository allotments,
            HostelScope scope, AuditService audit) {
        this.menu = menu;
        this.attendance = attendance;
        this.allotments = allotments;
        this.scope = scope;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public WeeklyMenu menu(UUID hostelId) {
        TenantContext.require();
        Hostel hostel = scope.require(hostelId);
        return new WeeklyMenu(hostelId, hostel.getName(), menu.findByHostelIdOrderByDayOfWeekAscMealAsc(hostelId)
                .stream().map(m -> new MenuSlot(m.getDayOfWeek(), m.getMeal(), m.getDish(), m.isVeg())).toList(),
                scope.canWarden(hostel));
    }

    /** Replaces the slots given: a blank dish clears the slot, others are added or updated. */
    public WeeklyMenu saveMenu(UUID hostelId, MenuForm form, Actor actor) {
        TenantContext.require();
        Hostel hostel = scope.requireWarden(hostelId);
        Map<String, MenuItem> existing = menu.findByHostelIdOrderByDayOfWeekAscMealAsc(hostelId).stream()
                .collect(Collectors.toMap(m -> m.getDayOfWeek() + ":" + m.getMeal(), m -> m));
        Instant now = Instant.now();
        int changed = 0;
        for (MenuEntry e : form.items()) {
            String key = e.dayOfWeek() + ":" + e.meal();
            String dish = HostelText.clean(e.dish());
            MenuItem item = existing.get(key);
            if (dish == null) {
                if (item != null) {
                    menu.delete(item);
                    changed++;
                }
                continue;
            }
            boolean veg = e.veg() == null || e.veg();
            if (item == null) {
                existing.put(key, menu.save(new MenuItem(hostelId, e.dayOfWeek(), e.meal(), dish, veg, now)));
                changed++;
            } else if (!item.getDish().equals(dish) || item.isVeg() != veg) {
                item.update(dish, veg);
                changed++;
            }
        }
        audit.record(actor, "hostel_menu.updated", "hostel", hostelId, Map.of("hostel", hostel.getName(),
                "slotsChanged", changed));
        menu.flush();
        return menu(hostelId);
    }

    @Transactional(readOnly = true)
    public MessDay day(UUID hostelId, LocalDate date) {
        TenantContext.require();
        scope.require(hostelId);
        LocalDate day = date == null ? HostelText.today() : date;
        return new MessDay(hostelId, day, allotments.countActiveInHostel(hostelId),
                attendance.findByHostelIdAndDayOrderByMealAsc(hostelId, day).stream()
                        .map(m -> new MealCountView(m.getMeal(), m.getHeadCount(), m.getMarkedByName())).toList());
    }

    public MessDay mark(UUID hostelId, LocalDate day, MessAttendanceForm form, Actor actor) {
        TenantContext.require();
        Hostel hostel = scope.requireWarden(hostelId);
        LocalDate today = HostelText.today();
        if (day.isAfter(today) || day.isBefore(today.minusDays(7))) {
            throw ApiException.badRequest("Mess attendance is marked for today or the last seven days.", "date");
        }
        Map<Meal, MessAttendance> existing = attendance.findByHostelIdAndDayOrderByMealAsc(hostelId, day).stream()
                .collect(Collectors.toMap(MessAttendance::getMeal, m -> m));
        Instant now = Instant.now();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("hostel", hostel.getName());
        details.put("date", day.toString());
        List<Meal> seen = new ArrayList<>();
        for (MealCount c : form.counts()) {
            if (seen.contains(c.meal())) {
                throw ApiException.badRequest("Each meal once.", "counts");
            }
            seen.add(c.meal());
            MessAttendance row = existing.get(c.meal());
            if (row == null) {
                attendance.save(new MessAttendance(hostelId, day, c.meal(), c.headCount(), actor.name(), now));
            } else {
                row.update(c.headCount(), actor.name());
            }
            details.put(c.meal().name().toLowerCase(), c.headCount());
        }
        audit.record(actor, "hostel_mess.marked", "hostel", hostelId, details);
        attendance.flush();
        return day(hostelId, day);
    }
}
