package com.hyunchang.webapp.service;

import com.hyunchang.webapp.entity.Expense;
import com.hyunchang.webapp.repository.ExpenseRepository;
import com.hyunchang.webapp.util.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class ExpenseService {
    private static final Logger log = LoggerFactory.getLogger(ExpenseService.class);

    private final ExpenseRepository expenseRepository;

    public ExpenseService(ExpenseRepository expenseRepository) {
        this.expenseRepository = expenseRepository;
    }

    public List<Expense> findAll() {
        return expenseRepository.findAll();
    }

    public Page<Expense> findAll(Pageable pageable) {
        return expenseRepository.findAll(pageable);
    }

    public Expense findById(Long id) {
        return expenseRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Expense not found with id: " + id));
    }

    public Expense create(Expense expense) {
        if (expense.getCreatedAt() == null) {
            expense.setCreatedAt(LocalDateTime.now());
        }
        Expense saved = expenseRepository.save(expense);
        log.info("[EXPENSE] user={}({}), CREATE id={} title={} type={} amount={} category={} fixed={}",
            SecurityUtils.getCurrentUserId(), SecurityUtils.getCurrentUserRoleName(),
            saved.getId(), saved.getTitle(), saved.getType(), saved.getAmount(), saved.getCategory(), saved.isFixed());
        return saved;
    }

    public Expense update(Long id, Expense expense) {
        Expense existingExpense = findById(id);

        String oldTitle = existingExpense.getTitle();
        String oldDescription = existingExpense.getDescription();
        Long oldAmount = existingExpense.getAmount();
        String oldCategory = existingExpense.getCategory();
        String oldType = existingExpense.getType();
        boolean oldFixed = existingExpense.isFixed();

        existingExpense.setTitle(expense.getTitle());
        existingExpense.setDescription(expense.getDescription());
        existingExpense.setAmount(expense.getAmount());
        existingExpense.setCategory(expense.getCategory());
        existingExpense.setType(expense.getType());
        existingExpense.setFixed(expense.isFixed());
        Expense saved = expenseRepository.save(existingExpense);

        List<String> diffs = new ArrayList<>();
        if (!Objects.equals(oldTitle, saved.getTitle())) {
            diffs.add(String.format("title '%s'→'%s'", oldTitle, saved.getTitle()));
        }
        if (!Objects.equals(oldAmount, saved.getAmount())) {
            diffs.add(String.format("amount %s→%s", oldAmount, saved.getAmount()));
        }
        if (!Objects.equals(oldCategory, saved.getCategory())) {
            diffs.add(String.format("category %s→%s", oldCategory, saved.getCategory()));
        }
        if (!Objects.equals(oldType, saved.getType())) {
            diffs.add(String.format("type %s→%s", oldType, saved.getType()));
        }
        if (oldFixed != saved.isFixed()) {
            diffs.add(String.format("fixed %s→%s", oldFixed, saved.isFixed()));
        }
        if (!Objects.equals(oldDescription, saved.getDescription())) {
            diffs.add("description (변경됨)");
        }

        log.info("[EXPENSE] user={}({}), UPDATE id={} {}",
            SecurityUtils.getCurrentUserId(), SecurityUtils.getCurrentUserRoleName(),
            saved.getId(),
            diffs.isEmpty() ? "(변경 없음)" : String.join(", ", diffs));
        return saved;
    }

    public void delete(Long id) {
        Expense expense = findById(id);
        expenseRepository.deleteById(id);
        log.info("[EXPENSE] user={}({}), DELETE id={} title={} type={} amount={}",
            SecurityUtils.getCurrentUserId(), SecurityUtils.getCurrentUserRoleName(),
            expense.getId(), expense.getTitle(), expense.getType(), expense.getAmount());
    }

    public List<Expense> findByType(String type) {
        return expenseRepository.findByTypeOrderByCreatedAtDesc(type);
    }

    public List<Expense> findByCategory(String category) {
        return expenseRepository.findByCategoryOrderByCreatedAtDesc(category);
    }

    public List<Expense> findByDateRange(LocalDateTime startDate, LocalDateTime endDate) {
        return expenseRepository.findByDateRange(startDate, endDate);
    }

    public List<Expense> findByDateRange(LocalDateTime startDate, LocalDateTime endDate, Boolean fixed) {
        List<Expense> expenses = expenseRepository.findByDateRange(startDate, endDate);
        if (fixed == null) {
            return expenses;
        }
        return expenses.stream()
                .filter(expense -> expense.isFixed() == fixed)
                .collect(Collectors.toList());
    }

    public List<Expense> findFixedExpenses() {
        return expenseRepository.findByFixedTrueOrderByCreatedAtDesc();
    }

    public Long getTotalIncome() {
        return expenseRepository.sumByType("INCOME") != null ? expenseRepository.sumByType("INCOME") : 0L;
    }

    public Long getTotalExpense() {
        return expenseRepository.sumByType("EXPENSE") != null ? expenseRepository.sumByType("EXPENSE") : 0L;
    }

    public Long getBalance() {
        return getTotalIncome() - getTotalExpense();
    }

    public Long getTotalIncomeByDateRange(LocalDateTime startDate, LocalDateTime endDate) {
        return expenseRepository.sumByTypeAndDateRange("INCOME", startDate, endDate) != null ? 
               expenseRepository.sumByTypeAndDateRange("INCOME", startDate, endDate) : 0L;
    }

    public Long getTotalExpenseByDateRange(LocalDateTime startDate, LocalDateTime endDate) {
        return expenseRepository.sumByTypeAndDateRange("EXPENSE", startDate, endDate) != null ? 
               expenseRepository.sumByTypeAndDateRange("EXPENSE", startDate, endDate) : 0L;
    }

    public Long getTotalFixedExpense() {
        return expenseRepository.sumByFixed() != null ? expenseRepository.sumByFixed() : 0L;
    }

    public Long getTotalFixedExpenseByDateRange(LocalDateTime startDate, LocalDateTime endDate) {
        return expenseRepository.sumByFixedAndDateRange(startDate, endDate) != null ?
                expenseRepository.sumByFixedAndDateRange(startDate, endDate) : 0L;
    }
} 