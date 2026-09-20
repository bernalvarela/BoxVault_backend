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
                         String email, String phone, String iban, boolean entity,
                         String ownersPhrase, List<Owner> owners) {}

    public Issuer forUnit(StorageUnit unit) {
        List<Ownership> shares = sharesOf(unit);
        Owner owner = pickOwner(shares);
        return owner == null ? fromProperties() : from(owner, shares);
    }

    private Issuer from(Owner owner, List<Ownership> shares) {
        List<Owner> all = listed(shares);
        return new Issuer(
                pick(owner.getFullName(), fallback.getIssuerName()),
                pick(owner.getDocumentId(), fallback.getIssuerTaxId()),
                pick(owner.getAddress(), fallback.getIssuerAddress()),
                pick(owner.getCity(), fallback.getIssuerCity()),
                pick(owner.getEmail(), fallback.getIssuerEmail()),
                pick(owner.getPhone(), fallback.getIssuerPhone()),
                pick(owner.getBankAccount(), fallback.getIssuerIban()),
                owner.isEntity(),
                ownersPhrase(all),
                all);
    }

    /**
     * Todos los propietarios de la unidad con su NIF, escrito como se escribe en
     * un contrato: "Fulano (NIF ...) y Mengano (NIF ...)".
     * <p>
     * Hace falta porque un piso suele ser de dos personas y el contrato lo firman
     * las dos; el emisor "principal" sirve para una factura, pero no para decir
     * quién arrienda.
     */
    private String ownersPhrase(List<Owner> owners) {
        List<String> names = owners.stream()
                .map(owner -> owner.getDocumentId() == null || owner.getDocumentId().isBlank()
                        ? owner.getFullName()
                        : owner.getFullName() + " (NIF " + owner.getDocumentId() + ")")
                .toList();
        if (names.isEmpty()) return InvoicingProperties.MISSING;
        if (names.size() == 1) return names.get(0);
        return String.join(", ", names.subList(0, names.size() - 1)) + " y " + names.get(names.size() - 1);
    }

    private Issuer fromProperties() {
        return new Issuer(
                fallback.getIssuerName(), fallback.getIssuerTaxId(), fallback.getIssuerAddress(),
                fallback.getIssuerCity(), fallback.getIssuerEmail(), fallback.getIssuerPhone(),
                fallback.getIssuerIban(),
                // Sin propietario que mirar no se afirma lo que no consta.
                false,
                InvoicingProperties.MISSING,
                List.of());
    }

    /**
     * Los propietarios que arriendan, de mayor a menor participación.
     * <p>
     * La comunidad de bienes se cae de la lista cuando hay más: es una
     * envoltura fiscal de los mismos señores, y en un contrato quien arrienda
     * son ellos. Si es la única propietaria, entonces sí es quien arrienda.
     * <p>
     * El orden no es capricho: de aquí salen {@code {{arrendador[1]}}},
     * {@code [2]}... y quien escribe una plantilla espera que el primero sea el
     * principal, no el que la base devolviera antes.
     */
    private List<Owner> listed(List<Ownership> shares) {
        return shares.stream()
                .filter(share -> share.getOwner() != null)
                .filter(share -> !share.getOwner().isEntity() || shares.size() == 1)
                .sorted(Comparator.comparing((Ownership share) -> share.getSharePercent() == null
                        ? BigDecimal.ZERO : share.getSharePercent()).reversed())
                .map(Ownership::getOwner)
                .toList();
    }

/** Las participaciones que mandan en esta unidad: las suyas o las del local que la contiene. */
    private List<Ownership> sharesOf(StorageUnit unit) {
        int guard = 0;
        for (StorageUnit u = unit; u != null && guard++ < 32; u = u.getParent()) {
            List<Ownership> shares = ownershipRepository.findByStorageUnitId(u.getId());
            if (!shares.isEmpty()) return shares;
        }
        return List.of();
    }

    /** Quién emite de entre ellas: la comunidad de bienes, y si no la que más tenga. */
    private Owner pickOwner(List<Ownership> shares) {
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

    private static String pick(String preferred, String other) {
        return preferred != null && !preferred.isBlank() ? preferred.trim() : other;
    }
}
