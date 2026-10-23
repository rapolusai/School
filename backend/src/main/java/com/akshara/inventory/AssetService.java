package com.akshara.inventory;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.accounts.AccountsViews.VendorView;
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
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
import com.akshara.inventory.InventoryViews.TransferView;
import com.akshara.inventory.InventoryViews.VerificationItemView;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;
import com.akshara.staff.StaffPayrollInputs.PayrollPerson;

/**
 * The fixed-asset register: tagged assets with a location and a custodian, transfers (kept as history), disposal
 * (requested with a reason, approved or refused by purchase.approve), printable labels, and physical verification
 * rounds where each asset is scanned or ticked found, missing or damaged. Depreciation is computed straight-line
 * from the category's rate and shown, never posted.
 */
@Service
@Transactional
public class AssetService {

    private final AssetRepository assets;
    private final AssetTransferRepository transfers;
    private final VerificationRoundRepository rounds;
    private final VerificationItemRepository roundItems;
    private final InventorySetupService setup;
    private final AuditService audit;

    AssetService(AssetRepository assets, AssetTransferRepository transfers, VerificationRoundRepository rounds,
            VerificationItemRepository roundItems, InventorySetupService setup, AuditService audit) {
        this.assets = assets;
        this.transfers = transfers;
        this.rounds = rounds;
        this.roundItems = roundItems;
        this.setup = setup;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ register

    @Transactional(readOnly = true)
    public List<AssetView> list(AssetStatus status, UUID locationId, UUID custodianId) {
        TenantContext.require();
        Context c = context();
        return assets.findAllOrdered().stream()
                .filter(a -> status == null || a.getStatus() == status)
                .filter(a -> locationId == null || locationId.equals(a.getLocationId()))
                .filter(a -> custodianId == null || custodianId.equals(a.getCustodianId()))
                .map(a -> view(a, c)).toList();
    }

    @Transactional(readOnly = true)
    public AssetDetail detail(UUID id) {
        TenantContext.require();
        return detail(assets.findById(id).orElseThrow(() -> ApiException.notFound("Asset")));
    }

    /** The asset with this tag, for scanning; 404 when unknown. */
    @Transactional(readOnly = true)
    public AssetDetail byTag(String tag) {
        TenantContext.require();
        return detail(assets.findByAssetTag(normalizeTag(tag)).orElseThrow(() -> ApiException.notFound("Asset")));
    }

    @Transactional(readOnly = true)
    public List<AssetLabel> labels(List<UUID> ids) {
        TenantContext.require();
        Context c = context();
        return assets.findLive().stream().filter(a -> ids == null || ids.isEmpty() || ids.contains(a.getId()))
                .map(a -> new AssetLabel(a.getId(), a.getAssetTag(), a.getName(),
                        c.categoryName(a.getCategoryId()), InventorySetupService.nameOf(c.locations,
                                a.getLocationId()))).toList();
    }

    public AssetDetail create(AssetForm form, Actor actor) {
        TenantContext.require();
        Refs r = refs(form);
        Asset a = new Asset(setup.nextAssetTag(), form.name().strip(), r.category.getId(), form.itemId(),
                r.location == null ? null : r.location.getId(), r.custodian == null ? null : r.custodian.userId(),
                r.custodian == null ? null : r.custodian.name(), form.purchaseDate(), form.costPaise(),
                r.vendor == null ? null : r.vendor.id(), InventoryForms.clean(form.serialNo()), form.condition(),
                form.warrantyExpiresOn(), AssetStatus.IN_USE, null, null, null, null,
                InventoryForms.clean(form.notes()), null);
        assets.saveAndFlush(a);
        audit.record(actor, "asset.created", "asset", a.getId(), details(a, r));
        return detail(a);
    }

    public AssetDetail update(UUID id, AssetForm form, Actor actor) {
        TenantContext.require();
        Asset a = assets.findById(id).orElseThrow(() -> ApiException.notFound("Asset"));
        if (a.getStatus() == AssetStatus.DISPOSED) {
            throw ApiException.conflict("This asset is disposed of.", "id");
        }
        Refs r = refs(form);
        a.setName(form.name().strip());
        a.setCategoryId(r.category.getId());
        a.setItemId(form.itemId());
        a.setLocationId(r.location == null ? null : r.location.getId());
        a.setCustodianId(r.custodian == null ? null : r.custodian.userId());
        a.setCustodianName(r.custodian == null ? null : r.custodian.name());
        a.setPurchaseDate(form.purchaseDate());
        a.setCostPaise(form.costPaise());
        a.setVendorId(r.vendor == null ? null : r.vendor.id());
        a.setSerialNo(InventoryForms.clean(form.serialNo()));
        a.setCondition(form.condition());
        a.setWarrantyExpiresOn(form.warrantyExpiresOn());
        a.setNotes(InventoryForms.clean(form.notes()));
        assets.flush();
        audit.record(actor, "asset.updated", "asset", id, details(a, r));
        return detail(a);
    }

    private record Refs(Category category, Location location, PayrollPerson custodian, VendorView vendor) {
    }

    private Refs refs(AssetForm form) {
        StockService.notFuture(form.purchaseDate(), "purchaseDate");
        if (form.warrantyExpiresOn() != null && form.warrantyExpiresOn().isBefore(form.purchaseDate())) {
            throw ApiException.badRequest("The warranty cannot end before the purchase date.", "warrantyExpiresOn");
        }
        return new Refs(setup.category(form.categoryId(), "categoryId"), setup.location(form.locationId(),
                "locationId"), setup.person(form.custodianId(), "custodianId"), setup.vendor(form.vendorId(),
                        "vendorId"));
    }

    private static Map<String, Object> details(Asset a, Refs r) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("assetTag", a.getAssetTag());
        d.put("name", a.getName());
        d.put("category", r.category.getName());
        d.put("costPaise", a.getCostPaise());
        d.put("purchaseDate", a.getPurchaseDate().toString());
        if (r.location != null) {
            d.put("location", r.location.getName());
        }
        if (r.custodian != null) {
            d.put("custodian", r.custodian.name());
        }
        d.put("condition", a.getCondition().name());
        return d;
    }

