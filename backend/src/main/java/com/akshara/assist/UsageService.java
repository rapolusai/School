package com.akshara.assist;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.assist.AssistProvider.AssistRequest;
import com.akshara.assist.AssistProvider.AssistResponse;
import com.akshara.assist.AssistViews.FeatureCount;
import com.akshara.assist.AssistViews.UsageRow;
import com.akshara.assist.AssistViews.UsageView;
import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * The usage log and the monthly limit: every provider call is stored with who, the feature, the provider, a token
 * estimate and a SHA-256 of the request (never its text). Months are calendar months in India.
 */
@Service
@Transactional
public class UsageService {

    public static final String LIMIT_TYPE = "urn:akshara:assist:limit-reached";
    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    static final int MAX_ROWS = 500;

    private final UsageRepository usage;
    private final AssistSettingsRepository settings;

    UsageService(UsageRepository usage, AssistSettingsRepository settings) {
        this.usage = usage;
        this.settings = settings;
    }

    /** Calls recorded this month (India). */
    @Transactional(readOnly = true)
    public long usedThisMonth() {
        TenantContext.require();
        YearMonth month = YearMonth.now(INDIA);
        return usage.countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(start(month), start(month.plusMonths(1)));
    }

    /**
     * 409 when this month's calls have reached the school's limit, before a provider is called. {@code wanted} is
     * how many calls are about to be made (a batch of remarks is one per student).
     */
    void checkLimit(int wanted) {
        int limit = settings.findForSchool().stream().findFirst().map(AssistSettings::getMonthlyLimit).orElse(500);
        long used = usedThisMonth();
        if (used + wanted > limit) {
            throw new ApiException(HttpStatus.CONFLICT, "Monthly limit reached",
                    "This month's limit of " + limit + " drafts has been reached (" + used + " used). A School Admin "
                            + "can raise it under Assistance settings.", Map.of(), LIMIT_TYPE);
        }
    }

    /** Records one provider call in the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    void record(Actor actor, AssistFeature feature, AssistRequest request, AssistResponse response, Instant at) {
        usage.save(new UsageEntry(actor.id(), actor.name(), feature, response.provider(), response.tokenEstimate(),
                hash(request), at));
    }

    /** A SHA-256 over the request's template, language, fields and lines, as 64 hex characters. */
    static String hash(AssistRequest request) {
        StringBuilder b = new StringBuilder(request.template()).append('\n').append(request.language()).append('\n');
        request.fields().forEach((k, v) -> b.append(k).append('=').append(v).append('\n'));
        request.lines().forEach(line -> b.append(line).append('\n'));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(b.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A month's usage ({@code month} "YYYY-MM", this month when null). */
    @Transactional(readOnly = true)
    public UsageView month(String month) {
        TenantContext.require();
        YearMonth ym;
        try {
            ym = month == null || month.isBlank() ? YearMonth.now(INDIA) : YearMonth.parse(month);
        } catch (java.time.format.DateTimeParseException e) {
            throw ApiException.badRequest("Give the month as YYYY-MM.", "month");
        }
        Instant from = start(ym);
        Instant to = start(ym.plusMonths(1));
        int limit = settings.findForSchool().stream().findFirst().map(AssistSettings::getMonthlyLimit).orElse(500);
        List<UsageEntry> rows = usage.findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(from,
                to, Limit.of(MAX_ROWS));
        long used = usage.countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(from, to);
        Map<AssistFeature, long[]> counts = new EnumMap<>(AssistFeature.class);
        long tokens = 0;
        for (UsageEntry e : rows) {
            long[] c = counts.computeIfAbsent(e.getFeature(), k -> new long[2]);
            c[0]++;
            c[1] += e.getTokenEstimate();
            tokens += e.getTokenEstimate();
        }
        List<FeatureCount> byFeature = new ArrayList<>();
        for (AssistFeature f : AssistFeature.values()) {
            long[] c = counts.get(f);
            if (c != null) {
                byFeature.add(new FeatureCount(f, c[0], c[1]));
            }
        }
        return new UsageView(ym.toString(), limit, used, tokens, byFeature, rows.stream()
                .map(e -> new UsageRow(e.getId(), e.getCreatedAt(), e.getUserId(), e.getUserName(), e.getFeature(),
                        e.getProvider(), e.getTokenEstimate(), e.getRequestHash()))
                .toList());
    }

    static Instant start(YearMonth month) {
        return LocalDate.of(month.getYear(), month.getMonth(), 1).atStartOfDay(INDIA).toInstant();
    }
}
