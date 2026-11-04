package dk.ek.shift_happens.identity.api;

import com.nimbusds.jose.jwk.JWKSet;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** GET /.well-known/jwks.json: the public key every service uses to verify the JWT. */
@RestController
class JwksController {

    private final JWKSet publicJwkSet;

    JwksController(JWKSet publicJwkSet) {
        this.publicJwkSet = publicJwkSet;
    }

    @GetMapping("/.well-known/jwks.json")
    Map<String, Object> jwks() {
        return publicJwkSet.toJSONObject(true);
    }
}
