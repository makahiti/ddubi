package com.ssafy.ddubi.domain.user.repository;

import com.ssafy.ddubi.domain.user.entity.UserTransportPref;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserTransportPrefRepository extends JpaRepository<UserTransportPref, Long> {

    List<UserTransportPref> findAllByUserId(Long userId);

    /**
     * 취향 목록 전체 교체용. orphanRemoval 을 쓰지 않기로 했으므로(3.1장 규칙 12)
     * 서비스가 이걸로 비우고 다시 저장한다.
     */
    void deleteAllByUserId(Long userId);
}
