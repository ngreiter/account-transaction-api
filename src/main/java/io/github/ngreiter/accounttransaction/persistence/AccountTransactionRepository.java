package io.github.ngreiter.accounttransaction.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import io.github.ngreiter.accounttransaction.domain.AccountTransaction;
import io.github.ngreiter.accounttransaction.domain.MovementType;

public interface AccountTransactionRepository extends JpaRepository<AccountTransaction, UUID> {

    Page<AccountTransaction> findByAccount_AccountNumberAndPostedAtGreaterThanEqualAndPostedAtLessThanOrderByPostedAtAscIdAsc(
            String accountNumber, Instant fromInclusive, Instant toExclusive, Pageable pageable);

    @Query("""
            select coalesce(sum(case when t.movementType = :credit then t.amount else -t.amount end), 0)
            from AccountTransaction t
            where t.account.accountNumber = :accountNumber
              and t.postedAt < :before
            """)
    BigDecimal calculateNetAmountBefore(@Param("accountNumber") String accountNumber,
            @Param("before") Instant before, @Param("credit") MovementType credit);

    @Query("""
            select coalesce(sum(case when t.movementType = :credit then t.amount else -t.amount end), 0)
            from AccountTransaction t
            where t.account.accountNumber = :accountNumber
              and t.postedAt >= :fromInclusive
              and t.postedAt < :toExclusive
            """)
    BigDecimal calculateNetAmountBetween(@Param("accountNumber") String accountNumber,
            @Param("fromInclusive") Instant fromInclusive, @Param("toExclusive") Instant toExclusive,
            @Param("credit") MovementType credit);
}
