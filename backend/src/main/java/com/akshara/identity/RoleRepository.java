package com.akshara.identity;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, UUID> {

    List<Role> findByCodeIn(Collection<String> codes);

    List<Role> findAllByOrderByNameAsc();
}