    // ------------------------------------------------------------------ transfers

    public AssetDetail transfer(UUID id, TransferForm form, Actor actor) {
        TenantContext.require();
        Asset a = assets.findForUpdate(id).orElseThrow(() -> ApiException.notFound("Asset"));
        if (a.getStatus() == AssetStatus.DISPOSED) {
            throw ApiException.conflict("This asset is disposed of.", "id");
        }
        if (form.toLocationId() == null && form.toCustodianId() == null) {
            throw ApiException.badRequest("Give the new location or the new custodian.", "toLocationId");
        }
        StockService.notFuture(form.movedOn(), "movedOn");
        if (form.movedOn().isBefore(a.getPurchaseDate())) {
            throw ApiException.badRequest("The transfer cannot be before the purchase date.", "movedOn");
        }
        Location to = setup.location(form.toLocationId(), "toLocationId");
        PayrollPerson custodian = setup.person(form.toCustodianId(), "toCustodianId");
        boolean sameLocation = to == null || to.getId().equals(a.getLocationId());
        boolean sameCustodian = custodian == null || custodian.userId().equals(a.getCustodianId());
        if (sameLocation && sameCustodian) {
            throw to != null
                    ? ApiException.badRequest("The asset is already at this location.", "toLocationId")
                    : ApiException.badRequest("The asset is already with this custodian.", "toCustodianId");
        }
        Map<UUID, Location> locations = setup.locationsById();
        AssetTransfer t = new AssetTransfer(id, a.getLocationId(), InventorySetupService.nameOf(locations,
                a.getLocationId()), to == null ? a.getLocationId() : to.getId(),
                to == null ? InventorySetupService.nameOf(locations, a.getLocationId()) : to.getName(),
                a.getCustodianId(), a.getCustodianName(),
                custodian == null ? a.getCustodianId() : custodian.userId(),
                custodian == null ? a.getCustodianName() : custodian.name(), InventoryForms.clean(form.reason()),
                form.movedOn(), actor.name());
        if (to != null) {
            a.setLocationId(to.getId());
        }
        if (custodian != null) {
            a.setCustodianId(custodian.userId());
            a.setCustodianName(custodian.name());
        }
        transfers.saveAndFlush(t);
        assets.flush();
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("assetTag", a.getAssetTag());
        d.put("name", a.getName());
        if (to != null) {
            d.put("toLocation", to.getName());
        }
        if (custodian != null) {
            d.put("toCustodian", custodian.name());
        }
        audit.record(actor, "asset.transferred", "asset", id, d);
        return detail(a);
    }

