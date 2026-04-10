package com.kafein.discovery.repository;

import com.kafein.discovery.entity.ColumnInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ColumnInfoRepository extends JpaRepository<ColumnInfo, UUID> {

    List<ColumnInfo> findByTableIdOrderByOrdinalPositionAsc(UUID tableId);

    List<ColumnInfo> findByMetadataId(UUID metadataId);
}
