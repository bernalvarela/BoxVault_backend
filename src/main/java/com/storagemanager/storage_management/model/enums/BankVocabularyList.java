package com.storagemanager.storage_management.model.enums;

/**
 * Las listas de palabras con las que se leen los conceptos del banco. Se
 * guardan en la base y se cambian desde la aplicación: cuando un inquilino
 * escribe "nmr" en vez de "nº", se añade a su lista y vale desde la próxima
 * importación, sin recompilar.
 * <p>
 * {@link #valueKind} dice qué acompaña a cada palabra: nada, el número del mes
 * ("XULLO" -> 7) o la categoría de gasto ("IBERDROLA" -> SUMINISTROS).
 */
public enum BankVocabularyList {

    UNIT_WORD("Palabras de unidad",
            "Las que van delante del número de la unidad: «trastero 4», «baixo 7». Las de 7 letras o más se reconocen también pegadas a la palabra anterior («pagotrastero»).",
            ValueKind.NONE),
    NUMBER_MARKER("Abreviaturas de número",
            "Lo que se escribe entre la palabra de unidad y el número: «nº», «número», «nr», «nmr».",
            ValueKind.NONE),
    MONTH("Meses",
            "Cómo se escribe cada mes en los conceptos, en castellano o en gallego («xullo»). Sirven para saber qué mensualidad se paga.",
            ValueKind.MONTH),
    DEPOSIT("Palabras de fianza",
            "Un ingreso o un cargo que las lleva es una fianza o su devolución: no es una mensualidad ni un gasto.",
            ValueKind.NONE),
    LOAN("Palabras de hipoteca o préstamo",
            "Un cargo que las lleva es la cuota de una hipoteca o un préstamo: se propone como gasto «Hipoteca».",
            ValueKind.NONE),
    OWNER_TRANSFER("Palabras de traspaso a propietarios",
            "Con ellas, una transferencia que nombra a un propietario solo por su nombre de pila es un reparto de beneficios, no un gasto.",
            ValueKind.NONE),
    EXPENSE_WORD("Palabras de cada categoría de gasto",
            "Las que delatan la categoría de un cargo: «Iberdrola» es un suministro, «IBI» un tributo. Si un cargo lleva palabras de varias, manda la categoría que va antes en la lista.",
            ValueKind.CATEGORY),
    GENERIC("Palabras genéricas del alquiler",
            "Las escribe cualquiera que pague una unidad («trastero», «alquiler», «pasaxe»): una regla aprendida no puede estar hecha solo de ellas, porque valdría para todos.",
            ValueKind.NONE),
    NOISE("Palabras sin significado",
            "Jerga del banco y partículas («transferencia», «concepto», «de»): no dicen quién paga, así que no cuentan para reconocer nombres ni para aprender reglas. Los meses y las abreviaturas de número cuentan como tales sin repetirlos aquí.",
            ValueKind.NONE);

    /** Qué acompaña a cada palabra de la lista. */
    public enum ValueKind { NONE, MONTH, CATEGORY }

    private final String label;
    private final String description;
    private final ValueKind valueKind;

    BankVocabularyList(String label, String description, ValueKind valueKind) {
        this.label = label;
        this.description = description;
        this.valueKind = valueKind;
    }

    public String label() { return label; }
    public String description() { return description; }
    public ValueKind valueKind() { return valueKind; }
}
