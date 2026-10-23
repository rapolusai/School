package com.akshara.assist;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.assist.AssistForms.SettingsForm;
import com.akshara.assist.AssistForms.WeightsForm;
import com.akshara.assist.AssistSettings.Weights;
import com.akshara.assist.AssistViews.SettingsView;
import com.akshara.assist.AssistViews.WeightsView;
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * The school's assistance settings: off until the School Admin switches them on (the demo school starts on), which
 * features are on, the monthly draft limit, the language of drafts and the early warning weights. Every feature
 * checks {@link #require(AssistFeature)} first and answers 409 with a clear message when it is off.
 */
@Service
@Transactional
public class AssistSettingsService {

    public static final String OFF_TYPE = "urn:akshara:assist:off";
    public static final String FEATURE_OFF_TYPE = "urn:akshara:assist:feature-off";

    private final AssistSettingsRepository settings;
    private final UsageService usage;
    private final AssistProvider provider;
    private final AuditService audit;

    AssistSettingsService(AssistSettingsRepository settings, UsageService usage, AssistProvider provider,
            AuditService audit) {
        this.settings = settings;
        this.usage = usage;
        this.provider = provider;
        this.audit = audit;
    }

    /** The settings as they stand (defaults, with assistance off, until first saved). */
    AssistSettings current() {
        return settings.findForSchool().stream().findFirst().orElseGet(() -> new AssistSettings(false));
    }

    /** The settings row, created with the defaults when the school has none yet (for recording a run). */
    AssistSettings currentOrCreate() {
        return settings.findForSchool().stream().findFirst()
                .orElseGet(() -> settings.saveAndFlush(new AssistSettings(false)));
    }

    /** The language drafts are written in ("en" or "hi"). */
    String language() {
        return current().getLanguage();
    }

    /** 409 unless assistance and the feature are on for the school. */
    void require(AssistFeature feature) {
        AssistSettings s = current();
        if (!s.isEnabled()) {
            throw new ApiException(HttpStatus.CONFLICT, "Assistance is off",
                    "AI assistance is switched off for this school. A School Admin can switch it on under "
                            + "Assistance settings.", Map.of(), OFF_TYPE);
        }
        boolean on = switch (feature) {
            case REMARK -> s.isRemarksEnabled();
            case EARLY_WARNING -> s.isEarlyWarningEnabled();
            case PAPER -> s.isPapersEnabled();
            case WRITING -> s.isWritingEnabled();
        };
        if (!on) {
            throw new ApiException(HttpStatus.CONFLICT, "Feature is off",
                    "This assistance feature is switched off for this school. A School Admin can switch it on under "
                            + "Assistance settings.", Map.of(), FEATURE_OFF_TYPE);
        }
    }

    @Transactional(readOnly = true)
    public SettingsView view() {
        TenantContext.require();
        Optional<AssistSettings> saved = settings.findForSchool().stream().findFirst();
        AssistSettings s = saved.orElseGet(this::current);
        return view(s, saved.isPresent());
    }

    SettingsView view(AssistSettings s, boolean saved) {
        Weights w = s.weights();
        return new SettingsView(s.isEnabled(), s.isRemarksEnabled(), s.isEarlyWarningEnabled(),
                s.isPapersEnabled(), s.isWritingEnabled(), s.getMonthlyLimit(), s.getLanguage(),
                new WeightsView(w.attendance30(), w.attendance90(), w.marksTrend(), w.feeDues(), w.homework(),
                        w.threshold()),
                saved, saved ? s.getUpdatedAt() : null, provider.name(),
                SimulatedAssistProvider.NAME.equals(provider.name()), usage.usedThisMonth(),
                s.getLastRunAt(), s.getLastRunStudents(), s.getLastRunListed());
    }

    public SettingsView save(SettingsForm form, Actor actor) {
        TenantContext.require();
        WeightsForm wf = form.weights();
        Weights weights = new Weights(wf.attendance30(), wf.attendance90(), wf.marksTrend(), wf.feeDues(),
                wf.homework(), wf.threshold());
        if (weights.attendance30() + weights.attendance90() + weights.marksTrend() + weights.feeDues()
                + weights.homework() == 0) {
            throw ApiException.badRequest("Give at least one signal a weight above zero.", "weights.attendance30");
        }
        AssistSettings s = settings.findForSchool().stream().findFirst().orElse(null);
        if (s == null) {
            s = new AssistSettings(form.enabled());
            s.update(form.enabled(), form.remarksEnabled(), form.earlyWarningEnabled(), form.papersEnabled(),
                    form.writingEnabled(), form.monthlyLimit(), form.language(), weights);
            s = settings.save(s);
        } else {
            s.update(form.enabled(), form.remarksEnabled(), form.earlyWarningEnabled(), form.papersEnabled(),
                    form.writingEnabled(), form.monthlyLimit(), form.language(), weights);
        }
        settings.flush();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("enabled", form.enabled());
        details.put("remarks", form.remarksEnabled());
        details.put("earlyWarning", form.earlyWarningEnabled());
        details.put("papers", form.papersEnabled());
        details.put("writing", form.writingEnabled());
        details.put("monthlyLimit", form.monthlyLimit());
        details.put("language", form.language());
        details.put("threshold", weights.threshold());
        audit.record(actor, "assist_settings.updated", "assist_settings", s.getId(), details);
        return view(s, true);
    }

    /** Remembers the last early warning run on the settings row. */
    void recordRun(Instant at, int students, int listed) {
        AssistSettings s = currentOrCreate();
        s.recordRun(at, students, listed);
        settings.flush();
    }
}
