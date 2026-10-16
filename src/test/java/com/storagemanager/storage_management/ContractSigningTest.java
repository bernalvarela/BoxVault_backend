package com.storagemanager.storage_management;

import com.storagemanager.storage_management.dto.SigningDTOs;
import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.exception.ResourceNotFoundException;
import com.storagemanager.storage_management.model.ContractSigning;
import com.storagemanager.storage_management.model.Document;
import com.storagemanager.storage_management.model.RentalAgreement;
import com.storagemanager.storage_management.model.enums.RentalStatus;
import com.storagemanager.storage_management.model.enums.SigningKind;
import com.storagemanager.storage_management.model.enums.SigningStatus;
import com.storagemanager.storage_management.repository.AppUserRepository;
import com.storagemanager.storage_management.repository.ContractSigningRepository;
import com.storagemanager.storage_management.repository.RentalAgreementRepository;
import com.storagemanager.storage_management.repository.SigningEventRepository;
import com.storagemanager.storage_management.security.CurrentUser;
import com.storagemanager.storage_management.service.ContractService;
import com.storagemanager.storage_management.service.DocumentService;
import com.storagemanager.storage_management.service.RentalAgreementService;
import com.storagemanager.storage_management.service.RentalDocumentService;
import com.storagemanager.storage_management.service.signing.ContractSigningService;
import com.storagemanager.storage_management.service.signing.SignatureSealService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * La firma de un contrato de principio a fin, sobre los datos sembrados: se
 * prepara, firman unos en persona y otro con su enlace (con un DNI mal
 * tecleado por medio), se sella y queda archivado el contrato firmado, que
 * pasa por sellado; el registro sigue encadenado. Y mientras se firma, el
 * contrato no se rehace. Cada prueba deja las firmas y sus PDF borrados.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class ContractSigningTest {

    private static final ContractSigningService.Origin TABLET =
            new ContractSigningService.Origin("192.168.1.20", "Mozilla/5.0 (Tablet de prueba)");
    private static final ContractSigningService.Origin PHONE =
            new ContractSigningService.Origin("203.0.113.7", "Mozilla/5.0 (Móvil de prueba)");

    @Autowired private ContractSigningService signing;
    @Autowired private ContractSigningRepository signings;
    @Autowired private SigningEventRepository events;
    @Autowired private RentalAgreementRepository rentals;
    @Autowired private RentalAgreementService rentalService;
    @Autowired private RentalDocumentService rentalDocuments;
    @Autowired private ContractService contracts;
    @Autowired private DocumentService documents;
    @Autowired private AppUserRepository users;

    private final List<Long> created = new ArrayList<>();
    private Long rentalId;

    @BeforeEach
    void asAdmin() {
        CurrentUser admin = CurrentUser.of(users.findByUsername("admin").orElseThrow());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(admin, null, List.of()));
    }

    @AfterEach
    void cleanUp() {
        // Las firmas y sus PDF, en el orden que dejan las FK.
        for (Long id : created) {
            signings.findById(id).ifPresent(s -> {
                Document original = s.getOriginalDocument();
                Document signed = s.getSignedDocument();
                events.deleteAll(events.findBySigningIdOrderByIdAsc(id));
                signings.delete(s);
                if (signed != null) rentalDocuments.delete(rentalId, signed.getId());
                rentalDocuments.delete(rentalId, original.getId());
            });
        }
        SecurityContextHolder.clearContext();
    }

    private RentalAgreement anActiveRental() {
        RentalAgreement rental = rentals.findByStatus(RentalStatus.ACTIVE).stream()
                .filter(r -> !r.tenants().isEmpty())
                .filter(r -> r.tenants().stream().anyMatch(c -> c.getDocumentId() != null && !c.getDocumentId().isBlank()))
                .filter(r -> signings.findByRentalAgreementIdOrderByIdDesc(r.getId()).isEmpty())
                .findFirst()
                .orElseThrow(() -> new AssertionError("no hay contratos en vigor con un arrendatario con NIF en los datos sembrados"));
        rentalId = rental.getId();
        return rental;
    }

    private SigningDTOs.SigningView prepare(RentalAgreement rental) {
        SigningDTOs.Overview overview = signing.overview(rental.getId());
        Long member = overview.onBehalfOptions().isEmpty() ? null : overview.onBehalfOptions().get(0).ownerId();
        SigningDTOs.SigningView view = signing.prepare(rental.getId(),
                new SigningDTOs.PrepareRequest(SigningKind.ENTRADA, member), TABLET);
        created.add(view.id());
        return view;
    }

    @Test
    void aContractIsSignedInPersonAndWithALinkAndEndsUpSealed() {
        RentalAgreement rental = anActiveRental();
        SigningDTOs.SigningView view = prepare(rental);
        assertEquals(SigningStatus.EN_CURSO, view.status());
        assertFalse(view.signers().isEmpty());
        assertTrue(view.chainIntact());

        // El que firmará con el enlace: un arrendatario con NIF. Los demás, en persona.
        SigningDTOs.SignerView remote = view.signers().stream()
                .filter(s -> s.role() == com.storagemanager.storage_management.model.enums.SignerRole.ARRENDATARIO)
                .filter(s -> s.taxId() != null)
                .findFirst().orElseThrow();
        for (SigningDTOs.SignerView signer : view.signers()) {
            if (signer.id().equals(remote.id())) continue;
            view = signing.signInPerson(rentalId, view.id(), signer.id(), signaturePng(), true, TABLET);
        }
        assertEquals(SigningStatus.EN_CURSO, view.status(), "falta uno");

        SigningDTOs.LinkView link = signing.link(rentalId, view.id(), remote.id(), "http://localhost:8088", TABLET);
        assertTrue(link.url().startsWith("http://localhost:8088/#/firmar?t="), link.url());
        String token = link.url().substring(link.url().indexOf("t=") + 2);

        SigningDTOs.PublicInfo info = signing.publicInfo(token, PHONE);
        assertEquals(remote.fullName(), info.signerName());
        assertFalse(info.signed());
        assertNotNull(read(signing.publicDocument(token)));

        // Un DNI que no es el suyo no firma, y el intento cuenta.
        BadRequestException wrong = assertThrows(BadRequestException.class,
                () -> signing.publicSign(token, signaturePng(), "00000000T", true, PHONE));
        assertTrue(wrong.getMessage().contains("quedan 4 intentos"), wrong.getMessage());
        // Sin aceptar el contrato, tampoco.
        assertThrows(BadRequestException.class,
                () -> signing.publicSign(token, signaturePng(), remote.taxId(), false, PHONE));

        info = signing.publicSign(token, signaturePng(), " " + remote.taxId().toLowerCase() + " ", true, PHONE);
        assertTrue(info.signed());
        assertTrue(info.completed(), "con la última firma se sella");

        ContractSigning done = signings.findById(view.id()).orElseThrow();
        assertEquals(SigningStatus.COMPLETADA, done.getStatus());
        assertNotNull(done.getSignedDocument());
        assertTrue(SignatureSealService.verify(read(documents.open(done.getSignedDocument()))),
                "el contrato firmado pasa por sellado");
        assertTrue(signing.verifyEvents(view.id()), "el registro sigue encadenado");

        // Con el mismo enlace se descarga la copia firmada; para firmar ya no vale.
        assertNotNull(read(signing.publicCopy(token, PHONE)));
        assertThrows(ResourceNotFoundException.class, () -> signing.publicDocument(token));
    }

    @Test
    void whileSigningTheContractCannotBeRebuiltUntilCancelled() {
        RentalAgreement rental = anActiveRental();
        SigningDTOs.SigningView view = prepare(rental);

        assertThrows(BadRequestException.class, () -> contracts.generate(rentalId), "no se rehace a mitad de firma");
        assertThrows(BadRequestException.class, () -> rentalDocuments.delete(rentalId, view.originalDocumentId()),
                "el PDF que se firma no se borra");
        assertThrows(BadRequestException.class, () -> rentalService.deleteAgreement(rentalId),
                "un contrato con firmas no se borra");

        SigningDTOs.SigningView cancelled = signing.cancel(rentalId, view.id(), "Cambia la renta", TABLET);
        assertEquals(SigningStatus.CANCELADA, cancelled.status());
        assertThrows(BadRequestException.class,
                () -> signing.signInPerson(rentalId, view.id(), view.signers().get(0).id(), signaturePng(), true, TABLET),
                "en una firma cancelada ya no firma nadie");

        // Cancelada, el contrato se puede rehacer; el PDF de la firma cancelada se conserva.
        Document rebuilt = contracts.generate(rentalId);
        assertNotEquals(view.originalDocumentId(), rebuilt.getId());
        assertTrue(signings.usesDocument(view.originalDocumentId()));
        rentalDocuments.delete(rentalId, rebuilt.getId());
    }

    @Test
    void anUnknownOrExpiredTokenGivesNothingAway() {
        assertThrows(ResourceNotFoundException.class, () -> signing.publicInfo("no-existe", PHONE));
        assertThrows(ResourceNotFoundException.class, () -> signing.publicInfo("", PHONE));
        assertThrows(ResourceNotFoundException.class, () -> signing.publicDocument(null));
    }

    // ------------------------------------------------------------------

    private static byte[] read(DocumentService.Content content) {
        try (InputStream in = content.stream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] signaturePng() {
        BufferedImage image = new BufferedImage(240, 90, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(0x1e, 0x3a, 0x8a));
        g.setStroke(new BasicStroke(3));
        g.drawLine(10, 70, 100, 15);
        g.drawLine(100, 15, 230, 60);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
