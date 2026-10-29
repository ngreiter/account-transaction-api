package io.github.ngreiter.accounttransaction.transaction.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

import io.github.ngreiter.accounttransaction.transaction.application.DepositCommand;
import io.github.ngreiter.accounttransaction.transaction.application.DepositResult;
import io.github.ngreiter.accounttransaction.transaction.application.DepositService;
import io.github.ngreiter.accounttransaction.transaction.application.TransactionApplicationException;
import io.github.ngreiter.accounttransaction.transaction.application.TransactionError;
import io.github.ngreiter.accounttransaction.transaction.application.TransferCommand;
import io.github.ngreiter.accounttransaction.transaction.application.TransferResult;
import io.github.ngreiter.accounttransaction.transaction.application.TransferService;
import io.github.ngreiter.accounttransaction.transaction.application.WithdrawalCommand;
import io.github.ngreiter.accounttransaction.transaction.application.WithdrawalResult;
import io.github.ngreiter.accounttransaction.transaction.application.WithdrawalService;

@WebMvcTest(TransactionController.class)
class TransactionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DepositService depositService;

    @MockitoBean
    private WithdrawalService withdrawalService;

    @MockitoBean
    private TransferService transferService;

    @Test
    void depositsAndReturnsCreatedStatusAndTransactionFields() throws Exception {
        String body = "{\"financialTransactionId\":\"e4728a74-272b-4a45-8805-d314824a8453\","
                + "\"type\":\"DEPOSIT\",\"amount\":\"25.00\",\"status\":\"SUCCEEDED\","
                + "\"postedAt\":\"2026-10-28T12:00:00Z\"}";
        when(depositService.deposit(eq("deposit-1"), any(DepositCommand.class)))
                .thenReturn(DepositResult.succeeded(body));

        mockMvc.perform(post("/api/v1/transactions/deposits")
                .header("Idempotency-Key", "deposit-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountNumber\":\"12345678-9\",\"amount\":25.00,\"description\":\"cash in\"}"))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json(expectedJson("transaction-deposit.json"), JsonCompareMode.STRICT));

        verify(depositService).deposit(eq("deposit-1"), any(DepositCommand.class));
    }

    @Test
    void returnsStoredStatusWhenDepositIsReplayed() throws Exception {
        when(depositService.deposit(eq("deposit-replay"), any(DepositCommand.class)))
                .thenReturn(DepositResult.replayed(201, "{\"type\":\"DEPOSIT\",\"status\":\"SUCCEEDED\"}"));

        mockMvc.perform(post("/api/v1/transactions/deposits")
                .header("Idempotency-Key", "deposit-replay")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountNumber\":\"12345678-9\",\"amount\":1.00}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    }

    @Test
    void withdrawsAndReturnsCreatedStatusAndTransactionFields() throws Exception {
        String body = "{\"financialTransactionId\":\"e4728a74-272b-4a45-8805-d314824a8453\","
                + "\"type\":\"WITHDRAWAL\",\"amount\":\"10.00\",\"status\":\"SUCCEEDED\","
                + "\"postedAt\":\"2026-10-28T12:00:00Z\"}";
        when(withdrawalService.withdraw(eq("withdraw-1"), any(WithdrawalCommand.class)))
                .thenReturn(WithdrawalResult.succeeded(body));

        mockMvc.perform(post("/api/v1/transactions/withdrawals")
                .header("Idempotency-Key", "withdraw-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountNumber\":\"12345678-9\",\"amount\":10.00}"))
                .andExpect(status().isCreated())
                .andExpect(content().json(expectedJson("transaction-withdrawal.json"), JsonCompareMode.STRICT));
    }

    @Test
    void transfersAndReturnsCreatedStatusAndTransactionFields() throws Exception {
        String body = "{\"financialTransactionId\":\"e4728a74-272b-4a45-8805-d314824a8453\","
                + "\"type\":\"TRANSFER\",\"amount\":\"7.50\",\"status\":\"SUCCEEDED\","
                + "\"postedAt\":\"2026-10-28T12:00:00Z\"}";
        when(transferService.transfer(eq("transfer-1"), any(TransferCommand.class)))
                .thenReturn(TransferResult.succeeded(body));

        mockMvc.perform(post("/api/v1/transactions/transfers")
                .header("Idempotency-Key", "transfer-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceAccountNumber\":\"12345678-9\","
                        + "\"destinationAccountNumber\":\"87654321-2\",\"amount\":7.50}"))
                .andExpect(status().isCreated())
                .andExpect(content().json(expectedJson("transaction-transfer.json"), JsonCompareMode.STRICT));
    }

    @Test
    void returnsNotFoundWhenDepositAccountDoesNotExist() throws Exception {
        when(depositService.deposit(eq("deposit-missing"), any(DepositCommand.class)))
                .thenThrow(new TransactionApplicationException(TransactionError.RESOURCE_NOT_FOUND,
                        "{\"code\":\"RESOURCE_NOT_FOUND\"}"));

        mockMvc.perform(post("/api/v1/transactions/deposits")
                .header("Idempotency-Key", "deposit-missing")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountNumber\":\"12345678-9\",\"amount\":1.00}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void returnsUnprocessableContentWhenWithdrawalHasInsufficientFunds() throws Exception {
        when(withdrawalService.withdraw(eq("withdraw-low"), any(WithdrawalCommand.class)))
                .thenThrow(new TransactionApplicationException(TransactionError.INSUFFICIENT_FUNDS,
                        "{\"code\":\"INSUFFICIENT_FUNDS\"}"));

        mockMvc.perform(post("/api/v1/transactions/withdrawals")
                .header("Idempotency-Key", "withdraw-low")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountNumber\":\"12345678-9\",\"amount\":100.00}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
    }

    @Test
    void returnsConflictWhenTransferIdempotencyKeyIsReused() throws Exception {
        when(transferService.transfer(eq("transfer-conflict"), any(TransferCommand.class)))
                .thenThrow(new TransactionApplicationException(TransactionError.IDEMPOTENCY_CONFLICT,
                        "{\"code\":\"IDEMPOTENCY_CONFLICT\"}"));

        mockMvc.perform(post("/api/v1/transactions/transfers")
                .header("Idempotency-Key", "transfer-conflict")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceAccountNumber\":\"12345678-9\","
                        + "\"destinationAccountNumber\":\"87654321-2\",\"amount\":7.50}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void returnsBadRequestForInvalidTransactionBody() throws Exception {
        mockMvc.perform(post("/api/v1/transactions/deposits")
                .header("Idempotency-Key", "invalid-deposit")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountNumber\":\"12345678-9\",\"amount\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(depositService, never()).deposit(eq("invalid-deposit"), any(DepositCommand.class));
    }

    private String expectedJson(String fileName) throws IOException {
        try (InputStream fixture = getClass().getResourceAsStream("/json/transaction/" + fileName)) {
            if (fixture == null) {
                throw new IllegalStateException("JSON fixture not found: " + fileName);
            }
            return new String(fixture.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
