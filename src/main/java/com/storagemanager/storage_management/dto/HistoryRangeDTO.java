package com.storagemanager.storage_management.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Span of recorded activity (payments by due date and expenses) of a set of
 * groups: lets the UI offer a "whole history" statistics range. Both dates are
 * null when there is no activity at all.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HistoryRangeDTO {
    private LocalDate firstDate;
    private LocalDate lastDate;
}
