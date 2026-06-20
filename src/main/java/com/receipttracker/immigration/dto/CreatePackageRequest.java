package com.receipttracker.immigration.dto;

import java.util.List;

public record CreatePackageRequest(
        String name,
        List<String> selectedFormTypes
) {}
