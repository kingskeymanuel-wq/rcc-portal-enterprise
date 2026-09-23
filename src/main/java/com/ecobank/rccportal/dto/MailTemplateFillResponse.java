package com.ecobank.rccportal.dto;

import java.util.List;

public record MailTemplateFillResponse(String subject, String body, List<String> placeholders) {
}
