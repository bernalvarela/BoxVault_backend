package com.storagemanager.storage_management.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Activa los datos del emisor de facturas y contratos
 * ({@link InvoicingProperties}). No hace falta nada más: quien los necesita los
 * recibe inyectados.
 */
@Configuration
@EnableConfigurationProperties(InvoicingProperties.class)
public class InvoicingConfig {
}
