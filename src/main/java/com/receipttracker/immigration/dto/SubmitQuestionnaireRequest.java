package com.receipttracker.immigration.dto;

import java.util.Map;

public record SubmitQuestionnaireRequest(
        // question key → plaintext value
        Map<String, String> answers
) {}
