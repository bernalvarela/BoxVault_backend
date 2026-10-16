package com.storagemanager.storage_management;

import com.storagemanager.storage_management.config.NativeHints;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ImportRuntimeHints(NativeHints.class)
// Para RentalClosingService, que cierra cada madrugada los contratos cuya baja vence.
@EnableScheduling
// Para SignedContractMailer, que manda el contrato firmado fuera del hilo de la
// petición: el último firmante no espera a que responda el servidor de correo.
@EnableAsync
public class StorageManagementApplication {

	public static void main(String[] args) {
		SpringApplication.run(StorageManagementApplication.class, args);
	}

}
