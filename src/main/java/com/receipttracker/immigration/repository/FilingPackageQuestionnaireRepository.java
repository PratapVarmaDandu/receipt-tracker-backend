package com.receipttracker.immigration.repository;

import com.receipttracker.immigration.model.FilingPackageQuestionnaire;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FilingPackageQuestionnaireRepository extends JpaRepository<FilingPackageQuestionnaire, Long> {
    List<FilingPackageQuestionnaire> findByFilingPackageId(Long packageId);
    Optional<FilingPackageQuestionnaire> findByToken(String token);
}
