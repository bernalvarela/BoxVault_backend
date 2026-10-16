package com.storagemanager.storage_management.model;

import com.storagemanager.storage_management.model.enums.MailSecurity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * El servidor de correo saliente, tal como se configura desde la aplicación
 * (Administración › Configuración). Hay una sola fila, la {@link #SINGLETON}.
 * <p>
 * La contraseña no se guarda en claro: va cifrada (ver SecretCipher) y nunca
 * sale hacia el navegador; la pantalla sólo sabe si hay una puesta.
 */
@EntityListeners(AuditingEntityListener.class)
@Entity
@Table(name = "mail_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MailSettings {

    public static final long SINGLETON = 1L;

    @Id
    private Long id;

    /** Si se manda correo. Apagado, nada sale aunque esté todo rellenado. */
    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = false;

    @Column(length = 150)
    private String host;

    @Column(nullable = false)
    @Builder.Default
    private Integer port = 587;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private MailSecurity security = MailSecurity.STARTTLS;

    @Column(length = 150)
    private String username;

    /** La contraseña cifrada; null si no hay. */
    @Column(columnDefinition = "TEXT")
    private String passwordEncrypted;

    /** El remitente: la dirección y el nombre que ve quien lo recibe. */
    @Column(length = 150)
    private String fromAddress;

    @Column(length = 150)
    private String fromName;

    /** Direcciones que reciben siempre copia del contrato firmado, separadas por comas. */
    @Column(length = 500)
    private String copyTo;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    @LastModifiedBy
    @Column(length = 60)
    private String updatedBy;
}
