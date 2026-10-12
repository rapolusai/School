package com.akshara.certificates;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.akshara.files.FileAccessPolicy;
import com.akshara.files.StoredFileInfo;
import com.akshara.shared.CurrentUser;
import com.akshara.shared.Permissions;

/**
 * Who may download an ID card photo: anyone who reads certificates, the member of staff it shows, the student it
 * shows and that student's parents.
 */
@Component
class CertificateFiles implements FileAccessPolicy {

    private final CertificateSubjects subjects;

    CertificateFiles(CertificateSubjects subjects) {
        this.subjects = subjects;
    }

    @Override
    public String ownerModule() {
        return IdCardService.MODULE;
    }

    @Override
    public boolean canRead(StoredFileInfo file) {
        Optional<UUID> user = CurrentUser.id();
        if (user.isEmpty()) {
            return false;
        }
        if (CertificateAuth.permissions().contains(Permissions.CERTIFICATES_READ)) {
            return true;
        }
        return switch (file.ownerType()) {
            case IdCardService.PHOTO_STAFF -> user.get().equals(file.ownerId());
            case IdCardService.PHOTO_STUDENT -> subjects.childIdsOf(user.get()).contains(file.ownerId())
                    || subjects.studentOf(user.get()).filter(s -> s.id().equals(file.ownerId())).isPresent();
            default -> false;
        };
    }
}
