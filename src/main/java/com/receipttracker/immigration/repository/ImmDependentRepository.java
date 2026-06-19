package com.receipttracker.immigration.repository;

import com.receipttracker.immigration.model.Beneficiary;
import com.receipttracker.immigration.model.ImmDependent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ImmDependentRepository extends JpaRepository<ImmDependent, Long> {

    List<ImmDependent> findByBeneficiaryOrderByLastNameAscFirstNameAsc(Beneficiary beneficiary);

    void deleteByBeneficiary(Beneficiary beneficiary);
}
