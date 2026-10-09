package com.akshara.notifications;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.notifications.MessageLogService.MessageDetail;
import com.akshara.notifications.MessageLogService.MessagePage;
import com.akshara.notifications.MessageLogService.MessageQuery;

/** The school's message log (messages.read). Phone numbers and emails are masked. */
@RestController
@RequestMapping("/api/messages")
public class MessageController {

    static final String READ = "hasAuthority('messages.read')";

    private final MessageLogService log;

    public MessageController(MessageLogService log) {
        this.log = log;
    }

    @GetMapping
    @PreAuthorize(READ)
    public MessagePage list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Channel channel,
            @RequestParam(required = false) MessageStatus status,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) UUID relatedId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100_000) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(MessageLogService.MAX_PAGE_SIZE) int size) {
        return log.list(new MessageQuery(from, to, channel, status, q, relatedId, page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize(READ)
    public MessageDetail get(@PathVariable UUID id) {
        return log.detail(id);
    }
}
