package com.akshara.assist;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
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

import com.akshara.assist.AssistForms.AssignForm;
import com.akshara.assist.AssistForms.DraftRemarksForm;
import com.akshara.assist.AssistForms.ImproveForm;
import com.akshara.assist.AssistForms.NoteForm;
import com.akshara.assist.AssistForms.PaperDraftForm;
import com.akshara.assist.AssistForms.PaperSaveForm;
import com.akshara.assist.AssistForms.RemarkTextForm;
import com.akshara.assist.AssistForms.SettingsForm;
import com.akshara.assist.AssistForms.StatusForm;
import com.akshara.assist.AssistViews.PaperRow;
import com.akshara.assist.AssistViews.PaperView;
import com.akshara.assist.AssistViews.RemarkDraftView;
import com.akshara.assist.AssistViews.RemarkSheet;
import com.akshara.assist.AssistViews.RunResult;
import com.akshara.assist.AssistViews.SettingsView;
import com.akshara.assist.AssistViews.UsageView;
import com.akshara.assist.AssistViews.WatchDetail;
import com.akshara.assist.AssistViews.WatchList;
import com.akshara.assist.AssistViews.WritingResult;

/** Settings, usage, remark drafts, the watch list, draft papers and the writing helper. */
@RestController
@RequestMapping("/api/assist")
@PreAuthorize(AssistAccess.USE)
public class AssistController {

    private final AssistSettingsService settings;
    private final UsageService usage;
    private final RemarkService remarks;
    private final RiskService risk;
    private final PaperService papers;
    private final WritingService writing;

    AssistController(AssistSettingsService settings, UsageService usage, RemarkService remarks, RiskService risk,
            PaperService papers, WritingService writing) {
        this.settings = settings;
        this.usage = usage;
        this.remarks = remarks;
        this.risk = risk;
        this.papers = papers;
        this.writing = writing;
    }

    // ------------------------------------------------------------------ settings and usage

    @GetMapping("/settings")
    public SettingsView settings() {
        return settings.view();
    }

    @PutMapping("/settings")
    @PreAuthorize(AssistAccess.MANAGE)
    public SettingsView saveSettings(@Valid @RequestBody SettingsForm form) {
        return settings.save(form, AssistAccess.actor());
    }

    @GetMapping("/usage")
    @PreAuthorize(AssistAccess.MANAGE)
    public UsageView usage(@RequestParam(required = false) String month) {
        return usage.month(month);
    }

    // ------------------------------------------------------------------ remark drafts

    @GetMapping("/remarks")
    public RemarkSheet remarks(@RequestParam UUID termId, @RequestParam UUID sectionId) {
        return remarks.sheet(termId, sectionId);
    }

    @PostMapping("/remarks/drafts")
    public List<RemarkDraftView> draftRemarks(@Valid @RequestBody DraftRemarksForm form) {
        return remarks.draft(form, AssistAccess.actor());
    }

    @PutMapping("/remarks/{id}")
    public RemarkDraftView editRemark(@PathVariable UUID id, @Valid @RequestBody RemarkTextForm form) {
        return remarks.edit(id, form.text(), AssistAccess.actor());
    }

    @PostMapping("/remarks/{id}/accept")
    public RemarkDraftView acceptRemark(@PathVariable UUID id, @Valid @RequestBody RemarkTextForm form) {
        return remarks.accept(id, form.text(), AssistAccess.actor());
    }

    // ------------------------------------------------------------------ watch list

    @GetMapping("/watch-list")
    public WatchList watchList(@RequestParam(required = false) UUID classId,
            @RequestParam(required = false) WatchStatus status) {
        return risk.list(classId, status);
    }

    @PostMapping("/watch-list/run")
    public RunResult runNow() {
        return risk.runNow(AssistAccess.actor());
    }

    @GetMapping("/watch-list/{id}")
    public WatchDetail watchEntry(@PathVariable UUID id) {
        return risk.detail(id);
    }

    @PutMapping("/watch-list/{id}/assignment")
    public WatchDetail assign(@PathVariable UUID id, @Valid @RequestBody AssignForm form) {
        return risk.assign(id, form.userId(), AssistAccess.actor());
    }

    @PutMapping("/watch-list/{id}/status")
    public WatchDetail status(@PathVariable UUID id, @Valid @RequestBody StatusForm form) {
        return risk.setStatus(id, form.status(), AssistAccess.actor());
    }

    @PostMapping("/watch-list/{id}/notes")
    @ResponseStatus(HttpStatus.CREATED)
    public WatchDetail addNote(@PathVariable UUID id, @Valid @RequestBody NoteForm form) {
        return risk.addNote(id, form.body(), AssistAccess.actor());
    }

    @PostMapping("/watch-list/{id}/inform-parent")
    public WatchDetail informParent(@PathVariable UUID id) {
        return risk.informParent(id, AssistAccess.actor());
    }

    // ------------------------------------------------------------------ draft papers

    @PostMapping("/papers/draft")
    public PaperView draftPaper(@Valid @RequestBody PaperDraftForm form) {
        return papers.draft(form, AssistAccess.actor());
    }

    @GetMapping("/papers")
    public List<PaperRow> papers() {
        return papers.list();
    }

    @PostMapping("/papers")
    @ResponseStatus(HttpStatus.CREATED)
    public PaperView savePaper(@Valid @RequestBody PaperSaveForm form) {
        return papers.save(form, AssistAccess.actor());
    }

    @GetMapping("/papers/{id}")
    public PaperView paper(@PathVariable UUID id) {
        return papers.get(id);
    }

    @PutMapping("/papers/{id}")
    public PaperView updatePaper(@PathVariable UUID id, @Valid @RequestBody PaperSaveForm form) {
        return papers.update(id, form, AssistAccess.actor());
    }

    @DeleteMapping("/papers/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePaper(@PathVariable UUID id) {
        papers.delete(id, AssistAccess.actor());
    }

    // ------------------------------------------------------------------ writing

    @PostMapping("/writing/improve")
    public WritingResult improve(@Valid @RequestBody ImproveForm form) {
        return writing.improve(form.text(), AssistAccess.actor());
    }
}
