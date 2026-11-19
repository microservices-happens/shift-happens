package dk.ek.shift_happens.scheduling.api.graphql;

import graphql.language.StringValue;
import graphql.language.Value;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import graphql.schema.GraphQLScalarType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * The two custom scalars of contracts/graphql/scheduling.graphql.
 *
 * <p>Declaring {@code scalar DateTime} in the SDL makes graphql-java demand an implementation, so both are
 * wired here instead of pulling in an extra scalar library: ISO-8601 in, ISO-8601 out, and the same lenient
 * date parsing the resolvers use.
 */
@Configuration
@Profile("query")
class GraphQlScalarConfig {

    @Bean
    RuntimeWiringConfigurer schedulingScalars() {
        return wiring -> wiring.scalar(dateTime()).scalar(date());
    }

    private static GraphQLScalarType dateTime() {
        return GraphQLScalarType.newScalar()
                .name("DateTime")
                .description("ISO-8601 instant, e.g. 2026-10-01T08:00:00Z")
                .coercing(new Coercing<Instant, String>() {
                    @Override
                    public String serialize(Object value) {
                        if (value instanceof Instant instant) {
                            return instant.toString();
                        }
                        throw new CoercingSerializeException("a DateTime must be an Instant");
                    }

                    @Override
                    public Instant parseValue(Object input) {
                        try {
                            return GraphQlTime.instant(String.valueOf(input), "value");
                        } catch (IllegalArgumentException notAnInstant) {
                            throw new CoercingParseValueException(notAnInstant.getMessage());
                        }
                    }

                    @Override
                    public Instant parseLiteral(Object input) {
                        return parseValue(literal(input));
                    }

                    @Override
                    public Value<?> valueToLiteral(Object input) {
                        return StringValue.newStringValue(serialize(input)).build();
                    }
                })
                .build();
    }

    private static GraphQLScalarType date() {
        return GraphQLScalarType.newScalar()
                .name("Date")
                .description("ISO-8601 date, e.g. 2026-10-01")
                .coercing(new Coercing<LocalDate, String>() {
                    @Override
                    public String serialize(Object value) {
                        if (value instanceof LocalDate date) {
                            return date.toString();
                        }
                        throw new CoercingSerializeException("a Date must be a LocalDate");
                    }

                    @Override
                    public LocalDate parseValue(Object input) {
                        try {
                            return LocalDate.parse(String.valueOf(input));
                        } catch (DateTimeParseException notADate) {
                            throw new CoercingParseValueException("not an ISO-8601 date: " + input);
                        }
                    }

                    @Override
                    public LocalDate parseLiteral(Object input) {
                        return parseValue(literal(input));
                    }

                    @Override
                    public Value<?> valueToLiteral(Object input) {
                        return StringValue.newStringValue(serialize(input)).build();
                    }
                })
                .build();
    }

    private static String literal(Object input) {
        if (input instanceof StringValue stringValue) {
            return stringValue.getValue();
        }
        throw new CoercingParseLiteralException("a date literal must be a string");
    }
}