    // ------------------------------------------------------------------ disposal

    public AssetDetail requestDisposal(UUID id, ReasonForm form, Actor actor) {
        TenantContext.require();
        Asset a = assets.findForUpdate(id).orElseThrow(() -> ApiException.notFound("Asset"));
        if (a.getStatus() != AssetStatus.IN_USE) {
            throw ApiException.conflict("Disposal is already requested or done.", "id");
        }
        a.setStatus(AssetStatus.DISPOSAL_REQUESTED);
        a.setDisposalReason(form.reason().strip());
        a.setDisposalRequestedBy(actor.name());
        assets.flush();
        audit.record(actor, "asset.disposal_requested", "asset", id,
                Map.of("assetTag", a.getAssetTag(), "name", a.getName(), "reason", form.reason().strip()));
        return detail(a);
    }

    public AssetDetail approveDisposal(UUID id, NoteForm form, Actor actor) {
        TenantContext.require();
        Asset a = assets.findForUpdate(id).orElseThrow(() -> ApiException.notFound("Asset"));
        if (a.getStatus() != AssetStatus.DISPOSAL_REQUESTED) {
            throw ApiException.conflict("No disposal is waiting for approval.", "id");
        }
        a.setStatus(AssetStatus.DISPOSED);
        a.setDisposedOn(StockService.today());
        a.setDisposedByName(actor.name());
        assets.flush();
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("assetTag", a.getAssetTag());
        d.put("name", a.getName());
        d.put("reason", a.getDisposalReason());
        if (form != null && InventoryForms.clean(form.note()) != null) {
            d.put("note", form.note().strip());
        }
        audit.record(actor, "asset.disposed", "asset", id, d);
        return detail(a);
    }

    public AssetDetail refuseDisposal(UUID id, ReasonForm form, Actor actor) {
        TenantContext.require();
        Asset a = assets.findForUpdate(id).orElseThrow(() -> ApiException.notFound("Asset"));
        if (a.getStatus() != AssetStatus.DISPOSAL_REQUESTED) {
            throw ApiException.conflict("No disposal is waiting for approval.", "id");
        }
        a.setStatus(AssetStatus.IN_USE);
        a.setDisposalReason(null);
        a.setDisposalRequestedBy(null);
        assets.flush();
        audit.record(actor, "asset.disposal_refused", "asset", id,
                Map.of("assetTag", a.getAssetTag(), "name", a.getName(), "reason", form.reason().strip()));
        return detail(a);
    }

    // ------------------------------------------------------------------ verification rounds

    @Transactional(readOnly = true)
    public List<RoundView> rounds() {
        TenantContext.require();
        Map<UUID, Location> locations = setup.locationsById();
        return rounds.findAllNewestFirst().stream().map(r -> view(r, locations, false)).toList();
    }

    @Transactional(readOnly = true)
    public RoundView round(UUID id) {
        TenantContext.require();
        return view(rounds.findById(id).orElseThrow(() -> ApiException.notFound("Verification round")),
                setup.locationsById(), true);
    }

