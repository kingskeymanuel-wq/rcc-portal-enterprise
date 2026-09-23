package com.ecobank.rccportal.dto;

import java.util.List;

public record ProcessPuzzleSubmitRequest(String procedureTitle, List<Integer> orderedStepIds) {}
