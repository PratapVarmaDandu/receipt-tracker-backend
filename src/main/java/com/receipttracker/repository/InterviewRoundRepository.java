package com.receipttracker.repository;

import com.receipttracker.model.InterviewRound;
import com.receipttracker.model.JobApplication;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InterviewRoundRepository extends JpaRepository<InterviewRound, Long> {

    List<InterviewRound> findByJobApplicationOrderByScheduledAtAsc(JobApplication jobApplication);
}
