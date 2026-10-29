package io.github.ngreiter.accounttransaction.account.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DocumentNormalizerTest {

    @Test
    void removesFormattingFromCpf() {
        assertEquals("52998224725", DocumentNormalizer.normalizeAndValidate("529.982.247-25"));
    }

    @Test
    void removesFormattingFromCnpj() {
        assertEquals("11222333000181", DocumentNormalizer.normalizeAndValidate("11.222.333/0001-81"));
    }

    @Test
    void rejectsNullAndMalformedDocuments() {
        assertThrows(IllegalArgumentException.class, () -> DocumentNormalizer.normalizeAndValidate(null));
        assertThrows(IllegalArgumentException.class, () -> DocumentNormalizer.normalizeAndValidate("12345"));
    }

    @Test
    void rejectsRepeatedAndInvalidCpfCheckDigits() {
        assertThrows(IllegalArgumentException.class, () -> DocumentNormalizer.normalizeAndValidate("11111111111"));
        assertThrows(IllegalArgumentException.class, () -> DocumentNormalizer.normalizeAndValidate("52998224724"));
    }

    @Test
    void rejectsRepeatedAndInvalidCnpjCheckDigits() {
        assertThrows(IllegalArgumentException.class, () -> DocumentNormalizer.normalizeAndValidate("11111111111111"));
        assertThrows(IllegalArgumentException.class, () -> DocumentNormalizer.normalizeAndValidate("11222333000180"));
    }
}
