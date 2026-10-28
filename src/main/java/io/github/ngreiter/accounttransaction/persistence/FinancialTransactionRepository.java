package io.github.ngreiter.accounttransaction.persistence;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import io.github.ngreiter.accounttransaction.domain.FinancialTransaction;

public interface FinancialTransactionRepository extends JpaRepository<FinancialTransaction, UUID> {
}
