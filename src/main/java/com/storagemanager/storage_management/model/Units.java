package com.storagemanager.storage_management.model;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Preguntas sobre el inventario que una unidad suelta no puede responder.
 * <p>
 * La que importa: qué unidades se alquilan. Un local que agrupa otras —el bajo
 * delantero con sus nueve trasteros— no se alquila entero, sus trasteros sí;
 * pero un local vacío, como el bajo trasero, se alquila como cualquier otra
 * unidad. Lo que decide no es el tipo, sino si algo cuelga de ella, y eso sólo
 * se sabe viendo todas las unidades a la vez.
 */
public final class Units {

    private Units() {}

    /** Las unidades de las que cuelga alguna otra: ésas no se alquilan. */
    public static Set<Long> containerIds(List<StorageUnit> units) {
        Set<Long> ids = new HashSet<>();
        for (StorageUnit unit : units) {
            if (unit.getParent() != null) ids.add(unit.getParent().getId());
        }
        return ids;
    }

    /** Las que sí se alquilan: todas menos las que contienen a otras. */
    public static List<StorageUnit> rentable(List<StorageUnit> units) {
        Set<Long> containers = containerIds(units);
        return units.stream().filter(u -> !containers.contains(u.getId())).toList();
    }
}
