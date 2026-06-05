package com.receipttracker.dto;

import java.math.BigDecimal;

public class ExpenseShareItemDTO {
    private Long receiptItemId;
    private String itemName;
    private BigDecimal itemTotal;
    private BigDecimal taxAmount;
    private BigDecimal taxRate;
    private boolean taxable;

    public ExpenseShareItemDTO() {}

    public Long getReceiptItemId() { return receiptItemId; }
    public void setReceiptItemId(Long receiptItemId) { this.receiptItemId = receiptItemId; }
    public String getItemName() { return itemName; }
    public void setItemName(String itemName) { this.itemName = itemName; }
    public BigDecimal getItemTotal() { return itemTotal; }
    public void setItemTotal(BigDecimal itemTotal) { this.itemTotal = itemTotal; }
    public BigDecimal getTaxAmount() { return taxAmount; }
    public void setTaxAmount(BigDecimal taxAmount) { this.taxAmount = taxAmount; }
    public BigDecimal getTaxRate() { return taxRate; }
    public void setTaxRate(BigDecimal taxRate) { this.taxRate = taxRate; }
    public boolean isTaxable() { return taxable; }
    public void setTaxable(boolean taxable) { this.taxable = taxable; }
}
