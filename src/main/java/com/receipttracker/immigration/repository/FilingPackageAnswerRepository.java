package com.receipttracker.immigration.repository;

import com.receipttracker.immigration.model.FilingPackageAnswer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FilingPackageAnswerRepository extends JpaRepository<FilingPackageAnswer, Long> {
    List<FilingPackageAnswer> findByFilingPackageId(Long packageId);
    Optional<FilingPackageAnswer> findByFilingPackageIdAndQuestionKey(Long packageId, String questionKey);
}
