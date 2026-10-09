package com.akshara.files;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * Downloads a stored file. Any signed-in person of the school may ask; the module that owns the file decides
 * ({@link FileAccessPolicy}). A file of another school, of an owner without a policy, or one the caller may not see
 * is 404.
 */
@RestController
public class FileController {

    private final FileStore files;
    private final List<FileAccessPolicy> policies;

    FileController(FileStore files, List<FileAccessPolicy> policies) {
        this.files = files;
        this.policies = List.copyOf(policies);
    }

    @GetMapping("/api/files/{id}")
    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> download(@PathVariable UUID id) {
        if (TenantContext.current().isEmpty()) {
            throw ApiException.notFound("File");
        }
        StoredFileInfo info = files.info(id).orElseThrow(() -> ApiException.notFound("File"));
        boolean allowed = policies.stream()
                .filter(p -> p.ownerModule().equals(info.ownerModule()))
                .anyMatch(p -> p.canRead(info));
        if (!allowed) {
            throw ApiException.notFound("File");
        }
        return FileDownloads.attachment(files.content(id).orElseThrow(() -> ApiException.notFound("File")));
    }
}
