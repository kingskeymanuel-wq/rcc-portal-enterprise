package com.ecobank.rccportal.dto;

import java.util.List;

public record ProcessPuzzleResultResponse(boolean correct, int correctPositions, int totalSteps, List<Integer> correctOrderStepIds) {}
