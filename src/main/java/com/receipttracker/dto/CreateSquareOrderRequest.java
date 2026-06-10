package com.receipttracker.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateSquareOrderRequest {

    private List<CartLineItem> items;

    /** PICKUP or DELIVERY */
    private String fulfillmentType;

    private String recipientName;
    private String recipientEmail;
    private String recipientPhone;

    /** Only used when fulfillmentType = DELIVERY */
    private String deliveryAddress;

    private String note;

    /** Square location ID the consumer selected. Falls back to SQUARE_LOCATION_ID env var if blank. */
    private String locationId;

    /** Token from Square Web Payments SDK. When present, backend charges the card directly
     *  (no hosted checkout redirect). When absent, falls back to a payment link. */
    private String sourceId;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CartLineItem {
        private String variationId;
        private String itemName;
        private String variationName;
        private int quantity;
        private BigDecimal unitPrice;
    }
}
