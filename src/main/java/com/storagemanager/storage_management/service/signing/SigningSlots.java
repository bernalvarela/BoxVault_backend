package com.storagemanager.storage_management.service.signing;

import com.storagemanager.storage_management.service.pdf.ContractPdfService.Slot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Los huecos de firma de un PDF, guardados como texto: una línea por hueco,
 * {@code clave;página;x1;y1;x2;y2}.
 * <p>
 * Texto plano y no JSON a propósito: son seis valores por línea, se leen a ojo
 * en la base de datos y no piden reflexión ninguna en la imagen nativa.
 */
final class SigningSlots {

    private SigningSlots() {}

    static String encode(List<Slot> slots) {
        StringBuilder text = new StringBuilder();
        for (Slot slot : slots) {
            text.append(slot.key()).append(';').append(slot.page()).append(';')
                    .append(format(slot.llx())).append(';').append(format(slot.lly())).append(';')
                    .append(format(slot.urx())).append(';').append(format(slot.ury())).append('\n');
        }
        return text.toString();
    }

    static List<Slot> decode(String text) {
        List<Slot> slots = new ArrayList<>();
        if (text == null) return slots;
        for (String line : text.split("\n")) {
            String[] parts = line.trim().split(";");
            if (parts.length != 6) continue;
            slots.add(new Slot(parts[0], Integer.parseInt(parts[1]),
                    Float.parseFloat(parts[2]), Float.parseFloat(parts[3]),
                    Float.parseFloat(parts[4]), Float.parseFloat(parts[5])));
        }
        return slots;
    }

    private static String format(float value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
