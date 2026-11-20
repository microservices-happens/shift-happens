package dk.ek.shift_happens.scheduling.api.graphql;

import dk.ek.shift_happens.scheduling.application.Viewer;
import dk.ek.shift_happens.scheduling.application.ViewerNotAllowedException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * Builds the {@link Viewer} from the validated JWT (contracts/README.md: claims {@code employeeId} and
 * {@code role}). The token is already verified by the resource server, so this is only claim extraction.
 */
public final class Viewers {

    private Viewers() {
    }

    public static Viewer of(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken token) {
            Object claim = token.getToken().getClaim("employeeId");
            Long employeeId = claim instanceof Number number ? number.longValue() : null;
            return new Viewer(employeeId, token.getToken().getClaimAsString("role"));
        }
        throw new ViewerNotAllowedException("a Shift Happens JWT with an employeeId claim is required");
    }
}
