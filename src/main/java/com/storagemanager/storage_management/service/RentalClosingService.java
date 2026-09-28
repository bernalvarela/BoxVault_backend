package com.storagemanager.storage_management.service;

import com.storagemanager.storage_management.model.RentalAgreement;
import com.storagemanager.storage_management.model.enums.RentalStatus;
import com.storagemanager.storage_management.repository.RentalAgreementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * Cierra los contratos con la baja dada cuando llega su fecha.
 * <p>
 * Un contrato finalizado con una fecha de fin por venir sigue en vigor hasta
 * entonces (ver {@link RentalAgreement#getTerminationNoticeDate()}). El día
 * siguiente a su fecha de fin ya no lo está: pasa a terminado y la unidad queda
 * libre. Se mira cada madrugada y al arrancar, por si el servidor estaba parado
 * a esa hora.
 * <p>
 * Sólo toca los que tienen la baja dada. Un contrato en vigor con la fecha de
 * fin pasada y SIN baja se deja como está: es uno que se prorroga, y decidir
 * qué se hace con él es cosa de quien lo lleva (sale en la bandeja de avisos).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RentalClosingService {

    private final RentalAgreementRepository rentals;
    private final RentalAgreementService rentalService;

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        closeDue();
    }

    /**
     * Sin transacción de conjunto a propósito: cada save lleva la suya, así que
     * cada contrato se cierra por separado y uno que falle no impide cerrar los
     * demás; se volverá a intentar la próxima vez.
     */
    @Scheduled(cron = "0 5 0 * * *")
    public void closeDue() {
        List<RentalAgreement> due = rentals.findByStatusAndTerminationNoticeDateIsNotNullAndEndDateBefore(
                RentalStatus.ACTIVE, LocalDate.now());
        for (RentalAgreement rental : due) {
            try {
                rentalService.close(rental);
                rentals.save(rental);
                log.info("Cerrado el contrato {}: su baja era para el {}",
                        rental.getAgreementNumber(), rental.getEndDate());
            } catch (RuntimeException e) {
                log.error("No se pudo cerrar el contrato {} con la baja vencida", rental.getAgreementNumber(), e);
            }
        }
    }
}
