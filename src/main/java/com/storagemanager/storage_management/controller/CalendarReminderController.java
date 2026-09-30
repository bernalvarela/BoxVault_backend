package com.storagemanager.storage_management.controller;

import com.storagemanager.storage_management.dto.CalendarReminderRequest;
import com.storagemanager.storage_management.exception.ResourceNotFoundException;
import com.storagemanager.storage_management.model.CalendarReminder;
import com.storagemanager.storage_management.repository.CalendarReminderRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Los vencimientos anuales propios del calendario (IBI, seguros...).
 * <p>
 * Cuelgan del permiso de Gastos porque son eso: recibos que llegan cada año.
 * No hay lógica que merezca un servicio aparte: se guardan tal cual y el
 * calendario, en el navegador, los coloca en el año que toque.
 */
@RestController
@RequestMapping("/api/calendar-reminders")
@RequiredArgsConstructor
public class CalendarReminderController {

    private final CalendarReminderRepository reminders;

    @PreAuthorize("@access.can('GASTOS','LEER')")
    @GetMapping
    public ResponseEntity<List<CalendarReminder>> list() {
        return ResponseEntity.ok(reminders.findAllByOrderByDueMonthAscDueDayAsc());
    }

    @PreAuthorize("@access.can('GASTOS','ESCRIBIR')")
    @PostMapping
    public ResponseEntity<CalendarReminder> create(@Valid @RequestBody CalendarReminderRequest request) {
        CalendarReminder reminder = new CalendarReminder();
        apply(reminder, request);
        return new ResponseEntity<>(reminders.save(reminder), HttpStatus.CREATED);
    }

    @PreAuthorize("@access.can('GASTOS','ESCRIBIR')")
    @PutMapping("/{id}")
    public ResponseEntity<CalendarReminder> update(@PathVariable Long id,
                                                   @Valid @RequestBody CalendarReminderRequest request) {
        CalendarReminder reminder = require(id);
        apply(reminder, request);
        return ResponseEntity.ok(reminders.save(reminder));
    }

    @PreAuthorize("@access.can('GASTOS','ESCRIBIR')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        reminders.delete(require(id));
        return ResponseEntity.noContent().build();
    }

    private CalendarReminder require(Long id) {
        return reminders.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Calendar reminder not found with id: " + id));
    }

    private static void apply(CalendarReminder reminder, CalendarReminderRequest request) {
        reminder.setTitle(request.getTitle().trim());
        reminder.setDueMonth(request.getDueMonth());
        reminder.setDueDay(request.getDueDay());
        String notes = request.getNotes();
        reminder.setNotes(notes == null || notes.isBlank() ? null : notes.trim());
    }
}
