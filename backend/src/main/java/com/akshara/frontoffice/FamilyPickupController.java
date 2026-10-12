package com.akshara.frontoffice;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.akshara.audit.AuditService.Actor;
import com.akshara.files.FileUploads;
import com.akshara.frontoffice.FrontOfficeForms.PickupPersonForm;
import com.akshara.frontoffice.FrontOfficeForms.PickupRequest;
import com.akshara.frontoffice.FrontOfficeViews.FamilyPickup;
import com.akshara.frontoffice.FrontOfficeViews.GatePassView;
import com.akshara.frontoffice.FrontOfficeViews.PickupPersonView;
import com.akshara.shared.CurrentUser;

/**
 * The family app's early pickup (docs/api/phase-2-frontoffice.md): a parent's requests for their own children, the
 * status of each pass (the OTP reaches them by message, never through the API) and the authorised pickup persons.
 */
@RestController
@RequestMapping("/api/me")
@PreAuthorize(FrontOfficeAuth.PARENT)
public class FamilyPickupController {

    private final GatePassService passes;
    private final PickupPersonService persons;

    FamilyPickupController(GatePassService passes, PickupPersonService persons) {
        this.passes = passes;
        this.persons = persons;
    }

    private static Actor parent() {
        return new Actor(CurrentUser.requireId(), CurrentUser.name().orElse("Parent"));
    }

    /** Every pass of the parent's children, latest first. */
    @GetMapping("/gate-passes")
    public List<GatePassView> mine() {
        return passes.ofChildren(CurrentUser.requireId());
    }

    @GetMapping("/children/{studentId}/pickup")
    public FamilyPickup child(@PathVariable UUID studentId) {
        return persons.family(CurrentUser.requireId(), studentId);
    }

    @PostMapping("/children/{studentId}/gate-passes")
    @ResponseStatus(HttpStatus.CREATED)
    public GatePassView request(@PathVariable UUID studentId, @Valid @RequestBody PickupRequest form) {
        return passes.request(CurrentUser.requireId(), studentId, form, parent());
    }

    @PostMapping("/children/{studentId}/gate-passes/{id}/cancel")
    public GatePassView cancel(@PathVariable UUID studentId, @PathVariable UUID id) {
        return passes.cancel(CurrentUser.requireId(), studentId, id, parent());
    }

    @PostMapping("/children/{studentId}/pickup-persons")
    @ResponseStatus(HttpStatus.CREATED)
    public PickupPersonView addPerson(@PathVariable UUID studentId, @Valid @RequestBody PickupPersonForm form) {
        return persons.add(CurrentUser.requireId(), studentId, form, parent());
    }

    @PutMapping("/children/{studentId}/pickup-persons/{id}")
    public PickupPersonView updatePerson(@PathVariable UUID studentId, @PathVariable UUID id,
            @Valid @RequestBody PickupPersonForm form) {
        return persons.update(CurrentUser.requireId(), studentId, id, form, parent());
    }

    @DeleteMapping("/children/{studentId}/pickup-persons/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removePerson(@PathVariable UUID studentId, @PathVariable UUID id) {
        persons.remove(CurrentUser.requireId(), studentId, id, parent());
    }

    @PostMapping(value = "/children/{studentId}/pickup-persons/{id}/photo",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PickupPersonView photo(@PathVariable UUID studentId, @PathVariable UUID id,
            @RequestPart("file") MultipartFile file) {
        return persons.addPhoto(CurrentUser.requireId(), studentId, id, FileUploads.read(file, "file"), parent());
    }
}
