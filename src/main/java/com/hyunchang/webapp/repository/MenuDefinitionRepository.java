package com.hyunchang.webapp.repository;

import com.hyunchang.webapp.entity.MenuDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MenuDefinitionRepository extends JpaRepository<MenuDefinition, Long> {

    Optional<MenuDefinition> findByPath(String path);

    boolean existsByPath(String path);

    List<MenuDefinition> findAllByOrderBySortOrderAscIdAsc();
}
