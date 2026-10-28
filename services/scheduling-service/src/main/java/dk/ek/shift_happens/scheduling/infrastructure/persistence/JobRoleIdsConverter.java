package dk.ek.shift_happens.scheduling.infrastructure.persistence;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Stores the replica's job role ids as a comma-separated string: the replica only needs membership
 * tests, and this avoids a second table (or a Postgres array mapping) for a copy of Workforce's data.
 */
@Converter
public class JobRoleIdsConverter implements AttributeConverter<List<Long>, String> {

    @Override
    public String convertToDatabaseColumn(List<Long> jobRoleIds) {
        if (jobRoleIds == null || jobRoleIds.isEmpty()) {
            return "";
        }
        return jobRoleIds.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    @Override
    public List<Long> convertToEntityAttribute(String column) {
        if (column == null || column.isBlank()) {
            return List.of();
        }
        return Arrays.stream(column.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(Long::valueOf)
                .toList();
    }
}
