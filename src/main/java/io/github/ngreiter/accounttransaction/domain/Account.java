package io.github.ngreiter.accounttransaction.domain;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "account")
public class Account {

    @Id
    @Column(name = "account_number", length = 10, nullable = false)
    private String accountNumber;

    @Column(name = "document", length = 14, nullable = false, unique = true)
    private String document;

    @Column(name = "balance", precision = 19, scale = 2, nullable = false)
    private BigDecimal balance;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected Account() {
    }

    public Account(String accountNumber, String document) {
        this.accountNumber = accountNumber;
        this.document = document;
        this.balance = BigDecimal.ZERO.setScale(2);
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public String getDocument() {
        return document;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
