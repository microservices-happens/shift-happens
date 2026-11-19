package dk.ek.shift_happens.scheduling.api.graphql;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/**
 * The {@code DateTime} scalar of contracts/graphql/scheduling.graphql, parsed explicitly so the service
 * accepts the three forms a client actually sends: an instant, an offset date-time and a bare date.
 */
final class GraphQlTime {

    private GraphQlTime() {
    }

    static Instant instant(String value, String argument) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(argument + " is required");
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException notAnInstant) {
            try {
                return OffsetDateTime.parse(value).toInstant();
            } catch (DateTimeParseException notAnOffset) {
                try {
                    return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant();
                } catch (DateTimeParseException notADate) {
                    throw new IllegalArgumentException(argument + " is not an ISO-8601 date: " + value);
                }
            }
        }
    }
}