    /** Starts a round over every asset in use (of the location, when one is given). */
    public RoundView startRound(RoundForm form, Actor actor) {
        TenantContext.require();
        Location l = setup.location(form.locationId(), "locationId");
        VerificationRound r = rounds.saveAndFlush(new VerificationRound(form.name().strip(),
                l == null ? null : l.getId(), StockTakeStatus.OPEN, StockService.today(), null, actor.name()));
        List<VerificationItem> items = new ArrayList<>();
        for (Asset a : assets.findLive()) {
            if (l == null || l.getId().equals(a.getLocationId())) {
                items.add(new VerificationItem(r.getId(), a.getId(), null, null, null));
            }
        }
        roundItems.saveAllAndFlush(items);
        audit.record(actor, "verification_round.started", "verification_round", r.getId(),
                Map.of("name", r.getName(), "assets", items.size(), "location", l == null ? "all" : l.getName()));
        return view(r, setup.locationsById(), true);
    }

    /** Records what was found for one asset, by id or scanned tag; 400 for an asset not in the round. */
    public RoundView verify(UUID id, VerifyForm form, Actor actor) {
        TenantContext.require();
        VerificationRound r = rounds.findForUpdate(id).orElseThrow(() -> ApiException.notFound("Verification round"));
        if (r.getStatus() != StockTakeStatus.OPEN) {
            throw ApiException.conflict("This round is closed.", "id");
        }
        Asset a;
        if (form.assetId() != null) {
            a = assets.findById(form.assetId()).orElseThrow(() -> ApiException.badRequest("Unknown asset.",
                    "assetId"));
        } else if (InventoryForms.clean(form.assetTag()) != null) {
            a = assets.findByAssetTag(normalizeTag(form.assetTag()))
                    .orElseThrow(() -> ApiException.badRequest("No asset has this tag.", "assetTag"));
        } else {
            throw ApiException.badRequest("Scan or type the asset tag.", "assetTag");
        }
        VerificationItem item = roundItems.findByRoundIdAndAssetId(id, a.getId())
                .orElseThrow(() -> ApiException.badRequest("This asset is not in the round.",
                        form.assetId() != null ? "assetId" : "assetTag"));
        item.setResult(form.result());
        item.setNote(InventoryForms.clean(form.note()));
        item.setCheckedAt(Instant.now());
        if (form.result() == VerificationResult.FOUND) {
            a.setLastVerifiedOn(StockService.today());
        } else if (form.result() == VerificationResult.DAMAGED) {
            a.setCondition(AssetCondition.DAMAGED);
            a.setLastVerifiedOn(StockService.today());
        }
        roundItems.flush();
        assets.flush();
        audit.record(actor, "asset.verified", "asset", a.getId(), Map.of("assetTag", a.getAssetTag(),
                "name", a.getName(), "result", form.result().name(), "roundId", id.toString()));
        return view(r, setup.locationsById(), true);
    }

    public RoundView closeRound(UUID id, Actor actor) {
        TenantContext.require();
        VerificationRound r = rounds.findForUpdate(id).orElseThrow(() -> ApiException.notFound("Verification round"));
        if (r.getStatus() != StockTakeStatus.OPEN) {
            throw ApiException.conflict("This round is closed.", "id");
        }
        r.setStatus(StockTakeStatus.CLOSED);
        r.setClosedAt(Instant.now());
        rounds.flush();
        RoundView v = view(r, setup.locationsById(), false);
        audit.record(actor, "verification_round.closed", "verification_round", id, Map.of("assets", v.assets(),
                "found", v.found(), "missing", v.missing(), "damaged", v.damaged(), "unchecked",
                v.assets() - v.checked()));
        return v;
    }

