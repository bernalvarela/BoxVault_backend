package com.storagemanager.storage_management.dto;

import com.storagemanager.storage_management.model.enums.BankLineAction;
import com.storagemanager.storage_management.model.enums.BankLineStatus;
import com.storagemanager.storage_management.model.enums.BankProfileContext;
import com.storagemanager.storage_management.model.enums.BankVocabularyList;
import com.storagemanager.storage_management.model.enums.CommunityEntryType;
import com.storagemanager.storage_management.model.enums.ExpenseCategory;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Lo que va y viene en la importación de extractos bancarios. */
public final class BankDTOs {

    private BankDTOs() {}

    // ------------------------------------------------------------- Perfiles

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProfileDTO {
        private Long id;
        private String name;
        private String bankName;
        private String accountLabel;
        private BankProfileContext context;
        private Long communityId;
        private String communityName;
        private Long defaultUnitId;
        private String defaultUnitName;
        private String encoding;
        private Integer headerRow;
        private Integer dateColumn;
        private Integer valueDateColumn;
        private Integer conceptColumn;
        private Integer conceptExtraColumn;
        private Integer amountColumn;
        private Integer debitColumn;
        private Integer creditColumn;
        private Integer balanceColumn;
        private Integer referenceColumn;
        private String dateFormat;
        private Boolean decimalComma;
    }

    @Data
    public static class ProfileRequest {
        @NotBlank(message = "Ponle un nombre al perfil: «BBVA pisos», «Abanca comunidad»...")
        @Size(max = 120)
        private String name;
        @Size(max = 80)
        private String bankName;
        @Size(max = 120)
        private String accountLabel;
        @NotNull(message = "Indica de quién es la cuenta: de los propietarios o de la comunidad")
        private BankProfileContext context;
        private Long communityId;
        private Long defaultUnitId;
        @Size(max = 20)
        private String encoding;
        @Min(0) @Max(100)
        private Integer headerRow;
        @NotNull(message = "Falta la columna de la fecha")
        @Min(0) @Max(200)
        private Integer dateColumn;
        @Min(0) @Max(200)
        private Integer valueDateColumn;
        @NotNull(message = "Falta la columna del concepto")
        @Min(0) @Max(200)
        private Integer conceptColumn;
        @Min(0) @Max(200)
        private Integer conceptExtraColumn;
        @Min(0) @Max(200)
        private Integer amountColumn;
        @Min(0) @Max(200)
        private Integer debitColumn;
        @Min(0) @Max(200)
        private Integer creditColumn;
        @Min(0) @Max(200)
        private Integer balanceColumn;
        /** El identificador único del movimiento (la Remesa de BBVA), si lo hay. */
        @Min(0) @Max(200)
        private Integer referenceColumn;
        @Size(max = 30)
        private String dateFormat;
        private Boolean decimalComma;
    }

    // ------------------------------------------------------------- Importar

    /**
     * El extracto ya leído en el navegador: las filas tal cual, cada celda como
     * texto (las fechas de Excel llegan como yyyy-mm-dd y los números con punto
     * decimal). El perfil dice qué columna es cada cosa.
     */
    @Data
    public static class ImportRequest {
        @NotNull(message = "Elige el perfil de la cuenta")
        private Long profileId;
        @Size(max = 255)
        private String fileName;
        @NotEmpty(message = "El fichero no tiene filas")
        @Size(max = 5000, message = "Un extracto de más de 5.000 filas: pártelo en varios")
        private List<List<String>> rows;
    }

