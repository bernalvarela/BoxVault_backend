package com.storagemanager.storage_management.security;

import com.storagemanager.storage_management.model.Client;
import com.storagemanager.storage_management.model.RentalAgreement;
import com.storagemanager.storage_management.model.StorageUnit;
import com.storagemanager.storage_management.repository.ClientRepository;
import com.storagemanager.storage_management.repository.RentalAgreementRepository;
import com.storagemanager.storage_management.repository.StorageUnitRepository;
import com.storagemanager.storage_management.repository.UserUnitScopeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Hasta dónde llega cada usuario.
 * <p>
 * Una concesión es una unidad y <em>todo lo que cuelga de ella</em>, ahora y en
 * adelante: conceder el local BD da sus trasteros de hoy y los que se creen
 * mañana, sin tener que volver a tocar los permisos. Quien tiene
 * {@code fullScope} —el gestor— no tiene límite ninguno; quien no lo tiene y no
 * tiene concesiones no ve nada.
 * <p>
 * Hay dos conjuntos, y la diferencia importa:
 * <ul>
 *   <li><strong>alcanzables</strong>: lo concedido y su descendencia. Son las
 *       unidades del usuario: sus datos, sus alquileres, sus cobros, sus gastos
 *       y sus totales;</li>
 *   <li><strong>de contexto</strong>: los padres de lo concedido. Salen en los
 *       listados para que el árbol se entienda —un trastero suelto, sin el local
 *       donde está, no dice nada—, pero no son suyos: ni se abren, ni se editan,
 *       ni suman en ningún total.</li>
 * </ul>
 * Todo esto se calcula sobre la marcha a partir de la base de datos. Son unas
 * pocas decenas de unidades, así que sale más barato y mucho más claro que
 * arrastrar el ámbito dentro del token.
 */
@Component
@RequiredArgsConstructor
public class UnitScope {

    private final StorageUnitRepository storageUnits;
    private final UserUnitScopeRepository grants;
    private final RentalAgreementRepository rentals;
    private final ClientRepository clients;

    /** Sin límite: ve todas las unidades. */
    public boolean isUnrestricted() {
        CurrentUser user = Authenticated.userOrNull();
        // Sin usuario es cosa de la propia aplicación (el arranque, el seeder):
        // no hay a quién limitar.
        return user == null || user.fullScope();
    }

    /**
     * Las unidades del usuario: lo concedido y su descendencia. {@code null}
     * quiere decir "todas", que no es lo mismo que un conjunto vacío —eso es no
     * ver ninguna—.
     */
    public Set<Long> accessibleUnitIds() {
        if (isUnrestricted()) return null;
        return resolve().accessible();
    }

    /** Los padres de lo concedido, sólo para que el árbol se entienda. */
    public Set<Long> contextUnitIds() {
        if (isUnrestricted()) return Set.of();
        return resolve().context();
    }

    public boolean isAccessible(Long unitId) {
        if (isUnrestricted()) return true;
        return unitId != null && resolve().accessible().contains(unitId);
    }

    /**
     * Igual que {@link #isAccessible}, pero cortando la petición. Da 403 y no
     * 404 a propósito: quien pregunta ya está dentro de la aplicación, y decirle
     * "no tienes acceso a esa unidad" no le descubre nada que no sepa.
     */
    public void requireAccessible(Long unitId) {
        if (!isAccessible(unitId)) {
            throw new AccessDeniedException("La unidad " + unitId + " no está en tu ámbito");
        }
    }

    /** Lo mismo para una unidad ya cargada. */
    public void requireAccessible(StorageUnit unit) {
        requireAccessible(unit == null ? null : unit.getId());
    }

    /**
     * Deja de una lista lo que cuelga de una unidad alcanzable: alquileres,
     * cobros, gastos... Lo que no tenga unidad se queda fuera, porque no hay
     * forma de saber de quién es.
     */
    public <T> List<T> filterByUnit(List<T> rows, Function<T, StorageUnit> unitOf) {
        Set<Long> accessible = accessibleUnitIds();
        if (accessible == null) return rows;
        List<T> visible = new ArrayList<>();
        for (T row : rows) {
            StorageUnit unit = unitOf.apply(row);
            if (unit != null && accessible.contains(unit.getId())) visible.add(row);
        }
        return visible;
    }

