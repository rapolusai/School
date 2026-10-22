package com.akshara.frontoffice;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.frontoffice.FrontOfficeForms.CourierForm;
import com.akshara.frontoffice.FrontOfficeViews.CourierView;

/** The postal and courier register. */
@RestController
@RequestMapping("/api/frontoffice/courier")
public class CourierController {

    private final CourierService courier;

    CourierController(CourierService courier) {
        this.courier = courier;
    }

    @GetMapping
    @PreAuthorize(FrontOfficeAuth.READ)
    public List<CourierView> list(@RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to) {
        return courier.list(from, to);
    }

    @PostMapping
    @PreAuthorize(FrontOfficeAuth.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public CourierView add(@Valid @RequestBody CourierForm form) {
        return courier.add(form, FrontOfficeAuth.actor());
    }

    @PutMapping("/{id}")
    @PreAuthorize(FrontOfficeAuth.MANAGE)
    public CourierView update(@PathVariable UUID id, @Valid @RequestBody CourierForm form) {
        return courier.update(id, form, FrontOfficeAuth.actor());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(FrontOfficeAuth.MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable UUID id) {
        courier.remove(id, FrontOfficeAuth.actor());
    }
}
