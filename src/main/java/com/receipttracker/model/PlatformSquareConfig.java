package com.receipttracker.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "platform_square_config")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlatformSquareConfig {

    @Id
    private Long id = 1L;

    @Column(name = "access_token_enc", length = 2048)
    private String accessTokenEnc;

    @Column(name = "application_id")
    private String applicationId;

    @Column(name = "location_id")
    private String locationId;

    @Column(name = "webhook_signature_key_enc", length = 2048)
    private String webhookSignatureKeyEnc;

    @Enumerated(EnumType.STRING)
    @Column(name = "environment")
    private Organization.SquareEnv environment = Organization.SquareEnv.SANDBOX;

    @Column(name = "plan_id_garage")
    private String planIdGarage;

    @Column(name = "plan_id_vault")
    private String planIdVault;

    @Column(name = "plan_id_jobs")
    private String planIdJobs;

    @Column(name = "plan_id_suite")
    private String planIdSuite;

    public boolean isConfigured() {
        return accessTokenEnc != null && !accessTokenEnc.isBlank();
    }
}
