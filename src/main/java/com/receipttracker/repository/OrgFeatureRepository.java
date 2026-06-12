package com.receipttracker.repository;

import com.receipttracker.model.AppFeature;
import com.receipttracker.model.OrgFeature;
import com.receipttracker.model.Organization;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrgFeatureRepository extends JpaRepository<OrgFeature, Long> {
    List<OrgFeature> findByOrg(Organization org);
    Optional<OrgFeature> findByOrgAndFeature(Organization org, AppFeature feature);
    void deleteByOrgAndFeature(Organization org, AppFeature feature);
    long countByFeature(AppFeature feature);
}
