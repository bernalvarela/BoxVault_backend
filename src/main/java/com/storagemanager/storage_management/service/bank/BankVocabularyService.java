package com.storagemanager.storage_management.service.bank;

import com.storagemanager.storage_management.dto.BankDTOs.VocabularyEntryDTO;
import com.storagemanager.storage_management.dto.BankDTOs.VocabularyListDTO;
import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.model.BankVocabularyEntry;
import com.storagemanager.storage_management.model.enums.BankVocabularyList;
import com.storagemanager.storage_management.model.enums.ExpenseCategory;
import com.storagemanager.storage_management.repository.BankVocabularyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * El vocabulario del banco guardado en la base: se carga al arrancar, se edita
 * lista a lista desde la aplicación y, en cuanto se guarda, pasa a ser el que
 * usa {@link TextMatch}. Sin recompilar ni reiniciar.
 * <p>
 * Una lista sin ninguna palabra en la base se rellena con la de fábrica: así
 * la primera vez sale entera, y una lista que aparezca en una versión nueva
 * también. Por eso no se deja guardar una lista vacía.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BankVocabularyService {

    private final BankVocabularyRepository repository;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void loadOnStartup() {
        for (BankVocabularyList list : BankVocabularyList.values()) {
            if (!repository.existsByListKey(list)) {
                write(list, Vocabulary.DEFAULTS.getOrDefault(list, List.of()));
            }
        }
        reload();
        log.info("Vocabulario del banco cargado");
    }

    @Transactional(readOnly = true)
    public List<VocabularyListDTO> lists() {
        Map<BankVocabularyList, List<Vocabulary.Entry>> stored = stored();
        return Arrays.stream(BankVocabularyList.values()).map(list -> {
            List<Vocabulary.Entry> entries = stored.getOrDefault(list, List.of());
            return VocabularyListDTO.builder()
                    .key(list)
                    .label(list.label())
                    .description(list.description())
                    .valueKind(list.valueKind())
                    .entries(entries.stream().map(e -> new VocabularyEntryDTO(e.word(), e.value())).toList())
                    .factoryDefault(Set.copyOf(entries).equals(Set.copyOf(Vocabulary.DEFAULTS.getOrDefault(list, List.of()))))
                    .build();
        }).toList();
    }

    /** Sustituye la lista entera por estas palabras, normalizadas y sin repetir. */
    @Transactional
    public List<VocabularyListDTO> save(BankVocabularyList list, List<VocabularyEntryDTO> requested) {
        Set<Vocabulary.Entry> clean = new LinkedHashSet<>();
        for (VocabularyEntryDTO dto : requested) {
            String word = TextMatch.normalize(dto.getWord());
            if (word.isEmpty()) continue;
            if (word.length() > 80) throw new BadRequestException("«" + dto.getWord() + "» es demasiado larga");
            clean.add(new Vocabulary.Entry(word, checkedValue(list, word, dto.getValue())));
        }
        if (clean.isEmpty()) {
            throw new BadRequestException("Una lista no puede quedar vacía: restaura la de fábrica si hace falta");
        }
        repository.deleteByList(list);
        write(list, new ArrayList<>(clean));
        reload();
        log.info("Vocabulario del banco: «{}» guardada con {} palabras", list.label(), clean.size());
        return lists();
    }

    /** Vuelve a dejar la lista como venía de fábrica. */
    @Transactional
    public List<VocabularyListDTO> reset(BankVocabularyList list) {
        repository.deleteByList(list);
        write(list, Vocabulary.DEFAULTS.getOrDefault(list, List.of()));
        reload();
        log.info("Vocabulario del banco: «{}» restaurada", list.label());
        return lists();
    }

    // ------------------------------------------------------------------------

    /** El mes (1 a 12) o la categoría de gasto que exige la lista; nada en las demás. */
    private static String checkedValue(BankVocabularyList list, String word, String value) {
        String v = value == null ? "" : value.trim();
        return switch (list.valueKind()) {
            case NONE -> null;
            case MONTH -> {
                try {
                    int month = Integer.parseInt(v);
                    if (month < 1 || month > 12) throw new NumberFormatException();
                    yield String.valueOf(month);
                } catch (NumberFormatException e) {
                    throw new BadRequestException("«" + word.toLowerCase() + "»: elige el mes al que corresponde");
                }
            }
            case CATEGORY -> {
                try {
                    yield ExpenseCategory.valueOf(v).name();
                } catch (IllegalArgumentException e) {
                    throw new BadRequestException("«" + word.toLowerCase() + "»: elige la categoría de gasto");
                }
            }
        };
    }

    private void write(BankVocabularyList list, List<Vocabulary.Entry> entries) {
        List<BankVocabularyEntry> rows = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            Vocabulary.Entry e = entries.get(i);
            rows.add(BankVocabularyEntry.builder().listKey(list).word(e.word()).value(e.value()).sortOrder(i).build());
        }
        repository.saveAll(rows);
    }

    private Map<BankVocabularyList, List<Vocabulary.Entry>> stored() {
        Map<BankVocabularyList, List<Vocabulary.Entry>> byList = new EnumMap<>(BankVocabularyList.class);
        for (BankVocabularyEntry row : repository.findAllByOrderByListKeyAscSortOrderAsc()) {
            if (row.getListKey() == null || row.getWord() == null) continue;
            byList.computeIfAbsent(row.getListKey(), k -> new ArrayList<>())
                    .add(new Vocabulary.Entry(row.getWord(), row.getValue()));
        }
        return byList;
    }

    private void reload() {
        repository.flush();
        TextMatch.use(Vocabulary.of(stored()));
    }
}
