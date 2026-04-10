package com.kafein.discovery.repository;

import com.kafein.discovery.entity.TableInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TableInfoRepository extends JpaRepository<TableInfo, UUID> {

    List<TableInfo> findByMetadataId(UUID metadataId);

    @Query("SELECT DISTINCT t FROM TableInfo t LEFT JOIN FETCH t.columns WHERE t.metadataId = :metadataId")
    List<TableInfo> findByMetadataIdWithColumns(@org.springframework.data.repository.query.Param("metadataId") UUID metadataId);

    /**
     * Count tables grouped by metadata_id.
     * Returns Object[] rows: [metadataId, count]
     * Matches: select(TableInfo.metadata_id, func.count(TableInfo.id)).group_by(...)
     */
    @Query("SELECT t.metadataId, COUNT(t.id) FROM TableInfo t GROUP BY t.metadataId")
    List<Object[]> countByMetadataIdGrouped();
}
