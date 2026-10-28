package io.github.ngreiter.accounttransaction.account.application;

import java.util.Locale;

final class DocumentNormalizer {

    private DocumentNormalizer() {
    }

    static String normalizeAndValidate(String input) {
        if (input == null) {
            throw invalidDocument();
        }
        String normalized = input.toUpperCase(Locale.ROOT).replaceAll("[./\\-]", "");
        if (normalized.matches("\\d{11}")) {
            validateCpf(normalized);
            return normalized;
        }
        if (normalized.matches("[A-Z0-9]{12}\\d{2}")) {
            validateCnpj(normalized);
            return normalized;
        }
        throw invalidDocument();
    }

    private static void validateCpf(String cpf) {
        if (cpf.chars().distinct().count() == 1) {
            throw invalidDocument();
        }
        int first = cpfDigit(cpf.substring(0, 9), 10);
        int second = cpfDigit(cpf.substring(0, 10), 11);
        if (cpf.charAt(9) - '0' != first || cpf.charAt(10) - '0' != second) {
            throw invalidDocument();
        }
    }

    private static int cpfDigit(String digits, int initialWeight) {
        int sum = 0;
        for (int i = 0; i < digits.length(); i++) {
            sum += (digits.charAt(i) - '0') * (initialWeight - i);
        }
        int remainder = sum % 11;
        return remainder < 2 ? 0 : 11 - remainder;
    }

    private static void validateCnpj(String cnpj) {
        if (cnpj.chars().distinct().count() == 1) {
            throw invalidDocument();
        }
        int first = cnpjDigit(cnpj.substring(0, 12), new int[] {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2});
        int second = cnpjDigit(cnpj.substring(0, 12) + first,
                new int[] {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2});
        if (cnpj.charAt(12) - '0' != first || cnpj.charAt(13) - '0' != second) {
            throw invalidDocument();
        }
    }

    private static int cnpjDigit(String base, int[] weights) {
        int sum = 0;
        for (int i = 0; i < weights.length; i++) {
            int value = base.charAt(i) - '0';
            if (Character.isLetter(base.charAt(i))) {
                value = base.charAt(i) - 'A' + 17;
            }
            sum += value * weights[i];
        }
        int remainder = sum % 11;
        return remainder < 2 ? 0 : 11 - remainder;
    }

    private static IllegalArgumentException invalidDocument() {
        return new IllegalArgumentException("Documento inválido.");
    }
}
