package com.receipttracker.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SquareCatalogItemDTO {
    private String id;
    private String name;
    private String description;
    private String categoryId;
    private String categoryName;
    private String imageUrl;
    private List<SquareVariationDTO> variations;
}
