package com.storagemanager.storage_management.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Activa {@link SigningProperties}, como {@link InvoicingConfig} con las suyas. */
@Configuration
@EnableConfigurationProperties(SigningProperties.class)
public class SigningConfig {
}
