package com.akshara.accounts;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.accounts.AccountsForms.ApproverInput;
import com.akshara.accounts.AccountsForms.ApproversForm;
import com.akshara.accounts.AccountsForms.SettingsForm;
import com.akshara.accounts.AccountsViews.ApproverView;
import com.akshara.accounts.AccountsViews.PersonRef;
import com.akshara.accounts.AccountsViews.SettingsView;
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.identity.RoleCatalog;
import com.akshara.identity.UserAccount;
import com.akshara.identity.UserRepository;
import com.akshara.shared.ApiException;
import com.akshara.shared.Permissions;
import com.akshara.shared.TenantContext;

/** The school's accounts settings and the approvers' limits. Changes need accounts.manage and are audited. */
@Service
@Transactional
public class AccountsSettingsService {

    private final AccountsSettingsRepository settings;
    private final ApproverRepository approvers;
    private final UserRepository users;
    private final AuditService audit;

    AccountsSettingsService(AccountsSettingsRepository settings, ApproverRepository approvers, UserRepository users,
            AuditService audit) {
        this.settings = settings;
        this.approvers = approvers;
        this.users = users;
        this.audit = audit;
    }

    /** The settings as they stand (defaults until first saved). */
    AccountsSettings current() {
        return settings.findForSchool().stream().findFirst()
                .orElseGet(() -> new AccountsSettings(BudgetMode.WARN, false, null, 0));
    }

    BudgetMode budgetMode() {
        return current().getBudgetMode();
    }

    @Transactional(readOnly = true)
    public SettingsView view() {
        TenantContext.require();
        Optional<AccountsSettings> saved = settings.findForSchool().stream().findFirst();
        AccountsSettings s = saved.orElseGet(this::current);
        return new SettingsView(s.getBudgetMode(), s.isDonation80gRegistered(), s.getDonation80gNumber(),
                s.getPettyCashFloatPaise(), saved.isPresent(), saved.map(AccountsSettings::getUpdatedAt).orElse(null),
                approverViews(), candidates());
    }

    public SettingsView save(SettingsForm form, Actor actor) {
        TenantContext.require();
        String number = AccountsForms.clean(form.donation80gNumber());
        if (form.donation80gRegistered() && number == null) {
            throw ApiException.badRequest("Give the school's 80G registration number.", "donation80gNumber");
        }
        AccountsSettings s = settings.findForSchool().stream().findFirst().orElse(null);
        if (s == null) {
            s = settings.save(new AccountsSettings(form.budgetMode(), form.donation80gRegistered(), number,
                    form.pettyCashFloatPaise()));
        } else {
            s.update(form.budgetMode(), form.donation80gRegistered(), number, form.pettyCashFloatPaise());
        }
        settings.flush();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("budgetMode", form.budgetMode().name());
        details.put("donation80gRegistered", form.donation80gRegistered());
        details.put("pettyCashFloatPaise", form.pettyCashFloatPaise());
        audit.record(actor, "accounts_settings.updated", "accounts_settings", s.getId(), details);
        return view();
    }

    /** Replaces the list of approvers and limits (400 on the field when a person cannot approve). */
    public SettingsView saveApprovers(ApproversForm form, Actor actor) {
        TenantContext.require();
        Map<UUID, UserAccount> people = users.findAllWithRoles().stream()
                .collect(Collectors.toMap(UserAccount::getId, Function.identity()));
        Map<UUID, Approver> existing = approvers.findAll().stream()
                .collect(Collectors.toMap(Approver::getUserId, Function.identity()));
        Map<UUID, ApproverInput> wanted = new LinkedHashMap<>();
        for (int i = 0; i < form.approvers().size(); i++) {
            ApproverInput in = form.approvers().get(i);
            UserAccount u = people.get(in.userId());
            if (u == null || !u.isActive() || !u.permissions().contains(Permissions.ACCOUNTS_APPROVE)) {
                throw ApiException.badRequest("This person cannot approve expenses (they need accounts.approve).",
                        "approvers[" + i + "].userId");
            }
            if (wanted.put(in.userId(), in) != null) {
                throw ApiException.badRequest("This person is listed twice.", "approvers[" + i + "].userId");
            }
        }
        for (Approver a : existing.values()) {
            if (!wanted.containsKey(a.getUserId())) {
                approvers.delete(a);
            }
        }
        for (ApproverInput in : wanted.values()) {
            UserAccount u = people.get(in.userId());
            Approver a = existing.get(in.userId());
            if (a == null) {
                approvers.save(new Approver(u.getId(), u.getName(), in.limitPaise()));
            } else {
                a.update(u.getName(), in.limitPaise());
            }
        }
        approvers.flush();
        audit.record(actor, "accounts_approvers.updated", "accounts_settings", null,
                Map.of("approvers", wanted.values().stream().map(in -> Map.of("userId", in.userId().toString(),
                        "limitPaise", in.limitPaise() == null ? "none" : in.limitPaise().toString())).toList()));
        return view();
    }

    /** The approver's limit, if one was set. Empty for someone without a listed limit. */
    Optional<Long> limitOf(UUID userId) {
        return approvers.findByUserId(userId).map(Approver::getLimitPaise);
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
        Map<UUID, Approver> listed = approvers.findAll().stream()
                .collect(Collectors.toMap(Approver::getUserId, Function.identity()));
        long largest = 0;
        boolean anyone = false;
        for (UserAccount u : users.findAllWithRoles()) {
            if (u.getId().equals(exceptUserId) || !u.isActive()
                    || !u.permissions().contains(Permissions.ACCOUNTS_APPROVE)) {
                continue;
            }
            anyone = true;
            Approver a = listed.get(u.getId());
            if (a == null || a.getLimitPaise() == null) {
                return null;
            }
            largest = Math.max(largest, a.getLimitPaise());
        }
        return anyone ? largest : 0L;
    }

    private List<ApproverView> approverViews() {
        Map<UUID, UserAccount> people = users.findAllWithRoles().stream()
                .collect(Collectors.toMap(UserAccount::getId, Function.identity()));
        return approvers.findAllOrdered().stream().map(a -> {
            UserAccount u = people.get(a.getUserId());
            boolean can = u != null && u.isActive() && u.permissions().contains(Permissions.ACCOUNTS_APPROVE);
            return new ApproverView(a.getUserId(), u == null ? a.getUserName() : u.getName(), a.getLimitPaise(), can);
        }).toList();
    }

    /** Active staff who hold accounts.approve, by name. */
    private List<PersonRef> candidates() {
        return users.findAllWithAnyRoleExcept(List.of(RoleCatalog.PARENT, RoleCatalog.STUDENT)).stream()
                .filter(u -> u.isActive() && u.permissions().contains(Permissions.ACCOUNTS_APPROVE))
                .map(u -> new PersonRef(u.getId(), u.getName())).toList();
    }
}
