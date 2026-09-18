package com.storagemanager.storage_management.model;

import com.storagemanager.storage_management.model.enums.InvoiceType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Una factura emitida: lo que se facturó, tal como se facturó.
 * <p>
 * Hasta ahora una factura era sólo un PDF que se componía leyendo el cobro en
 * vivo, y eso tiene un problema de fondo: si el cobro cambia, el mismo número
 * imprime cifras distintas. Una factura no puede funcionar así. Aquí quedan
 * congeladas las cifras y el destinatario del momento, de modo que:
 * <ul>
 *   <li>volver a imprimirla saca lo que se facturó, no lo que dice hoy el cobro;</li>
 *   <li>se puede ver de un vistazo que el cobro ya no coincide con su factura, que
 *       es lo que obliga a rectificar;</li>
 *   <li>la serie queda completa y se puede auditar sin abrir un solo PDF.</li>
 * </ul>
 * Las filas no se borran ni se editan nunca: una factura emitida se corrige con
 * otra ({@link InvoiceType#RECTIFICATIVA}), no se reescribe. Lo único que puede
 * cambiar es {@code document}, cuando se vuelve a componer el papel con las
 * mismas cifras.
 */
@Entity
@Table(name = "invoices")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** La mensualidad facturada. Una mensualidad puede tener varias facturas: la ordinaria y sus rectificativas. */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    /** A2026/0007 (ordinaria) o R2026/0001 (rectificativa). Único en toda la casa. */
    @Column(nullable = false, unique = true, length = 30)
    private String number;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private InvoiceType type = InvoiceType.ORDINARIA;

    /** La factura que ésta corrige; sólo en las rectificativas. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "rectifies_id")
    private Invoice rectifies;

    /** La causa de la rectificación, que la factura tiene que declarar. */
    @Column(length = 255)
    private String reason;

    @Column(nullable = false)
    private LocalDate issuedOn;

    // --- Las cifras, tal como se facturaron -------------------------------
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal base;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal vat;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal total;

    /** El tipo aplicado (21,00) o cero en una operación exenta. */
    @Column(nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal vatRate = new BigDecimal("21.00");

    // --- A quién, tal como se facturó -------------------------------------
    // Copiados y no leídos del cliente: si mañana se corrige su nombre o su NIF,
    // la factura entregada sigue diciendo lo que decía.
    @Column(length = 150)
    private String clientName;

    @Column(length = 50)
    private String clientTaxId;

    /** El PDF archivado. Es lo único que se sustituye al volver a componer el papel. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "document_id")
    private Document document;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    public boolean isRectificativa() {
        return type == InvoiceType.RECTIFICATIVA;
    }
}
