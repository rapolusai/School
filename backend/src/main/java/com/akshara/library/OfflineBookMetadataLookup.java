package com.akshara.library;

import java.util.Optional;

import org.springframework.stereotype.Component;

/** The lookup in use: offline, so it never finds anything and never touches the network. */
@Component
class OfflineBookMetadataLookup implements BookMetadataLookup {

    @Override
    public Optional<BookMetadata> find(String isbn13) {
        return Optional.empty();
    }
}
