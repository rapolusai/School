package com.akshara.frontoffice;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.akshara.files.FileAccessPolicy;
import com.akshara.files.StoredFileInfo;
import com.akshara.shared.CurrentUser;
import com.akshara.shared.Permissions;

/**
 * Who may download the front office's photos: a visitor's photo is for the office (frontoffice.read) and the gate
 * (gate.pass); a pickup person's photo also for the parent who keeps the list (child.view, their own child).
 */
@Component
class FrontOfficeFiles implements FileAccessPolicy {

    private final PickupPersonService pickupPersons;

    FrontOfficeFiles(PickupPersonService pickupPersons) {
        this.pickupPersons = pickupPersons;
    }

    @Override
    public String ownerModule() {
        return VisitorService.MODULE;
    }

    @Override
    public boolean canRead(StoredFileInfo file) {
        Optional<UUID> user = CurrentUser.id();
        if (user.isEmpty()) {
            return false;
        }
        Set<String> permissions = FrontOfficeAuth.permissions();
        boolean staff = permissions.contains(Permissions.FRONTOFFICE_READ)
                || permissions.contains(Permissions.FRONTOFFICE_MANAGE) || permissions.contains(Permissions.GATE_PASS);
        if (VisitorService.VISITOR_PHOTO.equals(file.ownerType())) {
            return staff;
        }
        if (PickupPersonService.PICKUP_PHOTO.equals(file.ownerType())) {
            return staff || (permissions.contains(Permissions.CHILD_VIEW)
                    && pickupPersons.belongsToChildOf(file.ownerId(), user.get()));
        }
        return false;
    }
}
