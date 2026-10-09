package com.akshara.identity;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends JpaRepository<UserAccount, UUID> {

    @Query("select distinct u from UserAccount u left join fetch u.roles where lower(u.email) = lower(?1)")
    Optional<UserAccount> findByEmailWithRoles(String email);

    @Query("select distinct u from UserAccount u left join fetch u.roles where u.id = ?1")
    Optional<UserAccount> findByIdWithRoles(UUID id);

    @Query("select distinct u from UserAccount u left join fetch u.roles order by u.name")
    List<UserAccount> findAllWithRoles();

    @Query("select count(u) > 0 from UserAccount u where lower(u.email) = lower(?1)")
    boolean existsByEmail(String email);

    /** People holding at least one role whose code is not in {@code excludedCodes} (for example the staff), by name. */
    @Query("select distinct u from UserAccount u left join fetch u.roles where u.id in "
            + "(select u2.id from UserAccount u2 join u2.roles r2 where r2.code not in ?1) order by u.name")
    List<UserAccount> findAllWithAnyRoleExcept(Collection<String> excludedCodes);
}
