package com.akshara.library;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface TitleRepository extends JpaRepository<Title, UUID> {

    Optional<Title> findByIsbn13(String isbn13);

    List<Title> findByIsbn13In(Collection<String> isbn13s);

    @Query("select t from Title t where t.id in ?1")
    List<Title> byIds(Collection<UUID> ids);

    /** Titles without an ISBN whose name (lower case) is one of these, for the import to add copies to. */
    @Query("select t from Title t where t.isbn13 is null and lower(t.title) in ?1")
    List<Title> withoutIsbnNamed(Collection<String> lowerCaseTitles);
}
