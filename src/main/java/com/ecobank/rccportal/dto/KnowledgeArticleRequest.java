package com.ecobank.rccportal.dto;
/** countryCode/serviceCode null ou vide = article générique (toutes filiales / toutes équipes). */
public record KnowledgeArticleRequest(Integer categoryId, String countryCode, String serviceCode,
                                      String title, String contentHtml, String tags, Integer sortOrder) {}