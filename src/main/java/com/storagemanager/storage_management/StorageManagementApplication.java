package com.storagemanager.storage_management;

import com.storagemanager.storage_management.config.NativeHints;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ImportRuntimeHints(NativeHints.class)
// Para RentalClosingService, que cierra cada madrugada los contratos cuya baja vence.
@EnableScheduling
public class StorageManagementApplication {

	public static void main(String[] args) {
		SpringApplication.run(StorageManagementApplication.class, args);
	}

}
