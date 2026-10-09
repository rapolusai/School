package com.akshara.files;

import java.util.UUID;

/**
 * The record a file belongs to, for example {@code ("homework", "submission", <submission id>)}. The owning module
 * decides who may download the file (see {@link FileAccessPolicy}) and removes its files with the record.
 */
public record FileOwner(String module, String type, UUID id) {

    public FileOwner {
        if (module == null || module.isBlank() || type == null || type.isBlank() || id == null) {
            throw new IllegalArgumentException("A file owner needs a module, a type and an id");
        }
    }
}
