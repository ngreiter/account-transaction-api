package io.github.ngreiter.accounttransaction.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import io.github.ngreiter.accounttransaction.domain.Account;
import jakarta.persistence.LockModeType;

public interface AccountRepository extends JpaRepository<Account, String> {

    Optional<Account> findByDocument(String document);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select a from Account a 
        where a.accountNumber in :accountNumbers 
        order by a.accountNumber
    """)
    List<Account> findAllByAccountNumberInOrderByAccountNumberForUpdate(
            @Param("accountNumbers") Collection<String> accountNumbers);
}
