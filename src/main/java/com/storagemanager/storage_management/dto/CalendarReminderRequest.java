package com.storagemanager.storage_management.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Un vencimiento anual propio: qué es y qué día del año vence. */
@Data
public class CalendarReminderRequest {

    @NotBlank(message = "Pon qué vence: «IBI del bajo», «Seguro del edificio»...")
    @Size(max = 120)
    private String title;

    @NotNull(message = "Falta el mes")
    @Min(1) @Max(12)
    private Integer dueMonth;

    @NotNull(message = "Falta el día")
    @Min(1) @Max(31)
    private Integer dueDay;

    @Size(max = 255)
    private String notes;
}
