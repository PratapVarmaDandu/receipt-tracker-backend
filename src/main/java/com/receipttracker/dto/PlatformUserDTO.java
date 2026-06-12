package com.receipttracker.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlatformUserDTO {
    private Long id;
    private String name;
    private String email;
    private LocalDateTime joinedAt;
    private boolean platformAdmin;
    private List<String> features;
}
