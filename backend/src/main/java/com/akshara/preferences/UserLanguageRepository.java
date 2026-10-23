package com.akshara.preferences;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface UserLanguageRepository extends JpaRepository<UserLanguage, UUID> {

    Optional<UserLanguage> findByUserAccountId(UUID userAccountId);

    List<UserLanguage> findByUserAccountIdIn(Collection<UUID> userAccountIds);
}
