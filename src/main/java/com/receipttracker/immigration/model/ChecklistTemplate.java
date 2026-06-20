package com.receipttracker.immigration.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "imm_checklist_templates")
@Data @NoArgsConstructor @AllArgsConstructor
public class ChecklistTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "form_type", length = 50, nullable = false)
    private String formType; // I129 | I485 | I140_EB2 | I140_EB3 | PERM | ...

    @Column(name = "item_key", length = 100, nullable = false)
    private String itemKey; // unique within a formType; used for upsert dedup

    @Column(name = "label", length = 500, nullable = false)
    private String label;

    @Column(name = "category", length = 100, nullable = false)
    private String category;

    @Column(name = "required", nullable = false)
    private boolean required;

    // Optional JSON condition: {"caseTypeIn":["I485"]} or {"i140Approved":true}
    // null = always include
    @Column(name = "condition_rule", columnDefinition = "TEXT")
    private String conditionRule;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;
}
