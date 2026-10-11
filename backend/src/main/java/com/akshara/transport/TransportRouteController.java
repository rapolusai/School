package com.akshara.transport;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

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

import com.akshara.transport.AllocationService.AllocationQuery;
import com.akshara.transport.TransportForms.AllocationForm;
import com.akshara.transport.TransportForms.AllocationUpdateForm;
import com.akshara.transport.TransportForms.BulkAllocationForm;
import com.akshara.transport.TransportForms.EndAllocationForm;
import com.akshara.transport.TransportForms.RouteForm;
import com.akshara.transport.TransportForms.StopsForm;
import com.akshara.transport.TransportViews.AllocationView;
import com.akshara.transport.TransportViews.BulkResult;
import com.akshara.transport.TransportViews.RouteDetail;
import com.akshara.transport.TransportViews.RouteSummary;
import com.akshara.transport.TransportViews.StudentOption;

/** Routes with their stops, and students' allocations to them. Reading needs transport.read; changes need manage. */
@RestController
@RequestMapping("/api/transport")
public class TransportRouteController {

    private final RouteService routes;
    private final AllocationService allocations;

    TransportRouteController(RouteService routes, AllocationService allocations) {
        this.routes = routes;
        this.allocations = allocations;
    }

    @GetMapping("/routes")
    @PreAuthorize(TransportAuth.READ)
    public List<RouteSummary> routes() {
        return routes.routes();
    }

    @GetMapping("/routes/{id}")
    @PreAuthorize(TransportAuth.READ)
    public RouteDetail route(@PathVariable UUID id) {
        return routes.route(id);
    }

    @PostMapping("/routes")
    @PreAuthorize(TransportAuth.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public RouteDetail create(@Valid @RequestBody RouteForm form) {
        return routes.create(form, TransportAuth.actor());
    }

    @PutMapping("/routes/{id}")
    @PreAuthorize(TransportAuth.MANAGE)
    public RouteDetail update(@PathVariable UUID id, @Valid @RequestBody RouteForm form) {
        return routes.update(id, form, TransportAuth.actor());
    }

    /** Replaces the route's stops, in pickup order. */
    @PutMapping("/routes/{id}/stops")
    @PreAuthorize(TransportAuth.MANAGE)
    public RouteDetail saveStops(@PathVariable UUID id, @Valid @RequestBody StopsForm form) {
        return routes.saveStops(id, form, TransportAuth.actor());
    }

    // ------------------------------------------------------------------ allocations

    @GetMapping("/allocations")
    @PreAuthorize(TransportAuth.READ)
    public List<AllocationView> allocations(@RequestParam(required = false) UUID routeId,
            @RequestParam(required = false) UUID stopId, @RequestParam(required = false) UUID classId,
            @RequestParam(required = false) AllocationFilter status, @RequestParam(required = false) String q) {
        return allocations.list(new AllocationQuery(routeId, stopId, classId, status == null ? null : status.name(),
                q));
    }

    /** ACTIVE (the default), ENDED, or ALL. */
    public enum AllocationFilter {
        ACTIVE, ENDED, ALL
    }

    @GetMapping("/allocations/{id}")
    @PreAuthorize(TransportAuth.READ)
    public AllocationView allocation(@PathVariable UUID id) {
        return allocations.allocation(id);
    }

    @PostMapping("/allocations")
    @PreAuthorize(TransportAuth.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public AllocationView allocate(@Valid @RequestBody AllocationForm form) {
        return allocations.allocate(form, TransportAuth.actor());
    }

    @PostMapping("/allocations/bulk")
    @PreAuthorize(TransportAuth.MANAGE)
    public BulkResult allocateClass(@Valid @RequestBody BulkAllocationForm form) {
        return allocations.allocateClass(form, TransportAuth.actor());
    }

    @PutMapping("/allocations/{id}")
    @PreAuthorize(TransportAuth.MANAGE)
    public AllocationView update(@PathVariable UUID id, @Valid @RequestBody AllocationUpdateForm form) {
        return allocations.update(id, form, TransportAuth.actor());
    }

    @PostMapping("/allocations/{id}/end")
    @PreAuthorize(TransportAuth.MANAGE)
    public AllocationView end(@PathVariable UUID id, @Valid @RequestBody EndAllocationForm form) {
        return allocations.end(id, form, TransportAuth.actor());
    }

    /** Active students for the allocation picker, with their current bus. */
    @GetMapping("/students")
    @PreAuthorize(TransportAuth.MANAGE)
    public List<StudentOption> students(@RequestParam(required = false) String q,
            @RequestParam(required = false) UUID classId, @RequestParam(required = false) UUID sectionId) {
        return allocations.studentOptions(q, classId, sectionId);
    }
}
