package com.receipttracker.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SquareVariationDTO {
    private String id;
    private String name;
    private BigDecimal price;
    private String currency;
    private boolean available;
}