    /**
     * Los clientes que se ven: los que tienen —o han tenido— algún alquiler de
     * una unidad del usuario, como titulares o como segundo titular.
     * {@code null} otra vez quiere decir "todos".
     * <p>
     * Los que no tienen ningún alquiler se ven siempre: no cuelgan de ninguna
     * unidad, así que no hay ámbito que aplicarles, y sin esto no se podría dar
     * de alta un cliente para luego hacerle el contrato —desaparecería nada más
     * crearlo—.
     * <p>
     * Vive aquí, y no en {@code ClientService}, porque lo necesitan también los
     * documentos de la ficha, y ese servicio no puede depender de aquél sin
     * montar un ciclo.
     */
    public Set<Long> visibleClientIds() {
        Set<Long> accessibleUnits = accessibleUnitIds();
        if (accessibleUnits == null) return null;

        Set<Long> visible = new HashSet<>();
        Set<Long> withAnyRental = new HashSet<>();
        for (RentalAgreement rental : rentals.findAll()) {
            // rental.tenants() y no las dos columnas de siempre: un contrato
            // puede tener tres arrendatarios, y el tercero también es inquilino
            // a efectos de quién puede ver su ficha.
            for (Client tenant : rental.tenants()) {
                if (tenant == null) continue;
                withAnyRental.add(tenant.getId());
                if (rental.getStorageUnit() != null
                        && accessibleUnits.contains(rental.getStorageUnit().getId())) {
                    visible.add(tenant.getId());
                }
            }
        }
        for (Client client : clients.findAll()) {
            if (!withAnyRental.contains(client.getId())) visible.add(client.getId());
        }
        return visible;
    }

    public boolean isClientVisible(Long clientId) {
        Set<Long> visible = visibleClientIds();
        return visible == null || visible.contains(clientId);
    }

    /** Como {@link #requireAccessible}, para un cliente. */
    public void requireClientVisible(Long clientId) {
        if (!isClientVisible(clientId)) {
            throw new AccessDeniedException("Ese cliente no alquila ninguna unidad de tu ámbito");
        }
    }

    /** Las unidades que salen en un listado: las del usuario y sus padres. */
    public List<StorageUnit> visibleUnits(List<StorageUnit> units) {
        if (isUnrestricted()) return units;
        Resolved resolved = resolve();
        return units.stream()
                .filter(unit -> resolved.accessible().contains(unit.getId())
                        || resolved.context().contains(unit.getId()))
                .toList();
    }

    // ------------------------------------------------------------------
    // El cálculo
    // ------------------------------------------------------------------

    private record Resolved(Set<Long> accessible, Set<Long> context) {}

    private Resolved resolve() {
        CurrentUser user = Authenticated.user();
        List<Long> granted = grants.findByUserId(user.id()).stream()
                .map(scope -> scope.getStorageUnit().getId())
                .toList();
        if (granted.isEmpty()) {
            // Sin concesiones y sin fullScope: no ve nada. Es lo que se acordó,
            // y además es el lado seguro para un usuario recién creado.
            return new Resolved(Set.of(), Set.of());
        }

        List<StorageUnit> all = storageUnits.findAll();
        Map<Long, List<Long>> childrenOf = new HashMap<>();
        Map<Long, Long> parentOf = new HashMap<>();
        for (StorageUnit unit : all) {
            Long parentId = unit.getParent() == null ? null : unit.getParent().getId();
            parentOf.put(unit.getId(), parentId);
            if (parentId != null) {
                childrenOf.computeIfAbsent(parentId, key -> new ArrayList<>()).add(unit.getId());
            }
        }

        // Hacia abajo: lo concedido y todo su subárbol.
        Set<Long> accessible = new HashSet<>();
        Deque<Long> pending = new ArrayDeque<>(granted);
        while (!pending.isEmpty()) {
            Long unitId = pending.pop();
            if (!accessible.add(unitId)) continue;
            pending.addAll(childrenOf.getOrDefault(unitId, List.of()));
        }

        // Hacia arriba: los padres, sólo como contexto.
        Set<Long> context = new HashSet<>();
        for (Long unitId : granted) {
            Long parentId = parentOf.get(unitId);
            while (parentId != null && !accessible.contains(parentId) && context.add(parentId)) {
                parentId = parentOf.get(parentId);
            }
        }
        return new Resolved(accessible, context);
    }
}
