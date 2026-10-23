package com.akshara.assist;

import java.time.Instant;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.assist.AssistProvider.AssistRequest;
import com.akshara.assist.AssistProvider.AssistResponse;
import com.akshara.assist.AssistProvider.Templates;
import com.akshara.assist.AssistViews.WritingResult;
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * "Improve this circular": typed text comes back tidied (simulated: spaces trimmed, sentences capitalised and
 * ended, a formal sign-off), counted against the monthly limit. The text itself is never stored or audited.
 */
@Service
@Transactional
public class WritingService {

    private final AssistSettingsService settings;
    private final UsageService usage;
    private final AssistProvider provider;
    private final AuditService audit;

    WritingService(AssistSettingsService settings, UsageService usage, AssistProvider provider, AuditService audit) {
        this.settings = settings;
        this.usage = usage;
        this.provider = provider;
        this.audit = audit;
    }

    public WritingResult improve(String text, Actor actor) {
        TenantContext.require();
        settings.require(AssistFeature.WRITING);
        String input = text == null ? "" : text.strip();
        if (input.isEmpty()) {
            throw ApiException.badRequest("Type the text to improve.", "text");
        }
        usage.checkLimit(1);
        AssistRequest request;
        try {
            request = new AssistRequest(AssistFeature.WRITING, settings.language(), Templates.WRITING_IMPROVE,
                    Map.of("text", input), null);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Take phone and Aadhaar numbers out of the text first; they are never "
                    + "sent to a provider.", "text");
        }
        AssistResponse response = provider.generate(request);
        usage.record(actor, AssistFeature.WRITING, request, response, Instant.now());
        audit.record(actor, "assist_writing.improved", "assist_writing", null,
                Map.of("characters", input.length(), "provider", response.provider()));
        return new WritingResult(response.text(), response.provider(), response.simulated(),
                response.tokenEstimate());
    }
}
