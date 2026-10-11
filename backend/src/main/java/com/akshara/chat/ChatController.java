package com.akshara.chat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.akshara.chat.ChatForms.AwayForm;
import com.akshara.chat.ChatForms.ReportForm;
import com.akshara.chat.ChatForms.SettingsForm;
import com.akshara.chat.ChatForms.StartWithParent;
import com.akshara.chat.ChatViews.Away;
import com.akshara.chat.ChatViews.Contacts;
import com.akshara.chat.ChatViews.ConversationChanges;
import com.akshara.chat.ChatViews.ConversationDetail;
import com.akshara.chat.ChatViews.ConversationRow;
import com.akshara.chat.ChatViews.MessageView;
import com.akshara.chat.ChatViews.ReportView;
import com.akshara.chat.ChatViews.Sent;
import com.akshara.chat.ChatViews.Settings;
import com.akshara.chat.ChatViews.Unread;
import com.akshara.chat.ConversationService.Started;
import com.akshara.files.FileUploads;
import com.akshara.files.IncomingFile;
import com.akshara.shared.ApiException;
import com.akshara.shared.CurrentUser;

/**
 * Messages for staff (chat.use): conversations with parents of the students they teach, their "away" note, and the
 * school's hours for messages (changed with chat.oversee). docs/api/phase-2-chat.md.
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    static final String USE = "hasAuthority('chat.use')";

    private final ConversationService conversations;
    private final ChatSettingsService settings;

    ChatController(ConversationService conversations, ChatSettingsService settings) {
        this.conversations = conversations;
        this.settings = settings;
    }

    @GetMapping("/conversations")
    @PreAuthorize(USE)
    public List<ConversationRow> list() {
        return conversations.list(ChatAccess.staff(), Instant.now());
    }

    @GetMapping("/unread")
    @PreAuthorize(USE)
    public Unread unread() {
        return conversations.unread(ChatAccess.staff());
    }

    @GetMapping("/contacts")
    @PreAuthorize(USE)
    public Contacts contacts(@RequestParam(required = false) UUID sectionId) {
        return conversations.contacts(ChatAccess.staff(), ChatAccess.oversees(), sectionId, Instant.now());
    }

    /** Starts a conversation with a parent about a student (201), or returns the one that exists (200). */
    @PostMapping("/conversations")
    @PreAuthorize(USE)
    public ResponseEntity<ConversationDetail> start(@Valid @RequestBody StartWithParent form) {
        Started started = conversations.startWithParent(ChatAccess.staff(), ChatAccess.oversees(), form.studentId(),
                form.parentUserId(), Instant.now());
        return ResponseEntity.status(started.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(started.conversation());
    }

    @GetMapping("/conversations/{id}")
    @PreAuthorize(USE)
    public ConversationDetail detail(@PathVariable UUID id, @RequestParam(required = false) Instant before) {
        return conversations.detail(ChatAccess.staff(), id, before, Instant.now());
    }

    @GetMapping("/conversations/{id}/changes")
    @PreAuthorize(USE)
    public ConversationChanges changes(@PathVariable UUID id, @RequestParam Instant since) {
        return conversations.changes(ChatAccess.staff(), id, since, Instant.now());
    }

    /** Sends a message (multipart): "body" (text, optional) and "files" (up to three). */
    @PostMapping(value = "/conversations/{id}/messages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(USE)
    @ResponseStatus(HttpStatus.CREATED)
    public Sent send(@PathVariable UUID id, @RequestParam(required = false) String body,
            @RequestParam(name = "files", required = false) List<MultipartFile> files) {
        return conversations.send(ChatAccess.staff(), id, body, read(files), Instant.now());
    }

    @DeleteMapping("/conversations/{id}/messages/{messageId}")
    @PreAuthorize(USE)
    public MessageView delete(@PathVariable UUID id, @PathVariable UUID messageId) {
        return conversations.delete(ChatAccess.staff(), id, messageId, Instant.now());
    }

    @PostMapping("/conversations/{id}/reports")
    @PreAuthorize(USE)
    @ResponseStatus(HttpStatus.CREATED)
    public ReportView report(@PathVariable UUID id, @Valid @RequestBody ReportForm form) {
        return conversations.report(ChatAccess.staff(), id, form.reason(), Instant.now());
    }

    @GetMapping("/away")
    @PreAuthorize(USE)
    public Away away() {
        return settings.away(CurrentUser.requireId(), ChatRules.today());
    }

    @PutMapping("/away")
    @PreAuthorize(USE)
    public Away setAway(@Valid @RequestBody AwayForm form) {
        return settings.setAway(CurrentUser.requireId(), form, ChatAccess.actor(), ChatRules.today(), Instant.now());
    }

    @GetMapping("/settings")
    @PreAuthorize("hasAnyAuthority('chat.use', 'chat.oversee')")
    public Settings settings() {
        return settings.current();
    }

    @PutMapping("/settings")
    @PreAuthorize("hasAuthority('chat.oversee')")
    public Settings updateSettings(@Valid @RequestBody SettingsForm form) {
        return settings.update(form, ChatAccess.actor(), Instant.now());
    }

    /** Reads and checks the uploaded attachments: at most three, each a type and size homework accepts. */
    static List<IncomingFile> read(List<MultipartFile> files) {
        List<MultipartFile> parts = files == null ? List.of()
                : files.stream().filter(f -> f != null && !f.isEmpty()).toList();
        if (parts.size() > ChatRules.MAX_ATTACHMENTS) {
            throw ApiException.badRequest("Attach at most " + ChatRules.MAX_ATTACHMENTS + " files.", "files");
        }
        return parts.stream().map(f -> FileUploads.read(f, "files")).toList();
    }
}
