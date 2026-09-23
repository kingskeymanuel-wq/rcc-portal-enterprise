package com.ecobank.rccportal.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record SalesRecordRequest(
        String productName,
        String clientName,
        String clientPhone,
        Double amount,
        LocalDate saleDate,
        String status,
        String notes
) {
}
