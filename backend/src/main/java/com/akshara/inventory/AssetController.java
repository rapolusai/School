package com.akshara.inventory;

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

import com.akshara.inventory.InventoryForms.AssetForm;
import com.akshara.inventory.InventoryForms.NoteForm;
import com.akshara.inventory.InventoryForms.ReasonForm;
import com.akshara.inventory.InventoryForms.RoundForm;
import com.akshara.inventory.InventoryForms.TransferForm;
import com.akshara.inventory.InventoryForms.VerifyForm;
import com.akshara.inventory.InventoryViews.AssetDetail;
import com.akshara.inventory.InventoryViews.AssetLabel;
import com.akshara.inventory.InventoryViews.AssetView;
import com.akshara.inventory.InventoryViews.RoundView;

/** The asset register: assets, transfers, disposal, labels and verification rounds. */
@RestController
@RequestMapping("/api/inventory")
@PreAuthorize(InventoryAccess.READ_OR_APPROVE)
public class AssetController {

    private final AssetService assets;

    AssetController(AssetService assets) {
        this.assets = assets;
    }

    @GetMapping("/assets")
    public List<AssetView> list(@RequestParam(required = false) AssetStatus status,
            @RequestParam(required = false) UUID locationId, @RequestParam(required = false) UUID custodianId) {
        return assets.list(status, locationId, custodianId);
    }

    @GetMapping("/assets/labels")
    public List<AssetLabel> labels(@RequestParam(required = false) List<UUID> ids) {
        return assets.labels(ids);
    }

    @GetMapping("/assets/by-tag/{tag}")
    public AssetDetail byTag(@PathVariable String tag) {
        return assets.byTag(tag);
    }

    @GetMapping("/assets/{id}")
    public AssetDetail get(@PathVariable UUID id) {
        return assets.detail(id);
    }

    @PostMapping("/assets")
    @PreAuthorize(InventoryAccess.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public AssetDetail create(@Valid @RequestBody AssetForm form) {
        return assets.create(form, InventoryAccess.actor());
    }

    @PutMapping("/assets/{id}")
    @PreAuthorize(InventoryAccess.MANAGE)
    public AssetDetail update(@PathVariable UUID id, @Valid @RequestBody AssetForm form) {
        return assets.update(id, form, InventoryAccess.actor());
    }

    @PostMapping("/assets/{id}/transfer")
    @PreAuthorize(InventoryAccess.MANAGE)
    public AssetDetail transfer(@PathVariable UUID id, @Valid @RequestBody TransferForm form) {
        return assets.transfer(id, form, InventoryAccess.actor());
    }

    @PostMapping("/assets/{id}/disposal")
    @PreAuthorize(InventoryAccess.MANAGE)
    public AssetDetail requestDisposal(@PathVariable UUID id, @Valid @RequestBody ReasonForm form) {
        return assets.requestDisposal(id, form, InventoryAccess.actor());
    }

    @PostMapping("/assets/{id}/disposal/approve")
    @PreAuthorize(InventoryAccess.APPROVE)
    public AssetDetail approveDisposal(@PathVariable UUID id, @Valid @RequestBody(required = false) NoteForm form) {
        return assets.approveDisposal(id, form, InventoryAccess.actor());
    }

    @PostMapping("/assets/{id}/disposal/refuse")
    @PreAuthorize(InventoryAccess.APPROVE)
    public AssetDetail refuseDisposal(@PathVariable UUID id, @Valid @RequestBody ReasonForm form) {
        return assets.refuseDisposal(id, form, InventoryAccess.actor());
    }

    @GetMapping("/verification-rounds")
    public List<RoundView> rounds() {
        return assets.rounds();
    }

    @GetMapping("/verification-rounds/{id}")
    public RoundView round(@PathVariable UUID id) {
        return assets.round(id);
    }

    @PostMapping("/verification-rounds")
    @PreAuthorize(InventoryAccess.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public RoundView startRound(@Valid @RequestBody RoundForm form) {
        return assets.startRound(form, InventoryAccess.actor());
    }

    @PostMapping("/verification-rounds/{id}/verify")
    @PreAuthorize(InventoryAccess.MANAGE)
    public RoundView verify(@PathVariable UUID id, @Valid @RequestBody VerifyForm form) {
        return assets.verify(id, form, InventoryAccess.actor());
    }

    @PostMapping("/verification-rounds/{id}/close")
    @PreAuthorize(InventoryAccess.MANAGE)
    public RoundView closeRound(@PathVariable UUID id) {
        return assets.closeRound(id, InventoryAccess.actor());
    }
}
