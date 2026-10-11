package com.akshara.chat;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.chat.ChatForms.CloseForm;
import com.akshara.chat.ChatForms.ReviewForm;
import com.akshara.chat.ChatViews.OversightDetail;
import com.akshara.chat.ChatViews.OversightPage;
import com.akshara.chat.ChatViews.ReportView;
import com.akshara.chat.OversightService.Filter;
import com.akshara.shared.ApiException;

/**
 * The Principal's oversight of messages (chat.oversee). Reading a conversation is written to the audit log every time.
 * docs/api/phase-2-chat.md.
 */
@RestController
@RequestMapping("/api/chat/oversight")
public class ChatOversightController {

    static final String OVERSEE = "hasAuthority('chat.oversee')";

    private final OversightService oversight;

    ChatOversightController(OversightService oversight) {
        this.oversight = oversight;
    }

    /** Every conversation without its content. {@code filter}: all (default), reported or closed. */
    @GetMapping("/conversations")
    @PreAuthorize(OVERSEE)
    public OversightPage list(@RequestParam(defaultValue = "all") String filter,
            @RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Filter f;
        try {
            f = Filter.valueOf(filter.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Choose all, reported or closed.", "filter");
        }
        return oversight.list(f, q, page, size);
    }

    @GetMapping("/conversations/{id}")
    @PreAuthorize(OVERSEE)
    public OversightDetail read(@PathVariable UUID id) {
        return oversight.read(id, ChatAccess.actor());
    }

    @PostMapping("/conversations/{id}/close")
    @PreAuthorize(OVERSEE)
    public OversightDetail close(@PathVariable UUID id, @Valid @RequestBody CloseForm form) {
        return oversight.close(id, form.reason(), ChatAccess.actor(), Instant.now());
    }

    @PostMapping("/conversations/{id}/reopen")
    @PreAuthorize(OVERSEE)
    public OversightDetail reopen(@PathVariable UUID id) {
        return oversight.reopen(id, ChatAccess.actor());
    }

    /** Reports: {@code status} open (default) or all. */
    @GetMapping("/reports")
    @PreAuthorize(OVERSEE)
    public List<ReportView> reports(@RequestParam(defaultValue = "open") String status) {
        if (!"open".equals(status) && !"all".equals(status)) {
            throw ApiException.badRequest("Choose open or all.", "status");
        }
        return oversight.reports("open".equals(status));
    }

    @PostMapping("/reports/{id}/review")
    @PreAuthorize(OVERSEE)
    public ReportView review(@PathVariable UUID id, @Valid @RequestBody ReviewForm form) {
        return oversight.review(id, form.note(), ChatAccess.actor(), Instant.now());
    }
}
