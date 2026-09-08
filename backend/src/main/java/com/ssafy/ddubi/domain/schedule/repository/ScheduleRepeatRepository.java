package com.ssafy.ddubi.domain.schedule.repository;

import com.ssafy.ddubi.domain.schedule.entity.ScheduleRepeat;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScheduleRepeatRepository extends JpaRepository<ScheduleRepeat, Long> {

    Page<ScheduleRepeat> findAllByUserId(Long userId, Pageable pageable);

    Page<ScheduleRepeat> findAllByUserIdAndActiveTrue(Long userId, Pageable pageable);
}
