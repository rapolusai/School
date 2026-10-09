package com.akshara.communication;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.communication.NoticeBoardService.BoardItem;
import com.akshara.communication.NoticeBoardService.BoardPage;
import com.akshara.shared.CurrentUser;

/** The signed-in person's notice board (notices.read): staff, parents and students alike. */
@RestController
@RequestMapping("/api/notices/board")
public class NoticeBoardController {

    static final String READ = "hasAuthority('notices.read')";

    private final NoticeBoardService board;

    NoticeBoardController(NoticeBoardService board) {
        this.board = board;
    }

    /** The circulars addressed to the caller: pinned urgent ones first, then the newest. */
    @GetMapping
    @PreAuthorize(READ)
    public BoardPage board(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "false") boolean unreadOnly) {
        return board.board(CurrentUser.requireId(), page, size, unreadOnly, Instant.now());
    }

    @GetMapping("/{id}")
    @PreAuthorize(READ)
    public BoardItem item(@PathVariable UUID id) {
        return board.item(CurrentUser.requireId(), id, Instant.now());
    }

    @PostMapping("/{id}/read")
    @PreAuthorize(READ)
    public BoardItem read(@PathVariable UUID id) {
        return board.markRead(CurrentUser.requireId(), id, Instant.now());
    }

    @PostMapping("/read-all")
    @PreAuthorize(READ)
    public Map<String, Integer> readAll() {
        return Map.of("marked", board.markAllRead(CurrentUser.requireId(), Instant.now()));
    }
}
