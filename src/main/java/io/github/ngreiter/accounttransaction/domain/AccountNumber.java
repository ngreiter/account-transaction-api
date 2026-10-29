package io.github.ngreiter.accounttransaction.domain;

import java.security.SecureRandom;
import java.util.Locale;

public final class AccountNumber {

    private static final SecureRandom RANDOM = new SecureRandom();

    private AccountNumber() {
    }

    public static String generate() {
        String base = String.format(Locale.ROOT, "%08d", RANDOM.nextInt(100_000_000));
        return base + "-" + checkDigit(base);
    }

    public static boolean isValid(String accountNumber) {
        if (accountNumber == null || !accountNumber.matches("[0-9]{8}-[0-9]")) {
            return false;
        }
        String base = accountNumber.substring(0, 8);
        return accountNumber.charAt(9) - '0' == checkDigit(base);
    }

    private static int checkDigit(String base) {
        int sum = 0;
        int weight = 2;
        for (int i = base.length() - 1; i >= 0; i--) {
            sum += (base.charAt(i) - '0') * weight;
            weight = weight == 9 ? 2 : weight + 1;
        }
        int digit = 11 - sum % 11;
        return digit >= 10 ? 0 : digit;
    }
}
