package com.akshara.library;

import java.util.Optional;

/**
 * Finds a book's details from its ISBN outside the school's own catalogue. The only implementation,
 * {@link OfflineBookMetadataLookup}, finds nothing: the product makes no calls to outside services. A lookup against
 * Open Library (openlibrary.org, free and without a key) could be added as another implementation once the owner
 * approves sending ISBNs to it; nothing else would need to change.
 */
public interface BookMetadataLookup {

    /** What an outside catalogue knows about a book. Any field may be null. */
    record BookMetadata(String title, String subtitle, String authors, String publisher, Integer publicationYear,
            String language) {
    }

    /** Looks up a valid ISBN-13. Empty when nothing is known (always, offline). */
    Optional<BookMetadata> find(String isbn13);
}
