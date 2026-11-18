package com.hyunchang.webapp.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.hyunchang.webapp.common.security.MenuAccessGuard;
import com.hyunchang.webapp.controller.TodoController;
import com.hyunchang.webapp.entity.Todo;
import com.hyunchang.webapp.entity.User;
import com.hyunchang.webapp.exception.GlobalExceptionHandler;
import com.hyunchang.webapp.repository.TodoRepository;
import com.hyunchang.webapp.repository.todo.TodoHistoryRepository;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TodoDeletionTest {
    @Test
    void httpContractRequiresExplicitIdsAndUsesExistingMenuPermission() throws Exception {
        TodoRepository repo = mock(TodoRepository.class);
        var service = new TodoService(repo, mock(TodoHistoryRepository.class));
        MenuAccessGuard guard = mock(MenuAccessGuard.class);
        when(guard.hasAccess("/todos")).thenReturn(true);
        var mvc =
                MockMvcBuilders.standaloneSetup(new TodoController(service, guard))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
        when(repo.findCompletedIds("needle", 3, "work")).thenReturn(List.of(7L, 9L));
        mvc.perform(
                        get("/api/todos/completed/preview")
                                .param("q", "needle")
                                .param("priority", "3")
                                .param("category", "work"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.ids[0]").value(7));
        mvc.perform(delete("/api/todos/completed")).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/todos/completed").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        verify(repo, never()).deleteCompletedByIds(any());
        when(repo.deleteCompletedByIds(List.of(7L, 9L))).thenReturn(2);
        mvc.perform(
                        delete("/api/todos/completed")
                                .contentType("application/json")
                                .content("{\"ids\":[7,9]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(2));
        when(guard.hasAccess("/todos")).thenReturn(false);
        when(guard.forbidden())
                .thenReturn(org.springframework.http.ResponseEntity.status(403).body("Forbidden"));
        mvc.perform(get("/api/todos/completed/preview")).andExpect(status().isForbidden());
    }

    @Test
    void filterPreviewSpansPagesAndDeletionCannotExpandOrDeleteReopenedItems() {
        var configuration =
                new Configuration()
                        .addAnnotatedClass(Todo.class)
                        .addAnnotatedClass(User.class)
                        .addResource("todo/orm.xml")
                        .setProperty("hibernate.connection.driver_class", "org.h2.Driver")
                        .setProperty(
                                "hibernate.connection.url", "jdbc:h2:mem:todo_" + UUID.randomUUID())
                        .setProperty("hibernate.hbm2ddl.auto", "create-drop");
        try (var factory = configuration.buildSessionFactory();
                var session = factory.openSession()) {
            var transaction = session.beginTransaction();
            TodoRepository repo =
                    new JpaRepositoryFactory(session).getRepository(TodoRepository.class);
            var service = new TodoService(repo, mock(TodoHistoryRepository.class));
            List<Todo> matching = new ArrayList<>();
            for (int i = 0; i < 12; i++)
                matching.add(repo.save(todo("Match " + i, true, 3, "work")));
            Todo otherCategory = repo.save(todo("Match other", true, 3, "home"));
            Todo otherPriority = repo.save(todo("Match low", true, 1, "work"));
            Todo otherSearch = repo.save(todo("Unrelated", true, 3, "work"));
            Todo active = repo.save(todo("Match active", false, 3, "work"));
            repo.flush();
            var preview = service.previewCompletedDeletion("  match  ", 3, "work");
            assertEquals(12, preview.count());
            assertEquals(matching.stream().map(Todo::getId).toList(), preview.ids());
            matching.get(0).setDone(false);
            Todo addedAfterPreview = repo.save(todo("Match new", true, 3, "work"));
            repo.flush();
            assertEquals(11, service.deleteCompleted(preview.ids()));
            session.clear();
            for (Todo preserved :
                    List.of(
                            matching.get(0),
                            otherCategory,
                            otherPriority,
                            otherSearch,
                            active,
                            addedAfterPreview)) {
                assertTrue(repo.existsById(preserved.getId()));
            }
            assertEquals(6, repo.count());
            assertEquals(0, service.deleteCompleted(preview.ids()));
            transaction.commit();
        }
    }

    @Test
    void missingOrInvalidSnapshotFailsClosedAndRepeatedIdsAreDeduplicated() {
        TodoRepository repo = mock(TodoRepository.class);
        var service = new TodoService(repo, mock(TodoHistoryRepository.class));
        assertThrows(IllegalArgumentException.class, () -> service.deleteCompleted(null));
        assertThrows(IllegalArgumentException.class, () -> service.deleteCompleted(List.of()));
        assertThrows(IllegalArgumentException.class, () -> service.deleteCompleted(List.of(-1L)));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.deleteCompleted(Arrays.asList(1L, null)));
        verifyNoInteractions(repo);
        service.deleteCompleted(List.of(1L, 1L, 2L));
        verify(repo).deleteCompletedByIds(List.of(1L, 2L));
    }

    private Todo todo(String title, boolean done, int priority, String category) {
        Todo todo = new Todo();
        todo.setTitle(title);
        todo.setDone(done);
        todo.setPriority(priority);
        todo.setCategory(category);
        return todo;
    }
}
