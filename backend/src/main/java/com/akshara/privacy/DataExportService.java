package com.akshara.privacy;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.identity.UserAccount;
import com.akshara.identity.UserRepository;
import com.akshara.privacy.ExportPackager.Packed;
import com.akshara.privacy.PrivacyViews.Download;
import com.akshara.privacy.PrivacyViews.RequestDetail;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * Data exports for access requests: staff make the ZIP, the parent who asked downloads it from their app. A file is
 * kept for 7 days; a newer export replaces it, and {@link ExportCleanup} deletes it once it expires. Every export and
 * every download is audited and shown on the request's timeline.
 */
@Service
@Transactional
public class DataExportService {

    /** How long an export can be downloaded before its file is deleted. */
    public static final Duration KEEP_FOR = Duration.ofDays(7);
    static final String ZIP = "application/zip";

    private final DataRequestService requests;
    private final DataExportRepository exports;
    private final ExportPackager packager;
    private final UserRepository users;
    private final AuditService audit;

    DataExportService(DataRequestService requests, DataExportRepository exports, ExportPackager packager,
            UserRepository users, AuditService audit) {
        this.requests = requests;
        this.exports = exports;
        this.packager = packager;
        this.users = users;
        this.audit = audit;
    }

    /**
     * Makes the export for an open access request (409 for another type or a closed request) and makes it available
     * to the parent for 7 days. An earlier file of the same request is deleted.
     */
    public RequestDetail create(UUID requestId, Actor actor) {
        TenantContext.require();
        DataRequest request = requests.findForUpdate(requestId);
        DataRequestService.requireOpen(request);
        if (request.getType() != RequestType.ACCESS) {
            throw new ApiException(HttpStatus.CONFLICT, "Not an access request",
                    "Only an access request gets a data export.");
        }
        UserAccount requester = request.getRequestedById() == null ? null
                : users.findById(request.getRequestedById()).orElse(null);
        if (requester == null) {
            throw new ApiException(HttpStatus.CONFLICT, "No parent sign-in",
                    "The parent who asked no longer has a sign-in, so nobody could download the export.");
        }
        Instant now = Instant.now();
        Instant expiresAt = now.plus(KEEP_FOR);
        Packed packed = packager.pack(request, requester, now, expiresAt);
        for (DataExport old : exports.readyFor(requestId)) {
            old.discard(ExportStatus.REPLACED, now);
        }
        exports.flush();
        DataExport export = exports.save(new DataExport(requestId, request.getStudentId(), packed.fileName(),
                packed.zip(), packed.sha256(), actor.id(), actor.name() == null ? "Staff" : actor.name(), now,
                expiresAt));
        requests.event(request, RequestEventKind.EXPORT_READY, actor, packed.fileName(), now);
        request.activity(now);
        exports.flush();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("requestId", requestId.toString());
        details.put("fileName", packed.fileName());
        details.put("sizeBytes", packed.zip().length);
        details.put("files", packed.files());
        details.put("expiresAt", expiresAt.toString());
        audit.record(actor, "data_export.created", "data_export", export.getId(), details);
        return requests.detail(request, true);
    }

    /** Staff download the current export of a request (404 when there is none or it has expired). */
    public Download download(UUID requestId, Actor actor) {
        TenantContext.require();
        return hand(requests.findForUpdate(requestId), actor, false);
    }

    /** The parent who asked downloads the export of their own request; anyone else's request is 404. */
    public Download downloadOwn(UUID userId, UUID requestId, Actor actor) {
        TenantContext.require();
        DataRequest request = requests.findForUpdate(requestId);
        if (!userId.equals(request.getRequestedById())) {
            throw ApiException.notFound("Request");
        }
        return hand(request, actor, true);
    }

    private Download hand(DataRequest request, Actor actor, boolean byRequester) {
        Instant now = Instant.now();
        DataExport export = exports.latestReady(request.getId()).filter(e -> e.downloadable(now))
                .orElseThrow(() -> ApiException.notFound("Export"));
        export.downloaded(now);
        if (byRequester) {
            requests.event(request, RequestEventKind.EXPORT_DOWNLOADED, actor, export.getFileName(), now);
            request.touchedBy(now);
        }
        exports.flush();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("requestId", request.getId().toString());
        details.put("fileName", export.getFileName());
        details.put("by", byRequester ? "PARENT" : "STAFF");
        audit.record(actor, "data_export.downloaded", "data_export", export.getId(), details);
        return new Download(export.getFileName(), ZIP, export.getContent());
    }
}
