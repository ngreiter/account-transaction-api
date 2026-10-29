package io.github.ngreiter.accounttransaction.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(named = "DB_URL", matches = "jdbc:postgresql:.+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PostgresIntegrationTest {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Sao_Paulo");
    private static final Pattern STRING_FIELD = Pattern.compile("\\\"%s\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"");
    private static final String ACCOUNTS_PATH = "/api/v1/accounts";
    private static final String TRANSACTIONS_PATH = "/api/v1/transactions";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();
    private final Set<String> idempotencyKeys = new LinkedHashSet<>();
    private final Set<String> documents = new LinkedHashSet<>();
    private String rollbackTriggerName;
    private String rollbackFunctionName;
    private String rollbackMarker;

    @Test
    void createsAndQueriesAccountAndRejectsDuplicateDocument() throws Exception {
        String document = newCpf();
        HttpResponse<String> created = createAccount(document, key());

        assertEquals(201, created.statusCode());
        String accountNumber = field(created.body(), "accountNumber");
        assertEquals("0.00", field(created.body(), "balance"));

        HttpResponse<String> details = get(ACCOUNTS_PATH + "/" + accountNumber);
        assertEquals(200, details.statusCode());
        assertEquals(accountNumber, field(details.body(), "accountNumber"));
        assertEquals("0.00", field(details.body(), "balance"));
        assertTrue(field(details.body(), "documentMasked").contains("***"));
        assertEquals(1, count("select count(*) from account where document = ?", document));

        HttpResponse<String> duplicate = createAccount(document, key());
        assertEquals(409, duplicate.statusCode());
        assertEquals(1, count("select count(*) from account where document = ?", document));
    }

    @Test
    void rejectsDuplicateCnpj() throws Exception {
        String document = newCnpj();
        HttpResponse<String> created = createAccount(document, key());
        assertEquals(201, created.statusCode());

        HttpResponse<String> duplicate = createAccount(document, key());
        assertEquals(409, duplicate.statusCode());
        assertEquals(1, count("select count(*) from account where document = ?", document));
    }

    @Test
    void depositsAndWithdrawalsUpdateBalanceAndPersistMovements() throws Exception {
        String account = createAccountAndGetNumber();

        HttpResponse<String> deposit = post(TRANSACTIONS_PATH + "/deposits", key(),
                "{\"accountNumber\":\"" + account + "\",\"amount\":100.00,\"description\":\"integration deposit\"}");
        assertEquals(201, deposit.statusCode());
        assertEquals("SUCCEEDED", field(deposit.body(), "status"));

        HttpResponse<String> withdrawal = post(TRANSACTIONS_PATH + "/withdrawals", key(),
                "{\"accountNumber\":\"" + account + "\",\"amount\":30.00,\"description\":\"integration withdrawal\"}");
        assertEquals(201, withdrawal.statusCode());
        assertEquals("SUCCEEDED", field(withdrawal.body(), "status"));

        assertMoney("70.00", balance(account));
        assertEquals(2, count("select count(*) from account_transaction where account_number = ?", account));
        assertEquals(2, count("select count(*) from financial_transaction f join account_transaction a "
                + "on a.financial_transaction_id = f.id where a.account_number = ?", account));
    }

    @Test
    void transferUpdatesBothAccountsAndPersistsBalancedMovements() throws Exception {
        String source = createAccountAndGetNumber();
        String destination = createAccountAndGetNumber();
        deposit(source, "100.00");

        HttpResponse<String> transfer = post(TRANSACTIONS_PATH + "/transfers", key(),
                "{\"sourceAccountNumber\":\"" + source + "\",\"destinationAccountNumber\":\""
                        + destination + "\",\"amount\":25.00,\"description\":\"integration transfer\"}");

        assertEquals(201, transfer.statusCode());
        assertEquals("SUCCEEDED", field(transfer.body(), "status"));
        assertMoney("75.00", balance(source));
        assertMoney("25.00", balance(destination));
        assertEquals(1, count("select count(*) from account_transaction where account_number = ? and movement_type = 'DEBIT'",
                source));
        assertEquals(1, count("select count(*) from account_transaction where account_number = ? and movement_type = 'CREDIT'",
                destination));
        String financialId = field(transfer.body(), "financialTransactionId");
        assertEquals(2, count("select count(*) from account_transaction where financial_transaction_id = ?::uuid",
                financialId));
    }

    @Test
    void idempotentReplayDoesNotDuplicateEffectsAndDifferentRequestConflicts() throws Exception {
        String account = createAccountAndGetNumber();
        String idempotencyKey = key();
        String request = "{\"accountNumber\":\"" + account + "\",\"amount\":40.00}";

        HttpResponse<String> first = post(TRANSACTIONS_PATH + "/deposits", idempotencyKey, request);
        HttpResponse<String> replay = post(TRANSACTIONS_PATH + "/deposits", idempotencyKey, request);
        HttpResponse<String> conflict = post(TRANSACTIONS_PATH + "/deposits", idempotencyKey,
                "{\"accountNumber\":\"" + account + "\",\"amount\":41.00}");

        assertEquals(201, first.statusCode());
        assertEquals(first.statusCode(), replay.statusCode());
        assertEquals(first.body(), replay.body());
        assertEquals(409, conflict.statusCode());
        assertEquals("IDEMPOTENCY_CONFLICT", field(conflict.body(), "code"));
        assertMoney("40.00", balance(account));
        assertEquals(1, count("select count(*) from account_transaction where account_number = ?", account));
    }

    @Test
    void statementReturnsPeriodBalancesChronologicalEntriesAndPagination() throws Exception {
        String account = createAccountAndGetNumber();
        deposit(account, "100.00");
        withdraw(account, "20.00");
        deposit(account, "5.00");
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        LocalDate from = today.withDayOfMonth(1).minusMonths(2);
        String path = ACCOUNTS_PATH + "/" + account + "/statement?from=" + from
                + "&to=" + today + "&page=1&size=2";

        HttpResponse<String> firstPage = get(path);
        assertEquals(200, firstPage.statusCode());
        assertEquals("0.00", field(firstPage.body(), "openingBalance"));
        assertEquals("85.00", field(firstPage.body(), "closingBalance"));
        assertEquals(1, numberField(firstPage.body(), "page"));
        assertEquals(2, numberField(firstPage.body(), "numberOfElements"));
        assertEquals(3, numberField(firstPage.body(), "totalEntries"));
        int firstDepositPosition = firstPage.body().indexOf("\"transactionType\":\"DEPOSIT\"");
        int withdrawalPosition = firstPage.body().indexOf("\"transactionType\":\"WITHDRAWAL\"");
        assertTrue(firstDepositPosition >= 0 && withdrawalPosition > firstDepositPosition);

        HttpResponse<String> secondPage = get(ACCOUNTS_PATH + "/" + account + "/statement?from="
                + from + "&to=" + today + "&page=2&size=2");
        assertEquals(200, secondPage.statusCode());
        assertEquals(1, numberField(secondPage.body(), "numberOfElements"));
        assertEquals("DEPOSIT", field(secondPage.body(), "transactionType"));
        assertTrue(secondPage.body().contains("\"amount\":\"5.00\""));
    }

    @Test
    void onlyOneOfTwoConcurrentWithdrawalsCanSpendTheAvailableBalance() throws Exception {
        String account = createAccountAndGetNumber();
        deposit(account, "100.00");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<HttpResponse<String>> first = executor.submit(() -> concurrentWithdrawal(account, ready, start));
            Future<HttpResponse<String>> second = executor.submit(() -> concurrentWithdrawal(account, ready, start));
            ready.await();
            start.countDown();
            HttpResponse<String> firstResponse = first.get();
            HttpResponse<String> secondResponse = second.get();

            List<Integer> statuses = List.of(firstResponse.statusCode(), secondResponse.statusCode());
            assertEquals(1, statuses.stream().filter(status -> status == 201).count());
            assertEquals(1, statuses.stream().filter(status -> status == 422).count());
            assertMoney("20.00", balance(account));
            assertEquals(2, count("select count(*) from account_transaction where account_number = ?", account));
            assertEquals(1, count("select count(*) from account_transaction where account_number = ? and movement_type = 'DEBIT'",
                    account));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void failedTransferRollsBackBalancesLedgerAndFinancialTransaction() throws Exception {
        String source = createAccountAndGetNumber();
        String destination = createAccountAndGetNumber();
        deposit(source, "100.00");
        rollbackMarker = "rollback-" + UUID.randomUUID();
        installTransferCreditFailureTrigger();
        String transferKey = key();
        HttpResponse<String> response = post(TRANSACTIONS_PATH + "/transfers", transferKey,
                "{\"sourceAccountNumber\":\"" + source + "\",\"destinationAccountNumber\":\""
                        + destination + "\",\"amount\":25.00,\"description\":\"" + rollbackMarker + "\"}");

        assertEquals(500, response.statusCode());
        assertMoney("100.00", balance(source));
        assertMoney("0.00", balance(destination));
        assertEquals(1, count("select count(*) from account_transaction where account_number in (?, ?)",
                source, destination));
        assertEquals(1, count("select count(*) from financial_transaction f join account_transaction a "
                + "on a.financial_transaction_id = f.id where a.account_number = ?", source));
        assertEquals(0, count("select count(*) from idempotency_record where idempotency_key = ?", transferKey));
    }

    @AfterEach
    void cleanUpIsolatedData() {
        dropTransferCreditFailureTrigger();
        List<String> accountNumbers = new ArrayList<>();
        for (String document : documents) {
            accountNumbers.addAll(jdbc.query("select account_number from account where document = ?",
                    (rs, row) -> rs.getString(1), document));
        }
        Set<String> uniqueAccountNumbers = new LinkedHashSet<>(accountNumbers);
        List<UUID> financialTransactionIds = new ArrayList<>();
        for (String accountNumber : uniqueAccountNumbers) {
            financialTransactionIds.addAll(jdbc.query(
                    "select distinct financial_transaction_id from account_transaction where account_number = ?",
                    (ResultSet rs, int row) -> rs.getObject(1, UUID.class), accountNumber));
        }
        for (String idempotencyKey : idempotencyKeys) {
            jdbc.update("delete from idempotency_record where idempotency_key = ?", idempotencyKey);
        }
        for (String accountNumber : uniqueAccountNumbers) {
            jdbc.update("delete from account_transaction where account_number = ?", accountNumber);
        }
        for (UUID id : financialTransactionIds) {
            jdbc.update("delete from financial_transaction where id = ?", id);
        }
        for (String accountNumber : uniqueAccountNumbers) {
            jdbc.update("delete from account where account_number = ?", accountNumber);
        }
        idempotencyKeys.clear();
        documents.clear();
    }

    private HttpResponse<String> concurrentWithdrawal(String account, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException, IOException {
        ready.countDown();
        start.await();
        return post(TRANSACTIONS_PATH + "/withdrawals", key(), "{\"accountNumber\":\"" + account
                + "\",\"amount\":80.00}");
    }

    private String createAccountAndGetNumber() throws Exception {
        HttpResponse<String> response = createAccount(newCpf(), key());
        assertEquals(201, response.statusCode());
        return field(response.body(), "accountNumber");
    }

    private HttpResponse<String> createAccount(String document, String key) throws Exception {
        documents.add(document);
        return post(ACCOUNTS_PATH, key, "{\"document\":\"" + document + "\"}");
    }

    private void deposit(String account, String amount) throws Exception {
        HttpResponse<String> response = post(TRANSACTIONS_PATH + "/deposits", key(), "{\"accountNumber\":\""
                + account + "\",\"amount\":" + amount + "}");
        assertEquals(201, response.statusCode());
    }

    private void withdraw(String account, String amount) throws Exception {
        HttpResponse<String> response = post(TRANSACTIONS_PATH + "/withdrawals", key(), "{\"accountNumber\":\""
                + account + "\",\"amount\":" + amount + "}");
        assertEquals(201, response.statusCode());
    }

    private HttpResponse<String> post(String path, String idempotencyKey, String body) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling the integration-test server", exception);
        }
    }

    private HttpResponse<String> get(String path) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(uri(path)).GET().build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling the integration-test server", exception);
        }
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private String key() {
        String key = "it-" + UUID.randomUUID();
        synchronized (idempotencyKeys) {
            idempotencyKeys.add(key);
        }
        return key;
    }

    private String field(String json, String name) {
        Matcher matcher = Pattern.compile(String.format(STRING_FIELD.pattern(), Pattern.quote(name))).matcher(json);
        if (!matcher.find()) {
            throw new AssertionError("JSON field '" + name + "' not found in: " + json);
        }
        return matcher.group(1);
    }

    private long numberField(String json, String name) {
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(name) + "\\\"\\s*:\\s*(\\d+)").matcher(json);
        if (!matcher.find()) {
            throw new AssertionError("Numeric JSON field '" + name + "' not found in: " + json);
        }
        return Long.parseLong(matcher.group(1));
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private BigDecimal balance(String accountNumber) {
        return jdbc.queryForObject("select balance from account where account_number = ?", BigDecimal.class,
                accountNumber);
    }

    private void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    private String newCpf() {
        String cpf;
        do {
            String base = String.valueOf(ThreadLocalRandom.current().nextInt(100_000_000, 1_000_000_000));
            int firstDigit = cpfDigit(base, 10);
            int secondDigit = cpfDigit(base + firstDigit, 11);
            cpf = base + firstDigit + secondDigit;
        } while (Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from account where document = ?)", Boolean.class, cpf)));
        return cpf;
    }

    private String newCnpj() {
        String cnpj;
        do {
            String base = String.valueOf(ThreadLocalRandom.current().nextLong(
                    100_000_000_000L, 1_000_000_000_000L));
            int firstDigit = cnpjDigit(base);
            int secondDigit = cnpjDigit(base + firstDigit);
            cnpj = base + firstDigit + secondDigit;
        } while (Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from account where document = ?)", Boolean.class, cnpj)));
        return cnpj;
    }

    private int cnpjDigit(String digits) {
        int[] weights = digits.length() == 12
                ? new int[] { 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2 }
                : new int[] { 6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2 };
        int sum = 0;
        for (int index = 0; index < digits.length(); index++) {
            sum += (digits.charAt(index) - '0') * weights[index];
        }
        int remainder = sum % 11;
        return remainder < 2 ? 0 : 11 - remainder;
    }

    private int cpfDigit(String digits, int initialWeight) {
        int sum = 0;
        for (int index = 0; index < digits.length(); index++) {
            sum += (digits.charAt(index) - '0') * (initialWeight - index);
        }
        int remainder = sum % 11;
        return remainder < 2 ? 0 : 11 - remainder;
    }

    private void installTransferCreditFailureTrigger() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        rollbackFunctionName = "it_fail_transfer_credit_" + suffix;
        rollbackTriggerName = "it_fail_transfer_credit_" + suffix;
        String function = "CREATE FUNCTION " + rollbackFunctionName + "() RETURNS trigger AS $$ "
                + "BEGIN IF NEW.movement_type = 'CREDIT' AND NEW.description = '" + rollbackMarker + "' THEN "
                + "RAISE EXCEPTION 'integration rollback probe' USING ERRCODE = '23514'; "
                + "END IF; RETURN NEW; END; $$ LANGUAGE plpgsql";
        jdbc.execute(function);
        jdbc.execute("CREATE TRIGGER " + rollbackTriggerName + " BEFORE INSERT ON account_transaction "
                + "FOR EACH ROW EXECUTE FUNCTION " + rollbackFunctionName + "()");
    }

    private void dropTransferCreditFailureTrigger() {
        if (rollbackTriggerName != null) {
            jdbc.execute("DROP TRIGGER IF EXISTS " + rollbackTriggerName + " ON account_transaction");
            rollbackTriggerName = null;
        }
        if (rollbackFunctionName != null) {
            jdbc.execute("DROP FUNCTION IF EXISTS " + rollbackFunctionName + "()");
            rollbackFunctionName = null;
        }
    }
}
