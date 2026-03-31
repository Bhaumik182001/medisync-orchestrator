package com.bhaumik18.medisync_orchestrator.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.bhaumik18.medisync_orchestrator.entity.BookingTransaction;

@Repository
public interface BookingTransactionRepository extends JpaRepository<BookingTransaction, Long> {

}
