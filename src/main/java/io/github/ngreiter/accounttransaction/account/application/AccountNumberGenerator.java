package io.github.ngreiter.accounttransaction.account.application;

import java.security.SecureRandom;
import java.util.Locale;

import org.springframework.stereotype.Component;

@Component
final class AccountNumberGenerator {

    private final SecureRandom random = new SecureRandom();

    String next() {
        String base = String.format(Locale.ROOT, "%08d", random.nextInt(100_000_000));
        int sum = 0;
        int weight = 2;
        for (int i = base.length() - 1; i >= 0; i--) {
            sum += (base.charAt(i) - '0') * weight;
            weight = weight == 9 ? 2 : weight + 1;
        }
        int digit = 11 - sum % 11;
        if (digit >= 10) {
            digit = 0;
        }
        return base + "-" + digit;
    }
}
