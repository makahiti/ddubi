package com.ssafy.ddubi.domain.route.repository;

import com.ssafy.ddubi.domain.route.entity.RouteRequest;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RouteRequestRepository extends JpaRepository<RouteRequest, Long> {
}