    private RoundView view(VerificationRound r, Map<UUID, Location> locations, boolean withItems) {
        List<VerificationItem> items = roundItems.findByRound(r.getId());
        int found = 0;
        int missing = 0;
        int damaged = 0;
        for (VerificationItem i : items) {
            if (i.getResult() == VerificationResult.FOUND) {
                found++;
            } else if (i.getResult() == VerificationResult.MISSING) {
                missing++;
            } else if (i.getResult() == VerificationResult.DAMAGED) {
                damaged++;
            }
        }
        List<VerificationItemView> rows = new ArrayList<>();
        if (withItems) {
            Map<UUID, Asset> byId = assets.findAll().stream()
                    .collect(Collectors.toMap(Asset::getId, Function.identity()));
            for (VerificationItem i : items) {
                Asset a = byId.get(i.getAssetId());
                if (a != null) {
                    rows.add(new VerificationItemView(a.getId(), a.getAssetTag(), a.getName(),
                            InventorySetupService.nameOf(locations, a.getLocationId()), a.getCustodianName(),
                            i.getResult(), i.getNote(), i.getCheckedAt()));
                }
            }
            rows.sort((x, y) -> x.assetTag().compareTo(y.assetTag()));
        }
        return new RoundView(r.getId(), r.getName(), r.getLocationId(), InventorySetupService.nameOf(locations,
                r.getLocationId()), r.getStatus(), r.getStartedOn(), r.getClosedAt(), r.getCreatedByName(),
                items.size(), found + missing + damaged, found, missing, damaged, rows);
    }

    // ------------------------------------------------------------------ views

    record Context(Map<UUID, Category> categories, Map<UUID, Location> locations, Map<UUID, VendorView> vendors,
            LocalDate today) {

        String categoryName(UUID id) {
            Category c = categories.get(id);
            return c == null ? null : c.getName();
        }

        int rate(UUID categoryId) {
            Category c = categories.get(categoryId);
            return c == null ? 0 : c.getDepreciationRateBp();
        }
    }

    Context context() {
        return new Context(setup.categoriesById(), setup.locationsById(), setup.vendorsById(),
                StockService.today());
    }

    static AssetView view(Asset a, Context c) {
        int rate = c.rate(a.getCategoryId());
        LocalDate asOf = a.getStatus() == AssetStatus.DISPOSED && a.getDisposedOn() != null ? a.getDisposedOn()
                : c.today();
        long depreciation = InventoryMath.depreciation(a.getCostPaise(), rate, a.getPurchaseDate(), asOf);
        return new AssetView(a.getId(), a.getAssetTag(), a.getName(), a.getCategoryId(),
                c.categoryName(a.getCategoryId()), rate, a.getItemId(), a.getLocationId(),
                InventorySetupService.nameOf(c.locations, a.getLocationId()), a.getCustodianId(),
                a.getCustodianName(), a.getPurchaseDate(), a.getCostPaise(), a.getVendorId(),
                InventorySetupService.vendorName(c.vendors, a.getVendorId()), a.getSerialNo(), a.getCondition(),
                a.getWarrantyExpiresOn(), a.getWarrantyExpiresOn() != null
                        && a.getWarrantyExpiresOn().isBefore(c.today()), a.getStatus(), a.getDisposalReason(),
                a.getDisposalRequestedBy(), a.getDisposedOn(), a.getDisposedByName(), a.getNotes(),
                a.getLastVerifiedOn(), depreciation, a.getCostPaise() - depreciation);
    }

    private AssetDetail detail(Asset a) {
        return new AssetDetail(view(a, context()), transfers.findByAsset(a.getId()).stream()
                .map(t -> new TransferView(t.getId(), t.getFromLocationName(), t.getToLocationName(),
                        t.getFromCustodianName(), t.getToCustodianName(), t.getReason(), t.getMovedOn(),
                        t.getCreatedByName())).toList());
    }

    static String normalizeTag(String tag) {
        return tag == null ? "" : tag.strip().toUpperCase(Locale.ROOT);
    }
}
