package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.entity.Expense;
import com.hyunchang.webapp.entity.Role;
import com.hyunchang.webapp.service.ExpenseService;
import com.hyunchang.webapp.service.MenuCrudPermissionService;
import com.hyunchang.webapp.util.SecurityUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
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
    private final MenuCrudPermissionService menuCrudPermissionService;

    public ExpenseController(ExpenseService expenseService, MenuCrudPermissionService menuCrudPermissionService) {
        this.expenseService = expenseService;
        this.menuCrudPermissionService = menuCrudPermissionService;
    }

    @GetMapping
    public ResponseEntity<?> findAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        Role userRole = SecurityUtils.getCurrentUserRole();
        if (!menuCrudPermissionService.canRead(userRole, "/expense")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("조회 권한이 없습니다.");
        }
        
        Pageable pageable = PageRequest.of(page, size);
        Page<Expense> expensePage = expenseService.findAll(pageable);
        
        return ResponseEntity
            .ok()
            .header("X-Total-Count", String.valueOf(expensePage.getTotalElements()))
            .body(expensePage.getContent());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> findById(@PathVariable Long id) {
        Role userRole = SecurityUtils.getCurrentUserRole();
        if (!menuCrudPermissionService.canRead(userRole, "/expense")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("조회 권한이 없습니다.");
        }
        return ResponseEntity.ok(expenseService.findById(id));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Expense expense) {
        Role userRole = SecurityUtils.getCurrentUserRole();
        if (!menuCrudPermissionService.canCreate(userRole, "/expense")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("생성 권한이 없습니다.");
        }
        return ResponseEntity.ok(expenseService.create(expense));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Expense expense) {
        Role userRole = SecurityUtils.getCurrentUserRole();
        if (!menuCrudPermissionService.canUpdate(userRole, "/expense")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("수정 권한이 없습니다.");
        }
        return ResponseEntity.ok(expenseService.update(id, expense));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        Role userRole = SecurityUtils.getCurrentUserRole();
        if (!menuCrudPermissionService.canDelete(userRole, "/expense")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("삭제 권한이 없습니다.");
        }
        expenseService.delete(id);
        return ResponseEntity.ok().body("삭제되었습니다.");
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