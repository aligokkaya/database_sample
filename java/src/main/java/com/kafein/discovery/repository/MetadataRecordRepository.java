package com.kafein.discovery.repository;

import com.kafein.discovery.entity.MetadataRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MetadataRecordRepository extends JpaRepository<MetadataRecord, UUID> {

    /**
     * Return all records ordered by creation date descending.
     * Matches the Python: select(MetadataRecord).order_by(MetadataRecord.created_at.desc())
     */
    List<MetadataRecord> findAllByOrderByCreatedAtDesc();

    /**
     * Eagerly fetch a MetadataRecord with its tables (no columns yet — fetch columns separately).
     */
    @Query("SELECT DISTINCT m FROM MetadataRecord m "
         + "LEFT JOIN FETCH m.tables "
         + "WHERE m.id = :id")
    Optional<MetadataRecord> findByIdWithTables(@Param("id") UUID id);
}
