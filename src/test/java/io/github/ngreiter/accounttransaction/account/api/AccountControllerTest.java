package io.github.ngreiter.accounttransaction.account.api;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

import io.github.ngreiter.accounttransaction.account.application.AccountCreationError;
import io.github.ngreiter.accounttransaction.account.application.AccountCreationException;
import io.github.ngreiter.accounttransaction.account.application.AccountCreationResult;
import io.github.ngreiter.accounttransaction.account.application.AccountCreationService;
import io.github.ngreiter.accounttransaction.account.application.AccountNotFoundException;
import io.github.ngreiter.accounttransaction.account.application.AccountQueryService;
import io.github.ngreiter.accounttransaction.domain.MovementType;
import io.github.ngreiter.accounttransaction.domain.TransactionType;
import io.github.ngreiter.accounttransaction.statement.api.StatementEntryResponse;
import io.github.ngreiter.accounttransaction.statement.api.StatementResponse;
import io.github.ngreiter.accounttransaction.statement.application.AccountStatementService;

@WebMvcTest(AccountController.class)
class AccountControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccountCreationService accountCreationService;

    @MockitoBean
    private AccountQueryService accountQueryService;

    @MockitoBean
    private AccountStatementService accountStatementService;

    @Test
    void createsAccountAndReturnsCreatedStatusAndResponseBody() throws Exception {
        String body = "{\"accountNumber\":\"12345678-9\",\"balance\":\"0.00\",\"createdAt\":\"2026-10-28T12:00:00Z\"}";
        when(accountCreationService.create("request-1", "52998224725"))
                .thenReturn(AccountCreationResult.succeeded(body));

        mockMvc.perform(post("/api/v1/accounts")
                .header("Idempotency-Key", "request-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"document\":\"52998224725\"}"))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json(expectedJson("account-created.json"), JsonCompareMode.STRICT));
    }

    @Test
    void returnsConflictForDuplicateDocument() throws Exception {
        when(accountCreationService.create("duplicate", "52998224725"))
                .thenThrow(new AccountCreationException(AccountCreationError.ACCOUNT_CONFLICT,
                        "{\"code\":\"ACCOUNT_CONFLICT\"}"));

        mockMvc.perform(post("/api/v1/accounts")
                .header("Idempotency-Key", "duplicate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"document\":\"52998224725\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_CONFLICT"));
    }

    @Test
    void returnsBadRequestForInvalidCreationBody() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"document\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(accountCreationService, never()).create(eq(null), eq(""));
    }

    @Test
    void returnsAccountDetailsForExistingAccount() throws Exception {
        when(accountQueryService.getByAccountNumber("12345678-9"))
                .thenReturn(new AccountDetailsResponse("12345678-9", "***.***.***-25", new BigDecimal("42.50")));

        mockMvc.perform(get("/api/v1/accounts/12345678-9"))
                .andExpect(status().isOk())
                .andExpect(content().json(expectedJson("account-details.json"), JsonCompareMode.STRICT));
    }

    @Test
    void returnsNotFoundForMissingAccountDetails() throws Exception {
        when(accountQueryService.getByAccountNumber("12345678-9"))
                .thenThrow(new AccountNotFoundException());

        mockMvc.perform(get("/api/v1/accounts/12345678-9"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void returnsStatementAndPassesPeriodAndPaginationParametersToService() throws Exception {
        StatementResponse response = new StatementResponse("12345678-9", LocalDate.parse("2026-10-01"),
                LocalDate.parse("2026-10-31"), new BigDecimal("10.00"), List.of(),
                new BigDecimal("10.00"), 2, 5, 0, 5);
        when(accountStatementService.getStatement("12345678-9", "2026-10-01", "2026-10-31", "2", "5"))
                .thenReturn(response);

        mockMvc.perform(get("/api/v1/accounts/12345678-9/statement")
                .param("from", "2026-10-01")
                .param("to", "2026-10-31")
                .param("page", "2")
                .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(content().json(expectedJson("account-statement.json"), JsonCompareMode.STRICT));

        verify(accountStatementService).getStatement("12345678-9", "2026-10-01", "2026-10-31", "2", "5");
    }

    @Test
    void returnsPopulatedFirstStatementPageWithTenChronologicalEntries() throws Exception {
        StatementResponse response = new StatementResponse("12345678-9", LocalDate.parse("2026-10-01"),
                LocalDate.parse("2026-10-31"), new BigDecimal("100.00"), firstTenEntries(),
                new BigDecimal("126.00"), 1, 10, 10, 12);
        when(accountStatementService.getStatement("12345678-9", "2026-10-01", "2026-10-31", "1", "10"))
                .thenReturn(response);

        mockMvc.perform(get("/api/v1/accounts/12345678-9/statement")
                .param("from", "2026-10-01")
                .param("to", "2026-10-31")
                .param("page", "1")
                .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(content().json(expectedJson("account-statement-page-full.json"),
                        JsonCompareMode.STRICT));

        verify(accountStatementService).getStatement("12345678-9", "2026-10-01", "2026-10-31", "1", "10");
    }

    @Test
    void returnsShortLastStatementPageWithRemainingEntries() throws Exception {
        List<StatementEntryResponse> lastEntries = List.of(
                entry(11, TransactionType.TRANSFER, MovementType.DEBIT, "1.25", "transferência enviada", 10),
                entry(12, TransactionType.DEPOSIT, MovementType.CREDIT, "0.50", "cashback", 11));
        StatementResponse response = new StatementResponse("12345678-9", LocalDate.parse("2026-10-01"),
                LocalDate.parse("2026-10-31"), new BigDecimal("100.00"), lastEntries,
                new BigDecimal("126.00"), 2, 10, 2, 12);
        when(accountStatementService.getStatement("12345678-9", "2026-10-01", "2026-10-31", "2", "10"))
                .thenReturn(response);

        mockMvc.perform(get("/api/v1/accounts/12345678-9/statement")
                .param("from", "2026-10-01")
                .param("to", "2026-10-31")
                .param("page", "2")
                .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(content().json(expectedJson("account-statement-page-last.json"),
                        JsonCompareMode.STRICT));

        verify(accountStatementService).getStatement("12345678-9", "2026-10-01", "2026-10-31", "2", "10");
    }

    private String expectedJson(String fileName) throws IOException {
        try (InputStream fixture = getClass().getResourceAsStream("/json/account/" + fileName)) {
            if (fixture == null) {
                throw new IllegalStateException("JSON fixture not found: " + fileName);
            }
            return new String(fixture.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private List<StatementEntryResponse> firstTenEntries() {
        return List.of(
                entry(1, TransactionType.DEPOSIT, MovementType.CREDIT, "10.00", "salário", 0),
                entry(2, TransactionType.WITHDRAWAL, MovementType.DEBIT, "5.00", "mercado", 1),
                entry(3, TransactionType.TRANSFER, MovementType.DEBIT, "2.50", "transferência enviada", 2),
                entry(4, TransactionType.TRANSFER, MovementType.CREDIT, "3.50", "transferência recebida", 3),
                entry(5, TransactionType.DEPOSIT, MovementType.CREDIT, "20.00", null, 4),
                entry(6, TransactionType.WITHDRAWAL, MovementType.DEBIT, "7.25", "conta de luz", 5),
                entry(7, TransactionType.DEPOSIT, MovementType.CREDIT, "1.00", "ajuste", 6),
                entry(8, TransactionType.TRANSFER, MovementType.CREDIT, "4.00", "recebimento", 7),
                entry(9, TransactionType.WITHDRAWAL, MovementType.DEBIT, "2.00", "tarifa", 8),
                entry(10, TransactionType.DEPOSIT, MovementType.CREDIT, "5.00", "reembolso", 9));
    }

    private StatementEntryResponse entry(int id, TransactionType transactionType, MovementType movementType,
            String amount, String description, int minute) {
        UUID transactionId = UUID.fromString(String.format("00000000-0000-0000-0000-%012x", id));
        Instant postedAt = Instant.parse(String.format("2026-10-15T09:%02d:00Z", minute));
        return new StatementEntryResponse(transactionId, transactionType, movementType,
                new BigDecimal(amount), description, postedAt);
    }

    @Test
    void returnsBadRequestForInvalidStatementAccountNumber() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/invalid/statement"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(accountStatementService, never()).getStatement(eq("invalid"), eq(null), eq(null), eq(null), eq(null));
    }

    @Test
    void returnsNotFoundWhenStatementAccountDoesNotExist() throws Exception {
        when(accountStatementService.getStatement("12345678-9", "2026-10-01", "2026-10-31", null, null))
                .thenThrow(new AccountNotFoundException());

        mockMvc.perform(get("/api/v1/accounts/12345678-9/statement")
                .param("from", "2026-10-01")
                .param("to", "2026-10-31"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }
}
