package com.world_dance.ms_music_media.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import com.world_dance.wd_lib_common.dto.EnrollmentResponseDto;
import com.world_dance.wd_lib_common.dto.UserEventRoleResponseDto;

@FeignClient(name = "ms-enrollment", path = "/api/v1/enrollments")
public interface EnrollmentFeignClient {

    @GetMapping("/{id}")
    EnrollmentResponseDto getEnrollmentById(@PathVariable("id") Long id);

    @GetMapping("/events/{eventId}/users/{userId}/role")
    UserEventRoleResponseDto getUserEventRole(@PathVariable("eventId") Long eventId, @PathVariable("userId") Long userId);
}