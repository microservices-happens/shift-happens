package dk.ek.shift_happens.scheduling.infrastructure.persistence;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Stores a small list of ids as a comma-separated string. Used by the employee replica's job roles
 * and by the ids an answer to the leave saga released: both are only ever read as a whole list.
 */
@Converter
public class LongListConverter implements AttributeConverter<List<Long>, String> {

    @Override
    public String convertToDatabaseColumn(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
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
