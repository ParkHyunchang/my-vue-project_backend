package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.entity.Expense;
import com.hyunchang.webapp.service.ExpenseService;
import com.hyunchang.webapp.service.MenuPermissionService;
import com.hyunchang.webapp.util.SecurityUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/expenses")
public class ExpenseController {
    private static final String MENU_PATH = "/expense";

    private final ExpenseService expenseService;
    private final MenuPermissionService menuPermissionService;

    public ExpenseController(ExpenseService expenseService, MenuPermissionService menuPermissionService) {
        this.expenseService = expenseService;
        this.menuPermissionService = menuPermissionService;
    }

    private boolean hasAccess() {
        return menuPermissionService.hasMenuAccess(SecurityUtils.getCurrentUserRoleName(), MENU_PATH);
    }

    private ResponseEntity<?> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("접근 권한이 없습니다.");
    }

    @GetMapping
    public ResponseEntity<?> findAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        if (!hasAccess()) return forbidden();

        Pageable pageable = PageRequest.of(page, size);
        Page<Expense> expensePage = expenseService.findAll(pageable);

        return ResponseEntity
            .ok()
            .header("X-Total-Count", String.valueOf(expensePage.getTotalElements()))
            .body(expensePage.getContent());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> findById(@PathVariable Long id) {
        if (!hasAccess()) return forbidden();
        return ResponseEntity.ok(expenseService.findById(id));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Expense expense) {
        if (!hasAccess()) return forbidden();
        return ResponseEntity.ok(expenseService.create(expense));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Expense expense) {
        if (!hasAccess()) return forbidden();
        return ResponseEntity.ok(expenseService.update(id, expense));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        if (!hasAccess()) return forbidden();
        expenseService.delete(id);
        return ResponseEntity.ok().body("삭제되었습니다.");
    }

    @GetMapping("/fixed")
    public ResponseEntity<?> findFixedExpenses() {
        if (!hasAccess()) return forbidden();
        return ResponseEntity.ok(expenseService.findFixedExpenses());
    }

    @GetMapping("/date-range")
    public ResponseEntity<?> findByDateRange(
            @RequestParam String startDate,
            @RequestParam String endDate,
            @RequestParam(required = false) Boolean fixed) {
        if (!hasAccess()) return forbidden();

        LocalDateTime start = parseStartDate(startDate);
        LocalDateTime end = parseEndDate(endDate);

        return ResponseEntity.ok(expenseService.findByDateRange(start, end, fixed));
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
        summary.put("totalFixedExpense", expenseService.getTotalFixedExpense());
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
        summary.put("totalFixedExpense", expenseService.getTotalFixedExpenseByDateRange(start, end));
        return summary;
    }

    private LocalDateTime parseStartDate(String date) {
        try {
            return LocalDateTime.parse(date);
        } catch (DateTimeParseException ex) {
            LocalDate localDate = LocalDate.parse(date);
            return localDate.atStartOfDay();
        }
    }

    private LocalDateTime parseEndDate(String date) {
        try {
            return LocalDateTime.parse(date);
        } catch (DateTimeParseException ex) {
            LocalDate localDate = LocalDate.parse(date);
            return localDate.atTime(LocalTime.MAX);
        }
    }

}
