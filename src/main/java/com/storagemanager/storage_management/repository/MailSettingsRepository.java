package com.storagemanager.storage_management.repository;

import com.storagemanager.storage_management.model.MailSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MailSettingsRepository extends JpaRepository<MailSettings, Long> {
}
