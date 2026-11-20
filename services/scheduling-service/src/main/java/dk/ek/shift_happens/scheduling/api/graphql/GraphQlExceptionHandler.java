package dk.ek.shift_happens.scheduling.api.graphql;

import dk.ek.shift_happens.scheduling.application.ViewerNotAllowedException;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import org.springframework.context.annotation.Profile;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.stereotype.Component;

/**
 * Turns the two errors a client can cause into a classified GraphQL error instead of an internal one:
 * a forbidden visibility request (FORBIDDEN) and a bad argument (BAD_REQUEST).
 */
@Component
@Profile("query")
class GraphQlExceptionHandler extends DataFetcherExceptionResolverAdapter {

    @Override
    protected GraphQLError resolveToSingleError(Throwable exception, DataFetchingEnvironment environment) {
        if (exception instanceof ViewerNotAllowedException) {
            return error(ErrorType.FORBIDDEN, exception, environment);
        }
        if (exception instanceof IllegalArgumentException) {
            return error(ErrorType.BAD_REQUEST, exception, environment);
        }
        return null;
    }

    private static GraphQLError error(ErrorType type, Throwable exception, DataFetchingEnvironment environment) {
        return GraphqlErrorBuilder.newError(environment)
                .errorType(type)
                .message(exception.getMessage())
                .build();
    }
}
