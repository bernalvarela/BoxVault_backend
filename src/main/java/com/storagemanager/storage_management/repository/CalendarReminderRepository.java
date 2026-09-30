package com.storagemanager.storage_management.repository;

import com.storagemanager.storage_management.model.CalendarReminder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Los vencimientos anuales propios del calendario. */
@Repository
public interface CalendarReminderRepository extends JpaRepository<CalendarReminder, Long> {

    List<CalendarReminder> findAllByOrderByDueMonthAscDueDayAsc();
}
