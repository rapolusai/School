package com.akshara.transport;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.akshara.fees.FeeDuesService;
import com.akshara.fees.FeeHeadKind;
import com.akshara.fees.StudentChargeSource;

/**
 * The bus fee in a student's dues. For each instalment of the student's fee structure, the allocation that covers the
 * instalment's due date decides the fee: its stop's zone's annual fee split evenly over the structure's instalments
 * (the last one takes the rounding). Instalments due before the student started on the bus or after they left are not
 * charged. The amount goes under the school's first active Transport fee head, so it is collected, receipted and paid
 * online with the rest of the fees.
 *
 * <p>After an allocation or a zone's fee changes, {@link #refresh} re-prices the student's dues: unpaid transport dues
 * that no longer apply are removed, paid ones are kept.
 */
@Component
class TransportCharges implements StudentChargeSource {

    private final AllocationRepository allocations;
    private final StopRepository stops;
    private final FeeZoneRepository zones;
    private final FeeDuesService feeDues;

    TransportCharges(AllocationRepository allocations, StopRepository stops, FeeZoneRepository zones,
            FeeDuesService feeDues) {
        this.allocations = allocations;
        this.stops = stops;
        this.zones = zones;
        this.feeDues = feeDues;
    }

    @Override
    public List<StudentCharge> chargesFor(UUID studentId, UUID academicYearId, List<ChargeInstalment> instalments) {
        List<Allocation> list = allocations.findByStudentIdOrderByStartDateDesc(studentId);
        if (list.isEmpty() || instalments.isEmpty()) {
            return List.of();
        }
        Map<UUID, Stop> stopById = stops.findByIdIn(list.stream().map(Allocation::getStopId)
                .filter(id -> id != null).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(Stop::getId, Function.identity()));
        Map<UUID, FeeZone> zoneById = zones.findAll().stream()
                .collect(Collectors.toMap(FeeZone::getId, Function.identity()));
        List<StudentCharge> result = new ArrayList<>();
        for (int i = 0; i < instalments.size(); i++) {
            ChargeInstalment instalment = instalments.get(i);
            Optional<Allocation> covering = TransportRules.covering(list, Allocation::period, instalment.dueDate());
            Optional<FeeZone> zone = covering.map(a -> stopById.get(a.getStopId()))
                    .map(s -> s.getFeeZoneId() == null ? null : zoneById.get(s.getFeeZoneId()));
            if (zone.isEmpty()) {
                continue;
            }
            long amount = TransportRules.instalmentCharge(zone.get().getAnnualFeePaise(), i, instalments.size());
            if (amount > 0) {
                result.add(new StudentCharge(instalment.id(), FeeHeadKind.TRANSPORT, amount));
            }
        }
        return result;
    }

    /** Re-prices the current-year dues of these students (in id order, so two requests never deadlock). */
    void refresh(Collection<UUID> studentIds) {
        for (UUID studentId : new TreeSet<>(studentIds)) {
            feeDues.refreshStudentCharges(studentId);
        }
    }
}
