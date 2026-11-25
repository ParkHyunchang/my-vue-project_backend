package com.hyunchang.webapp.service;

import com.hyunchang.webapp.dto.ManualRecordsCleanupDtos.Counts;
import com.hyunchang.webapp.repository.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Deletes only user-owned manual records. Never depends on broker/trading repositories. */
@Service
@Transactional
public class ManualRecordsCleanupService {
    private final StockHoldingRepository stock;
    private final GeneralHoldingRepository general;
    private final IsaHoldingRepository isa;
    private final IrpHoldingRepository irp;
    private final AccountHoldingRepository accountHoldings;
    private final PortfolioAccountRepository accounts;

    public ManualRecordsCleanupService(
            StockHoldingRepository stock,
            GeneralHoldingRepository general,
            IsaHoldingRepository isa,
            IrpHoldingRepository irp,
            AccountHoldingRepository accountHoldings,
            PortfolioAccountRepository accounts) {
        this.stock = stock;
        this.general = general;
        this.isa = isa;
        this.irp = irp;
        this.accountHoldings = accountHoldings;
        this.accounts = accounts;
    }

    @Transactional(readOnly = true)
    public Counts summary(String userId) {
        requireUser(userId);
        return Counts.of(
                stock.countByUserUserId(userId),
                general.countByUserUserId(userId),
                isa.countByUserUserId(userId),
                irp.countByUserUserId(userId),
                accountHoldings.countByAccountUserUserId(userId),
                accounts.countByUserUserId(userId));
    }

    public Counts delete(String userId, String confirmation) {
        requireUser(userId);
        if (!"DELETE_MANUAL_RECORDS".equals(confirmation)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "수기 기록 삭제 확인이 필요합니다.");
        }
        // Child rows first; all six bulk operations participate in this transaction.
        long deletedHoldings = accountHoldings.deleteManualByUserId(userId);
        long deletedAccounts = accounts.deleteManualByUserId(userId);
        long deletedStock = stock.deleteManualByUserId(userId);
        long deletedGeneral = general.deleteManualByUserId(userId);
        long deletedIsa = isa.deleteManualByUserId(userId);
        long deletedIrp = irp.deleteManualByUserId(userId);
        return Counts.of(
                deletedStock,
                deletedGeneral,
                deletedIsa,
                deletedIrp,
                deletedHoldings,
                deletedAccounts);
    }

    private void requireUser(String userId) {
        if (userId == null || userId.isBlank() || "unknown".equals(userId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }
}
