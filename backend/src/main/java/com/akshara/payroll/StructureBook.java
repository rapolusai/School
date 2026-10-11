package com.akshara.payroll;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.payroll.PayslipCalculator.ComponentLine;

/** Loads salary structures with their lines and components, in as few queries as possible. */
@Component
@Transactional(readOnly = true)
class StructureBook {

    /** A structure and its lines with the components' flags. */
    record Loaded(SalaryStructure structure, List<ComponentLine> lines) {

        LocalDate from() {
            return structure.getEffectiveFrom();
        }

        long gross() {
            return lines.stream().filter(l -> l.kind() == ComponentKind.EARNING).mapToLong(ComponentLine::monthlyPaise)
                    .sum();
        }

        long fixedDeductions() {
            return lines.stream().filter(l -> l.kind() == ComponentKind.DEDUCTION)
                    .mapToLong(ComponentLine::monthlyPaise).sum();
        }

        /** Full monthly ESI wages: what decides ESI coverage. */
        long esiWages() {
            return lines.stream().filter(ComponentLine::esiWage).mapToLong(ComponentLine::monthlyPaise).sum();
        }
    }

    private final SalaryStructureRepository structures;
    private final SalaryStructureLineRepository lines;
    private final SalaryComponentRepository components;

    StructureBook(SalaryStructureRepository structures, SalaryStructureLineRepository lines,
            SalaryComponentRepository components) {
        this.structures = structures;
        this.lines = lines;
        this.components = components;
    }

    /** Every structure of the school by person, oldest first. */
    Map<UUID, List<Loaded>> all() {
        return load(structures.findAllOrdered()).stream()
                .collect(Collectors.groupingBy(l -> l.structure().getUserId(), LinkedHashMap::new,
                        Collectors.toList()));
    }

    /** One person's structures, oldest first. */
    List<Loaded> of(UUID userId) {
        return load(structures.findByUser(userId));
    }

    /** The structure in force on the day: the latest that starts on or before it. */
    static Optional<Loaded> inForce(List<Loaded> structures, LocalDate day) {
        if (structures == null) {
            return Optional.empty();
        }
        return structures.stream().filter(s -> !s.from().isAfter(day)).max(Comparator.comparing(Loaded::from));
    }

    private List<Loaded> load(List<SalaryStructure> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<UUID, SalaryComponent> byId = components.findAll().stream()
                .collect(Collectors.toMap(SalaryComponent::getId, Function.identity()));
        Collection<UUID> ids = list.stream().map(SalaryStructure::getId).toList();
        Map<UUID, List<ComponentLine>> linesByStructure = new HashMap<>();
        for (SalaryStructureLine l : lines.findByStructures(ids)) {
            SalaryComponent c = byId.get(l.getComponentId());
            if (c == null) {
                continue;
            }
            linesByStructure.computeIfAbsent(l.getStructureId(), k -> new ArrayList<>())
                    .add(new ComponentLine(c.getId(), c.getCode(), c.getName(), c.getKind(), l.getMonthlyPaise(),
                            c.isPfWage(), c.isEsiWage(), c.isTaxable(), c.isProrated(), c.getDisplayOrder()));
        }
        return list.stream().map(s -> new Loaded(s, linesByStructure.getOrDefault(s.getId(), List.of()).stream()
                .sorted(Comparator.comparing(ComponentLine::kind).thenComparingInt(ComponentLine::displayOrder)
                        .thenComparing(ComponentLine::name))
                .toList())).toList();
    }
}