    /** Un extracto subido, sin sus movimientos: para la lista. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImportSummaryDTO {
        private Long id;
        private Long profileId;
        private String profileName;
        private String fileName;
        private String status;
        private LocalDateTime createdAt;
        private String createdBy;
        private LocalDate firstDate;
        private LocalDate lastDate;
        private long total;
        private long pending;
        private long discarded;
        private long applied;
        private long failed;
        /** Pendientes a los que les falta algo para poder aplicarse. */
        private long incomplete;
        /** Ingresos que ya estaban apuntados a mano como cobro. */
        private long alreadyRecorded;
        /** Filas que ya estaban en otro extracto. */
        private long duplicates;
    }

    /** Un extracto con sus movimientos: la pantalla de revisión. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImportDTO {
        private ImportSummaryDTO summary;
        private ProfileDTO profile;
        private List<LineDTO> lines;
        /** Filas del fichero que no se pudieron leer, y por qué. Solo al subirlo. */
        private List<String> warnings;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LineDTO {
        private Long id;
        private Integer lineNumber;
        private LocalDate date;
        private LocalDate valueDate;
        private String concept;
        private BigDecimal amount;
        private BigDecimal balance;
        private boolean duplicate;
        /** El ingreso ya estaba apuntado a mano como cobro ({@code paymentId}): se ignora. */
        private boolean alreadyRecorded;
        private BankLineStatus status;
        private BankLineAction action;
        private String reason;
        private Long rentalAgreementId;
        private String rentalLabel;
        private Integer periodYear;
        private Integer periodMonth;
        /** Cuántas mensualidades seguidas cubre el cobro. */
        private Integer periodCount;
        private String bankReference;
        private ExpenseCategory expenseCategory;
        private Long storageUnitId;
        private String unitLabel;
        /** El gasto repartido entre varias unidades, si se reparte. */
        private List<SplitDTO> splits;
        private CommunityEntryType communityEntryType;
        private boolean learn;
        private Long paymentId;
        private Long expenseId;
        private Long communityEntryId;
        private String error;
        /** Si tiene todo lo que hace falta para aplicarse. */
        private boolean complete;
        /** Lo que va a crear, en una frase: "Cobro de octubre de 2026 · Trastero 3 · Ana Gómez". */
        private String outcome;
    }

    /**
     * Lo que cambia quien revisa en un movimiento. Se manda entero: lo que no
     * corresponda a la acción elegida se ignora.
     */
    @Data
    public static class LineUpdateRequest {
        /** PENDING para que se aplique, DISCARDED para descartarlo. */
        private BankLineStatus status;
        private BankLineAction action;
        private Long rentalAgreementId;
        private Integer periodYear;
        private Integer periodMonth;
        /** Cuántas mensualidades seguidas, desde ese mes. */
        private Integer periodCount;
        private ExpenseCategory expenseCategory;
        private Long storageUnitId;
        /** Para repartir un gasto entre varias unidades: dos partes o más que sumen el cargo. */
        private List<SplitDTO> splits;
        private CommunityEntryType communityEntryType;
        /** Recordarlo para los próximos extractos. Por defecto, sí. */
        private Boolean learn;
    }

    /** Lo que pasó al aplicar un extracto. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApplyResultDTO {
        private long payments;
        private long expenses;
        private long communityEntries;
        private long discarded;
        private long failed;
        private long skippedIncomplete;
        private long rulesLearned;
        private ImportDTO bankImport;
    }

    // ------------------------------------------------------------- Reglas

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RuleDTO {
        private Long id;
        private String pattern;
        private BankLineAction action;
        private Long clientId;
        private String clientName;
        private ExpenseCategory expenseCategory;
        private Long storageUnitId;
        private String unitLabel;
        private CommunityEntryType communityEntryType;
        /** "3º E 37,5 % · 3º I 37,5 % · Bajo 25 %", si la regla reparte. */
        private String splitsText;
        private LocalDateTime createdAt;
        private String createdBy;
    }

    /** La parte de un gasto que es de una unidad. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SplitDTO {
        private Long storageUnitId;
        private String unitLabel;
        private BigDecimal amount;
    }

    // ------------------------------------------------------------- Vocabulario

    /** Una palabra de una lista, con su mes o su categoría si la lista los lleva. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VocabularyEntryDTO {
        @NotBlank
        @Size(max = 80)
        private String word;
        @Size(max = 30)
        private String value;
    }

    /** Una lista del vocabulario, para verla y editarla. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VocabularyListDTO {
        private BankVocabularyList key;
        private String label;
        private String description;
        /** NONE, MONTH o CATEGORY: qué acompaña a cada palabra. */
        private BankVocabularyList.ValueKind valueKind;
        private List<VocabularyEntryDTO> entries;
        /** Si la lista es la de fábrica, sin cambios. */
        private boolean factoryDefault;
    }

    /** Las palabras con que queda una lista: la sustituyen entera. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VocabularySaveRequest {
        @NotEmpty(message = "Una lista no puede quedar vacía: restaura la de fábrica si hace falta")
        @Size(max = 500)
        private List<@jakarta.validation.Valid VocabularyEntryDTO> entries;
    }
}
