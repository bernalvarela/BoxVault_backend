package com.storagemanager.storage_management;

import com.storagemanager.storage_management.config.NativeHints;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportRuntimeHints;

@SpringBootApplication
@ImportRuntimeHints(NativeHints.class)
public class StorageManagementApplication {

	public static void main(String[] args) {
		SpringApplication.run(StorageManagementApplication.class, args);
	}

}
