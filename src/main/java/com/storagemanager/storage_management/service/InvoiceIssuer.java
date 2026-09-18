package com.storagemanager.storage_management.service;

import com.storagemanager.storage_management.config.InvoicingProperties;
import com.storagemanager.storage_management.model.Owner;
import com.storagemanager.storage_management.model.Ownership;
import com.storagemanager.storage_management.model.StorageUnit;
import com.storagemanager.storage_management.repository.OwnershipRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/**
 * Quién emite la factura o el contrato de una unidad: su propietario.
 * <p>
 * Los datos salen de la ficha del propietario, que es donde ya están y donde los
 * mantiene quien lleva la casa —el NIF de la comunidad de bienes, su IBAN, su
 * domicilio—. La configuración del servidor ({@link InvoicingProperties}) sólo
 * rellena lo que ahí falte: sirve para una instalación que todavía no tenga
 * propietarios cargados, no para llevar la contabilidad en un fichero .env.
 * <p>
 * Si la unidad no tiene participaciones propias se miran las del local que la
 * contiene, igual que hacen los impuestos: los trasteros no son de nadie por
 * separado, son del bajo en el que están. Y entre varios propietarios manda la
 * comunidad de bienes: es la que tiene NIF propio y la que factura. Si no hay
 * ninguna, el que más participación tenga.
 */
@Service
@RequiredArgsConstructor
public class InvoiceIssuer {

    private final OwnershipRepository ownershipRepository;
    private final InvoicingProperties fallback;

    /**
     * Los datos con los que se encabeza una factura o un contrato.
     *
     * @param entity si quien emite es una comunidad de bienes; de ello depende
     *               la coletilla del pie de la factura ("entidad en régimen de
     *               atribución de rentas"), que de una persona sería falsa.
     */
    public record Issuer(String name, String taxId, String address, String city,
                         String email, String phone, String iban, boolean entity) {}

    public Issuer forUnit(StorageUnit unit) {
        Owner owner = ownerOf(unit);
        return owner == null ? fromProperties() : from(owner);
    }

    /**
     * El emisor para una vista previa, que no va de ninguna unidad concreta: la
     * comunidad de bienes si la hay, y si no el primer propietario que conste.
     * Con los datos de verdad, que es de lo que sirve una vista previa.
     */
    public Issuer any() {
        List<Ownership> shares = ownershipRepository.findAll();
        Owner owner = shares.stream().map(Ownership::getOwner).filter(Owner::isEntity).findFirst()
                .orElseGet(() -> shares.stream().map(Ownership::getOwner).findFirst().orElse(null));
        return owner == null ? fromProperties() : from(owner);
    }

    private Issuer from(Owner owner) {
        return new Issuer(
                pick(owner.getFullName(), fallback.getIssuerName()),
                pick(owner.getDocumentId(), fallback.getIssuerTaxId()),
                pick(owner.getAddress(), fallback.getIssuerAddress()),
                // El municipio no es un campo del propietario: su domicilio va en
                // una línea. Esto queda para quien lo tenga puesto en el entorno.
                fallback.getIssuerCity(),
                pick(owner.getEmail(), fallback.getIssuerEmail()),
                pick(owner.getPhone(), fallback.getIssuerPhone()),
                pick(owner.getBankAccount(), fallback.getIssuerIban()),
                owner.isEntity());
    }

    private Issuer fromProperties() {
        return new Issuer(
                fallback.getIssuerName(), fallback.getIssuerTaxId(), fallback.getIssuerAddress(),
                fallback.getIssuerCity(), fallback.getIssuerEmail(), fallback.getIssuerPhone(),
                fallback.getIssuerIban(),
                // Sin propietario que mirar no se afirma lo que no consta.
                false);
    }

    /** El propietario que factura esta unidad, o null si no consta ninguno. */
    private Owner ownerOf(StorageUnit unit) {
        int guard = 0;
        for (StorageUnit u = unit; u != null && guard++ < 32; u = u.getParent()) {
            List<Ownership> shares = ownershipRepository.findByStorageUnitId(u.getId());
            if (shares.isEmpty()) continue;

            return shares.stream()
                    .map(Ownership::getOwner)
                    .filter(Owner::isEntity)
                    .findFirst()
                    .orElseGet(() -> shares.stream()
                            .max(Comparator.comparing(share -> share.getSharePercent() == null
                                    ? BigDecimal.ZERO : share.getSharePercent()))
                            .map(Ownership::getOwner)
                            .orElse(null));
        }
        return null;
    }

    private static String pick(String preferred, String other) {
        return preferred != null && !preferred.isBlank() ? preferred.trim() : other;
    }
}
