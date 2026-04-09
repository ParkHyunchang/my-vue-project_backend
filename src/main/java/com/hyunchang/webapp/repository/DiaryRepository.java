package com.hyunchang.webapp.repository;

import com.hyunchang.webapp.entity.Diary;
import com.hyunchang.webapp.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface DiaryRepository extends JpaRepository<Diary, Long> {

    List<Diary> findByUserOrderByDiaryDateDesc(User user);

    Optional<Diary> findByUserAndDiaryDate(User user, LocalDate diaryDate);

    boolean existsByUserAndDiaryDate(User user, LocalDate diaryDate);
}
