package com.akshara.library;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/**
 * A book in the school's catalogue: its ISBN (always kept as the ISBN-13, with the ISBN-10 when it has one), title,
 * authors and the rest of its details. The physical books are its {@link Copy copies}. There are no cover images.
 */
@Entity
@Table(schema = "library", name = "title")
class Title extends AssignedIdEntity {

    /** The fields staff fill in, already cleaned and validated. */
    record Details(String isbn13, String isbn10, String title, String subtitle, String authors, String publisher,
            Integer publicationYear, String edition, String language, String subject, String callNumber,
            String shelfLocation) {
    }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    private String isbn13;

    private String isbn10;

    @Column(nullable = false)
    private String title;

    private String subtitle;

    private String authors;

    private String publisher;

    private Integer publicationYear;

    private String edition;

    private String language;

    private String subject;

    private String callNumber;

    private String shelfLocation;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Title() {
    }

    Title(Details details) {
        this.id = Ids.newId();
        apply(details);
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void update(Details details) {
        apply(details);
    }

    private void apply(Details d) {
        this.isbn13 = d.isbn13();
        this.isbn10 = d.isbn10();
        this.title = d.title();
        this.subtitle = d.subtitle();
        this.authors = d.authors();
        this.publisher = d.publisher();
        this.publicationYear = d.publicationYear();
        this.edition = d.edition();
        this.language = d.language();
        this.subject = d.subject();
        this.callNumber = d.callNumber();
        this.shelfLocation = d.shelfLocation();
    }

    Details details() {
        return new Details(isbn13, isbn10, title, subtitle, authors, publisher, publicationYear, edition, language,
                subject, callNumber, shelfLocation);
    }

    @Override
    public UUID getId() {
        return id;
    }

    String getIsbn13() {
        return isbn13;
    }

    String getIsbn10() {
        return isbn10;
    }

    String getTitle() {
        return title;
    }

    String getSubtitle() {
        return subtitle;
    }

    String getAuthors() {
        return authors;
    }

    String getPublisher() {
        return publisher;
    }

    Integer getPublicationYear() {
        return publicationYear;
    }

    String getEdition() {
        return edition;
    }

    String getLanguage() {
        return language;
    }

    String getSubject() {
        return subject;
    }

    String getCallNumber() {
        return callNumber;
    }

    String getShelfLocation() {
        return shelfLocation;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
