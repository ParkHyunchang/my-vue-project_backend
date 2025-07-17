package com.hyunchang.webapp.service;

import com.hyunchang.webapp.entity.Expense;
import com.hyunchang.webapp.repository.ExpenseRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class ExpenseService {
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
        return expenseRepository.save(expense);
    }

    public Expense update(Long id, Expense expense) {
        Expense existingExpense = findById(id);
        existingExpense.setTitle(expense.getTitle());
        existingExpense.setDescription(expense.getDescription());
        existingExpense.setAmount(expense.getAmount());
        existingExpense.setCategory(expense.getCategory());
        existingExpense.setType(expense.getType());
        return expenseRepository.save(existingExpense);
    }

    public void delete(Long id) {
        expenseRepository.deleteById(id);
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
} 