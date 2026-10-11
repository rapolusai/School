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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.akshara.chat.ChatForms.ReportForm;
import com.akshara.chat.ChatForms.StartWithTeacher;
import com.akshara.chat.ChatViews.Contacts;
import com.akshara.chat.ChatViews.ConversationChanges;
import com.akshara.chat.ChatViews.ConversationDetail;
import com.akshara.chat.ChatViews.ConversationRow;
import com.akshara.chat.ChatViews.MessageView;
import com.akshara.chat.ChatViews.ReportView;
import com.akshara.chat.ChatViews.Sent;
import com.akshara.chat.ChatViews.Unread;
import com.akshara.chat.ConversationService.Started;

/**
 * Messages for parents (child.view): conversations with the class teacher and subject teachers of their children.
 * docs/api/phase-2-chat.md.
 */
@RestController
@RequestMapping("/api/me/chat")
public class MyChatController {

    static final String PARENT = "hasAuthority('child.view')";

    private final ConversationService conversations;

    MyChatController(ConversationService conversations) {
        this.conversations = conversations;
    }

    @GetMapping("/conversations")
    @PreAuthorize(PARENT)
    public List<ConversationRow> list() {
        return conversations.list(ChatAccess.parent(), Instant.now());
    }

    @GetMapping("/unread")
    @PreAuthorize(PARENT)
    public Unread unread() {
        return conversations.unread(ChatAccess.parent());
    }

    /** The teachers of each child the parent can write to. */
    @GetMapping("/contacts")
    @PreAuthorize(PARENT)
    public Contacts contacts() {
        return conversations.contacts(ChatAccess.parent(), false, null, Instant.now());
    }

    /** Starts a conversation with a teacher of the child (201), or returns the one that exists (200). */
    @PostMapping("/conversations")
    @PreAuthorize(PARENT)
    public ResponseEntity<ConversationDetail> start(@Valid @RequestBody StartWithTeacher form) {
        Started started = conversations.startWithTeacher(ChatAccess.parent(), form.studentId(),
                form.teacherUserId(), Instant.now());
        return ResponseEntity.status(started.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(started.conversation());
    }

    @GetMapping("/conversations/{id}")
    @PreAuthorize(PARENT)
    public ConversationDetail detail(@PathVariable UUID id, @RequestParam(required = false) Instant before) {
        return conversations.detail(ChatAccess.parent(), id, before, Instant.now());
    }

    @GetMapping("/conversations/{id}/changes")
    @PreAuthorize(PARENT)
    public ConversationChanges changes(@PathVariable UUID id, @RequestParam Instant since) {
        return conversations.changes(ChatAccess.parent(), id, since, Instant.now());
    }

    /** Sends a message (multipart): "body" (text, optional) and "files" (up to three). */
    @PostMapping(value = "/conversations/{id}/messages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(PARENT)
    @ResponseStatus(HttpStatus.CREATED)
    public Sent send(@PathVariable UUID id, @RequestParam(required = false) String body,
            @RequestParam(name = "files", required = false) List<MultipartFile> files) {
        return conversations.send(ChatAccess.parent(), id, body, ChatController.read(files), Instant.now());
    }

    @DeleteMapping("/conversations/{id}/messages/{messageId}")
    @PreAuthorize(PARENT)
    public MessageView delete(@PathVariable UUID id, @PathVariable UUID messageId) {
        return conversations.delete(ChatAccess.parent(), id, messageId, Instant.now());
    }

    @PostMapping("/conversations/{id}/reports")
    @PreAuthorize(PARENT)
    @ResponseStatus(HttpStatus.CREATED)
    public ReportView report(@PathVariable UUID id, @Valid @RequestBody ReportForm form) {
        return conversations.report(ChatAccess.parent(), id, form.reason(), Instant.now());
    }
}
