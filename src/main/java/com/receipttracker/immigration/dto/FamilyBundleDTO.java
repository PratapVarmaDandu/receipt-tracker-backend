package com.receipttracker.immigration.dto;

import java.util.List;

public record FamilyBundleDTO(ImmigrationCaseDTO primaryCase, List<ImmigrationCaseDTO> dependentCases) {}
