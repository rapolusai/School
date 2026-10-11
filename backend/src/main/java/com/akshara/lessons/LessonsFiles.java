package com.akshara.lessons;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.akshara.files.FileAccessPolicy;
import com.akshara.files.StoredFileInfo;
import com.akshara.shared.CurrentUser;
import com.akshara.shared.Permissions;

/**
 * Who may download study material files: staff with lessons.read, lessons.manage or lessons.approve; students of the
 * material's class (every section, or its own section) once it is published; and those students' parents.
 */
@Component
class LessonsFiles implements FileAccessPolicy {

    private final MaterialRepository materials;
    private final LearnerService learners;

    LessonsFiles(MaterialRepository materials, LearnerService learners) {
        this.materials = materials;
        this.learners = learners;
    }

    @Override
    public String ownerModule() {
        return MaterialService.MODULE;
    }

    @Override
    public boolean canRead(StoredFileInfo file) {
        Optional<UUID> user = CurrentUser.id();
        if (user.isEmpty() || !MaterialService.MATERIAL_FILE.equals(file.ownerType())) {
            return false;
        }
        Material m = materials.findById(file.ownerId()).orElse(null);
        if (m == null) {
            return false;
        }
        Set<String> permissions = LessonsAccess.permissions();
        if (permissions.contains(Permissions.LESSONS_READ) || permissions.contains(Permissions.LESSONS_MANAGE)
                || permissions.contains(Permissions.LESSONS_APPROVE)) {
            return true;
        }
        return learners.canSee(user.get(), m, LessonsSupport.today());
    }
}
