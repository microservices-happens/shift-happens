package dk.ek.shift_happens.identity.api;

import dk.ek.shift_happens.identity.application.OidcLoginException;
import dk.ek.shift_happens.identity.application.OidcLoginService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * GET /v1/auth/oidc/{provider}/authorize and /callback (contracts/openapi/identity.yaml).
 *
 * <p>The callback is opened by the browser, so it always answers with a redirect to the frontend:
 * {@code #token=<jwt>} on success, {@code #error=<code>} otherwise, never an error page. The token is
 * in the fragment, which browsers do not send to servers or put in Referer headers.
 */
@RestController
@RequestMapping("/v1/auth/oidc/{provider}")
class OidcController {

    private static final Logger log = LoggerFactory.getLogger(OidcController.class);

    private final OidcLoginService oidcLogin;
    private final String frontendCallbackUrl;

    OidcController(OidcLoginService oidcLogin, @Value("${app.oidc.frontend-callback-url}") String frontendCallbackUrl) {
        this.oidcLogin = oidcLogin;
        this.frontendCallbackUrl = frontendCallbackUrl;
    }

    @GetMapping("/authorize")
    ResponseEntity<Void> authorize(@PathVariable String provider) {
        return redirect(oidcLogin.authorize(provider));
    }

    @GetMapping("/callback")
    ResponseEntity<Void> callback(@PathVariable String provider,
                                  @RequestParam(required = false) String code,
                                  @RequestParam(required = false) String state,
                                  @RequestParam(required = false) String error) {
        String fragment;
        try {
            fragment = "token=" + oidcLogin.complete(provider, code, state, error);
        } catch (OidcLoginException e) {
            fragment = "error=" + e.code();
        } catch (RuntimeException e) {
            log.error("OIDC callback failed unexpectedly", e);
            fragment = "error=server_error";
        }
        return redirect(URI.create(frontendCallbackUrl + "#" + fragment));
    }

    private static ResponseEntity<Void> redirect(URI location) {
        return ResponseEntity.status(HttpStatus.FOUND).location(location).cacheControl(CacheControl.noStore()).build();
    }
}
