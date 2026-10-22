package com.akshara.inventory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.accounts.AccountsViews.VendorView;
import com.akshara.accounts.VendorService;
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.identity.UserAccount;
import com.akshara.identity.UserRepository;
import com.akshara.inventory.InventoryForms.ApproverInput;
import com.akshara.inventory.InventoryForms.ApproversForm;
import com.akshara.inventory.InventoryForms.CategoryForm;
import com.akshara.inventory.InventoryForms.LocationForm;
import com.akshara.inventory.InventoryViews.ApproverView;
import com.akshara.inventory.InventoryViews.CategoryView;
import com.akshara.inventory.InventoryViews.DepartmentView;
import com.akshara.inventory.InventoryViews.LocationView;
import com.akshara.inventory.InventoryViews.Lookups;
import com.akshara.inventory.InventoryViews.PersonView;
import com.akshara.inventory.InventoryViews.VendorRef;
import com.akshara.shared.ApiException;
import com.akshara.shared.Permissions;
import com.akshara.shared.TenantContext;
import com.akshara.staff.StaffPayrollInputs;
import com.akshara.staff.StaffPayrollInputs.PayrollPerson;
import com.akshara.staff.StaffService.DepartmentRef;

/**
 * The inventory's masters: purchase approvers with limits, categories with depreciation rates, locations, and the
 * lookups a form needs (the accounts module's vendors, the staff module's departments and people). Also hands out
 * the document numbers (REQ/2026-27/000001 and so on) and asset tags (AST-000001).
 */
@Service
@Transactional
public class InventorySetupService {

    private final PurchaseApproverRepository approvers;
    private final CategoryRepository categories;
    private final LocationRepository locations;
    private final ItemRepository items;
    private final AssetRepository assets;
    private final StockMovementRepository movements;
    private final UserRepository users;
    private final VendorService vendors;
    private final StaffPayrollInputs staff;
    private final EntityManager entityManager;
    private final AuditService audit;

