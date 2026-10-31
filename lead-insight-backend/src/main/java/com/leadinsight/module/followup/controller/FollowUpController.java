package com.leadinsight.module.followup.controller;

import com.leadinsight.common.result.R;
import com.leadinsight.module.followup.dto.FollowUpCreateRequest;
import com.leadinsight.module.followup.service.FollowUpService;
import com.leadinsight.module.followup.vo.FollowUpVO;
import com.leadinsight.security.LoginUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 跟进记录接口。路径归属线索资源，故以 /api/v1/leads/{id}/follow-ups 暴露。
 */
@RestController
@RequestMapping("/api/v1/leads/{id}/follow-ups")
@RequiredArgsConstructor
public class FollowUpController {

    private final FollowUpService followUpService;

    @GetMapping
    @PreAuthorize("hasAnyRole('SPECIALIST','SUPERVISOR')")
    public R<List<FollowUpVO>> list(@PathVariable("id") Long leadId,
                                    @AuthenticationPrincipal LoginUser loginUser) {
        return R.ok(followUpService.listByLeadId(leadId, loginUser.getUserId(), loginUser.getRole()));
    }

    @PostMapping
    @PreAuthorize("hasRole('SPECIALIST')")
    public ResponseEntity<R<FollowUpVO>> create(@PathVariable("id") Long leadId,
                                                @Valid @RequestBody FollowUpCreateRequest request,
                                                @AuthenticationPrincipal LoginUser loginUser) {
        FollowUpVO vo = followUpService.create(leadId, request, loginUser.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(R.ok(vo));
    }
}
