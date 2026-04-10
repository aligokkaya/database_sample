package com.kafein.discovery.repository;

import com.kafein.discovery.entity.DbConnection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface DbConnectionRepository extends JpaRepository<DbConnection, UUID> {

    /**
     * Find the connection record belonging to a given metadata record.
     * Matches: select(DbConnection).where(DbConnection.metadata_id == metadata_id)
     */
    Optional<DbConnection> findByMetadataId(UUID metadataId);
}