    InventorySetupService(PurchaseApproverRepository approvers, CategoryRepository categories,
            LocationRepository locations, ItemRepository items, AssetRepository assets,
            StockMovementRepository movements, UserRepository users, VendorService vendors, StaffPayrollInputs staff,
            EntityManager entityManager, AuditService audit) {
        this.approvers = approvers;
        this.categories = categories;
        this.locations = locations;
        this.items = items;
        this.assets = assets;
        this.movements = movements;
        this.users = users;
        this.vendors = vendors;
        this.staff = staff;
        this.entityManager = entityManager;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ approvers

    @Transactional(readOnly = true)
    public List<ApproverView> approvers() {
        TenantContext.require();
        return approvers.findAllOrdered().stream()
                .map(a -> new ApproverView(a.getUserId(), a.getUserName(), a.getLimitPaise())).toList();
    }

    /** Active staff who hold purchase.approve, by name (the candidates for the approvers list). */
    @Transactional(readOnly = true)
    public List<PersonView> approverCandidates() {
        TenantContext.require();
        return users.findAllWithRoles().stream()
                .filter(u -> u.isActive() && u.permissions().contains(Permissions.PURCHASE_APPROVE))
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .map(u -> new PersonView(u.getId(), u.getName(), null)).toList();
    }

    public List<ApproverView> saveApprovers(ApproversForm form, Actor actor) {
        TenantContext.require();
        Map<UUID, UserAccount> people = users.findAllWithRoles().stream()
                .collect(Collectors.toMap(UserAccount::getId, Function.identity()));
        Map<UUID, PurchaseApprover> existing = approvers.findAll().stream()
                .collect(Collectors.toMap(PurchaseApprover::getUserId, Function.identity()));
        Map<UUID, ApproverInput> wanted = new LinkedHashMap<>();
        for (int i = 0; i < form.approvers().size(); i++) {
            ApproverInput in = form.approvers().get(i);
            UserAccount u = people.get(in.userId());
            if (u == null || !u.isActive() || !u.permissions().contains(Permissions.PURCHASE_APPROVE)) {
                throw ApiException.badRequest("This person cannot approve purchases (they need purchase.approve).",
                        "approvers[" + i + "].userId");
            }
            if (wanted.put(in.userId(), in) != null) {
                throw ApiException.badRequest("This person is listed twice.", "approvers[" + i + "].userId");
            }
        }
        for (PurchaseApprover a : existing.values()) {
            if (!wanted.containsKey(a.getUserId())) {
                approvers.delete(a);
            }
        }
        for (ApproverInput in : wanted.values()) {
            UserAccount u = people.get(in.userId());
            PurchaseApprover a = existing.get(in.userId());
            if (a == null) {
                approvers.save(new PurchaseApprover(u.getId(), u.getName(), in.limitPaise()));
            } else {
                a.setUserName(u.getName());
                a.setLimitPaise(in.limitPaise());
            }
        }
        approvers.flush();
        audit.record(actor, "purchase_approvers.updated", "inventory_settings", null,
                Map.of("approvers", wanted.values().stream().map(in -> Map.of("userId", in.userId().toString(),
                        "limitPaise", in.limitPaise() == null ? "none" : in.limitPaise().toString())).toList()));
        return approvers();
    }

    /** The approver's limit, if one was set. Empty for someone without a listed limit. */
    Optional<Long> limitOf(UUID userId) {
        return approvers.findByUserId(userId).map(PurchaseApprover::getLimitPaise);
    }

    /** True when this person is listed with a limit. */
    boolean isLimited(UUID userId) {
        return approvers.findByUserId(userId).map(a -> a.getLimitPaise() != null).orElse(false);
    }

    /**
     * The largest limit among the other approvers (listed or not): null when any other person who can approve has no
     * limit, 0 when there is nobody else.
     */
    Long largestOtherLimit(UUID exceptUserId) {
        Map<UUID, PurchaseApprover> listed = approvers.findAll().stream()
                .collect(Collectors.toMap(PurchaseApprover::getUserId, Function.identity()));
        long largest = 0;
        boolean anyone = false;
        for (UserAccount u : users.findAllWithRoles()) {
            if (u.getId().equals(exceptUserId) || !u.isActive()
                    || !u.permissions().contains(Permissions.PURCHASE_APPROVE)) {
                continue;
            }
            anyone = true;
            PurchaseApprover a = listed.get(u.getId());
            if (a == null || a.getLimitPaise() == null) {
                return null;
            }
            largest = Math.max(largest, a.getLimitPaise());
        }
        return anyone ? largest : 0L;
    }

    // ------------------------------------------------------------------ categories

    @Transactional(readOnly = true)
    public List<CategoryView> categories() {
        TenantContext.require();
        return categories.findAllOrdered().stream().map(this::view).toList();
    }

    public CategoryView createCategory(CategoryForm form, Actor actor) {
        TenantContext.require();
        String name = form.name().strip();
        if (categories.nameTaken(name, new UUID(0, 0))) {
            throw ApiException.conflict("A category with this name exists.", "name");
        }
        Category c = categories.saveAndFlush(new Category(name, form.depreciationRateBp(), form.active()));
        audit.record(actor, "inventory_category.created", "inventory_category", c.getId(),
                Map.of("name", name, "depreciationRateBp", form.depreciationRateBp()));
        return view(c);
    }

    public CategoryView updateCategory(UUID id, CategoryForm form, Actor actor) {
        TenantContext.require();
        Category c = categories.findById(id).orElseThrow(() -> ApiException.notFound("Category"));
        String name = form.name().strip();
        if (categories.nameTaken(name, id)) {
            throw ApiException.conflict("A category with this name exists.", "name");
        }
        c.setName(name);
        c.setDepreciationRateBp(form.depreciationRateBp());
        c.setActive(form.active());
        categories.flush();
        audit.record(actor, "inventory_category.updated", "inventory_category", id,
                Map.of("name", name, "depreciationRateBp", form.depreciationRateBp(), "active", form.active()));
        return view(c);
    }

    public void deleteCategory(UUID id, Actor actor) {
        TenantContext.require();
        Category c = categories.findById(id).orElseThrow(() -> ApiException.notFound("Category"));
        if (items.anyInCategory(id) || assets.anyInCategory(id)) {
            throw ApiException.conflict("This category has items or assets; mark it inactive instead.", "id");
        }
        categories.delete(c);
        categories.flush();
        audit.record(actor, "inventory_category.deleted", "inventory_category", id, Map.of("name", c.getName()));
    }

    Category category(UUID id, String field) {
        return categories.findById(id).filter(Category::isActive)
                .orElseThrow(() -> ApiException.badRequest("Pick a category.", field));
    }

    Map<UUID, Category> categoriesById() {
        return categories.findAll().stream().collect(Collectors.toMap(Category::getId, Function.identity()));
    }

    private CategoryView view(Category c) {
        return new CategoryView(c.getId(), c.getName(), c.getDepreciationRateBp(), c.isActive(),
                items.anyInCategory(c.getId()) || assets.anyInCategory(c.getId()));
    }

    // ------------------------------------------------------------------ locations

    @Transactional(readOnly = true)
    public List<LocationView> locations() {
        TenantContext.require();
        return locations.findAllOrdered().stream().map(this::view).toList();
    }

    public LocationView createLocation(LocationForm form, Actor actor) {
        TenantContext.require();
        String name = form.name().strip();
        if (locations.nameTaken(name, new UUID(0, 0))) {
            throw ApiException.conflict("A location with this name exists.", "name");
        }
        Location l = locations.saveAndFlush(new Location(name, form.kind(), form.active()));
        audit.record(actor, "inventory_location.created", "inventory_location", l.getId(),
                Map.of("name", name, "kind", form.kind().name()));
        return view(l);
    }

    public LocationView updateLocation(UUID id, LocationForm form, Actor actor) {
        TenantContext.require();
        Location l = locations.findById(id).orElseThrow(() -> ApiException.notFound("Location"));
        String name = form.name().strip();
        if (locations.nameTaken(name, id)) {
            throw ApiException.conflict("A location with this name exists.", "name");
        }
        l.setName(name);
        l.setKind(form.kind());
        l.setActive(form.active());
        locations.flush();
        audit.record(actor, "inventory_location.updated", "inventory_location", id,
                Map.of("name", name, "kind", form.kind().name(), "active", form.active()));
        return view(l);
    }

    public void deleteLocation(UUID id, Actor actor) {
        TenantContext.require();
        Location l = locations.findById(id).orElseThrow(() -> ApiException.notFound("Location"));
        if (assets.anyAtLocation(id) || movements.anyAtLocation(id)) {
            throw ApiException.conflict("This location holds assets or stock; mark it inactive instead.", "id");
        }
        locations.delete(l);
        locations.flush();
        audit.record(actor, "inventory_location.deleted", "inventory_location", id, Map.of("name", l.getName()));
    }

    /** The location, or null when none was given; 400 on the field for an unknown or inactive one. */
    Location location(UUID id, String field) {
        if (id == null) {
            return null;
        }
        return locations.findById(id).filter(Location::isActive)
                .orElseThrow(() -> ApiException.badRequest("Pick a location.", field));
    }

    Map<UUID, Location> locationsById() {
        return locations.findAll().stream().collect(Collectors.toMap(Location::getId, Function.identity()));
    }

    static String nameOf(Map<UUID, Location> byId, UUID id) {
        Location l = id == null ? null : byId.get(id);
        return l == null ? null : l.getName();
    }

    private LocationView view(Location l) {
        return new LocationView(l.getId(), l.getName(), l.getKind(), l.isActive(),
                assets.anyAtLocation(l.getId()) || movements.anyAtLocation(l.getId()));
    }

    // ------------------------------------------------------------------ vendors, departments and people

    /** The vendor, or null when none was given; 400 on the field for an unknown or inactive one. */
    VendorView vendor(UUID id, String field) {
        if (id == null) {
            return null;
        }
        VendorView v = vendorsById().get(id);
        if (v == null || !v.active()) {
            throw ApiException.badRequest("Pick an active vendor.", field);
        }
        return v;
    }

    Map<UUID, VendorView> vendorsById() {
        return vendors.list().stream().collect(Collectors.toMap(VendorView::id, Function.identity()));
    }

    static String vendorName(Map<UUID, VendorView> byId, UUID id) {
        VendorView v = id == null ? null : byId.get(id);
        return v == null ? null : v.name();
    }

    /** The department, or null when none was given; 400 on the field for an unknown one. */
    DepartmentRef department(UUID id, String field) {
        if (id == null) {
            return null;
        }
        return staff.departments().stream().filter(d -> d.id().equals(id)).findFirst()
                .orElseThrow(() -> ApiException.badRequest("Pick a department.", field));
    }

    /** A member of staff, or null when none was given; 400 on the field for anyone else. */
    PayrollPerson person(UUID id, String field) {
        if (id == null) {
            return null;
        }
        return staff.person(id).filter(PayrollPerson::active)
                .orElseThrow(() -> ApiException.badRequest("Pick a member of staff.", field));
    }

    @Transactional(readOnly = true)
    public Lookups lookups() {
        TenantContext.require();
        return new Lookups(categories(), locations(),
                vendors.list().stream().map(v -> new VendorRef(v.id(), v.name(), v.active())).toList(),
                staff.departments().stream().map(d -> new DepartmentView(d.id(), d.name())).toList(),
                staff.people().stream().filter(PayrollPerson::active)
                        .map(p -> new PersonView(p.userId(), p.name(), p.departmentName())).toList());
    }

    // ------------------------------------------------------------------ numbers

    /** The next number of a kind in a financial year ("-" for asset tags), from a per-school counter. */
    int nextSeq(String kind, String financialYear) {
        UUID tenantId = TenantContext.require();
        entityManager.createNativeQuery("insert into inventory.number_counter (tenant_id, kind, financial_year, "
                + "last_seq, updated_at) values (?1, ?2, ?3, 0, now()) on conflict (tenant_id, kind, financial_year) "
                + "do nothing")
                .setParameter(1, tenantId).setParameter(2, kind).setParameter(3, financialYear).executeUpdate();
        Object next = entityManager.createNativeQuery("update inventory.number_counter set last_seq = last_seq + 1, "
                + "updated_at = now() where tenant_id = ?1 and kind = ?2 and financial_year = ?3 returning last_seq")
                .setParameter(1, tenantId).setParameter(2, kind).setParameter(3, financialYear).getSingleResult();
        return ((Number) next).intValue();
    }

    /** "PO/2026-27/000001". */
    String nextNumber(String kind, String financialYear) {
        return kind + "/" + financialYear + "/" + String.format("%06d", nextSeq(kind, financialYear));
    }

    /** "AST-000001". */
    String nextAssetTag() {
        return "AST-" + String.format("%06d", nextSeq("AST", "-"));
    }
}
