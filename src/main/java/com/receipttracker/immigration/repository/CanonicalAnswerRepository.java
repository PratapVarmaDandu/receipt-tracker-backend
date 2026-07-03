package com.receipttracker.immigration.repository;

import com.receipttracker.immigration.model.CanonicalAnswer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CanonicalAnswerRepository extends JpaRepository<CanonicalAnswer, Long> {
    List<CanonicalAnswer> findBySubjectTypeAndSubjectId(String subjectType, Long subjectId);
    Optional<CanonicalAnswer> findBySubjectTypeAndSubjectIdAndQuestionKey(String subjectType, Long subjectId, String questionKey);
}
