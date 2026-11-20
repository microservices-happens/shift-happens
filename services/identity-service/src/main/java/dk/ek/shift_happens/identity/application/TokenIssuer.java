package dk.ek.shift_happens.identity.application;

import dk.ek.shift_happens.identity.domain.Account;

/** Port: issues the Shift Happens JWT for an account. Signing lives in infrastructure. */
public interface TokenIssuer {

    String issueFor(Account account);
}
