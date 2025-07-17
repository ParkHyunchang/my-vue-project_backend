package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.entity.Expense;
import com.hyunchang.webapp.service.ExpenseService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/expenses")
public class ExpenseController {
    private final ExpenseService expenseService;

    public ExpenseController(ExpenseService expenseService) {
        this.expenseService = expenseService;
    }



    @GetMapping
    public ResponseEntity<List<Expense>> findAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<Expense> expensePage = expenseService.findAll(pageable);
        
        return ResponseEntity
            .ok()
            .header("X-Total-Count", String.valueOf(expensePage.getTotalElements()))
            .body(expensePage.getContent());
    }

    @GetMapping("/{id}")
    public Expense findById(@PathVariable Long id) {
        return expenseService.findById(id);
    }

    @PostMapping
    public Expense create(@RequestBody Expense expense) {
        return expenseService.create(expense);
    }

    @PutMapping("/{id}")
    public Expense update(@PathVariable Long id, @RequestBody Expense expense) {
        return expenseService.update(id, expense);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        expenseService.delete(id);
    }

    @GetMapping("/type/{type}")
    public List<Expense> findByType(@PathVariable String type) {
        return expenseService.findByType(type);
    }

    @GetMapping("/category/{category}")
    public List<Expense> findByCategory(@PathVariable String category) {
        return expenseService.findByCategory(category);
    }

    @GetMapping("/summary")
    public Map<String, Object> getSummary() {
        Map<String, Object> summary = new HashMap<>();
        summary.put("totalIncome", expenseService.getTotalIncome());
        summary.put("totalExpense", expenseService.getTotalExpense());
        summary.put("balance", expenseService.getBalance());
        return summary;
    }

    @GetMapping("/summary/date-range")
    public Map<String, Object> getSummaryByDateRange(
            @RequestParam String startDate,
            @RequestParam String endDate) {
        LocalDateTime start = LocalDateTime.parse(startDate);
        LocalDateTime end = LocalDateTime.parse(endDate);
        
        Map<String, Object> summary = new HashMap<>();
        summary.put("totalIncome", expenseService.getTotalIncomeByDateRange(start, end));
        summary.put("totalExpense", expenseService.getTotalExpenseByDateRange(start, end));
        summary.put("balance", expenseService.getTotalIncomeByDateRange(start, end) - expenseService.getTotalExpenseByDateRange(start, end));
        return summary;
    }
} 