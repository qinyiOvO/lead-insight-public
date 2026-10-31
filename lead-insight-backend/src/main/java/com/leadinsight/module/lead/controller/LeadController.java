package com.leadinsight.module.lead.controller;

import com.leadinsight.common.result.PageResult;
import com.leadinsight.common.result.R;
import com.leadinsight.module.lead.dto.LeadCreateRequest;
import com.leadinsight.module.lead.dto.LeadQueryRequest;
import com.leadinsight.module.lead.dto.LeadUpdateRequest;
import com.leadinsight.module.lead.dto.StatusChangeRequest;
import com.leadinsight.module.lead.service.LeadService;
import com.leadinsight.module.lead.vo.LeadDetailVO;
import com.leadinsight.module.lead.vo.LeadVO;
import com.leadinsight.module.lead.vo.ScoreDetailVO;
import com.leadinsight.security.LoginUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 线索接口。Controller 只做参数校验、调用 Service、组装响应，绝不直连数据库。
 */
@RestController
@RequestMapping("/api/v1/leads")
@RequiredArgsConstructor
public class LeadController {

    private final LeadService leadService;

    @PostMapping
    @PreAuthorize("hasRole('SPECIALIST')")
    public ResponseEntity<R<LeadVO>> create(@Valid @RequestBody LeadCreateRequest request,
                                            @AuthenticationPrincipal LoginUser loginUser) {
        LeadVO vo = leadService.create(request, loginUser.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(R.ok(vo));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SPECIALIST','SUPERVISOR')")
    public R<PageResult<LeadVO>> page(LeadQueryRequest query,
                                      @AuthenticationPrincipal LoginUser loginUser) {
        return R.ok(leadService.page(query, loginUser.getUserId(), loginUser.getRole()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SPECIALIST','SUPERVISOR')")
    public R<LeadDetailVO> detail(@PathVariable("id") Long id,
                                  @AuthenticationPrincipal LoginUser loginUser) {
        return R.ok(leadService.detail(id, loginUser.getUserId(), loginUser.getRole()));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('SPECIALIST')")
    public R<LeadVO> update(@PathVariable("id") Long id,
                            @Valid @RequestBody LeadUpdateRequest request,
                            @AuthenticationPrincipal LoginUser loginUser) {
        return R.ok(leadService.update(id, request, loginUser.getUserId()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SPECIALIST')")
    public R<Void> delete(@PathVariable("id") Long id,
                          @AuthenticationPrincipal LoginUser loginUser) {
        leadService.delete(id, loginUser.getUserId());
        return R.ok();
    }

    @PostMapping("/{id}/claim")
    @PreAuthorize("hasRole('SPECIALIST')")
    public R<LeadVO> claim(@PathVariable("id") Long id,
                           @AuthenticationPrincipal LoginUser loginUser) {
        return R.ok(leadService.claim(id, loginUser.getUserId()));
    }

    @PostMapping("/{id}/status")
    @PreAuthorize("hasRole('SPECIALIST')")
    public R<LeadVO> changeStatus(@PathVariable("id") Long id,
                                  @Valid @RequestBody StatusChangeRequest request,
                                  @AuthenticationPrincipal LoginUser loginUser) {
        return R.ok(leadService.changeStatus(id, request, loginUser.getUserId()));
    }

    @PostMapping("/{id}/extract")
    @PreAuthorize("hasRole('SPECIALIST')")
    public R<LeadVO> reextract(@PathVariable("id") Long id,
                               @AuthenticationPrincipal LoginUser loginUser) {
        // openapi 1.2.2：重新触发抽取；模型不可用时仍返回 200，extractStatus=DEGRADED
        return R.ok(leadService.reextract(id, loginUser.getUserId()));
    }

    @GetMapping("/{id}/scores")
    @PreAuthorize("hasAnyRole('SPECIALIST','SUPERVISOR')")
    public R<List<ScoreDetailVO>> listScores(@PathVariable("id") Long id,
                                             @AuthenticationPrincipal LoginUser loginUser) {
        return R.ok(leadService.listScores(id, loginUser.getUserId(), loginUser.getRole()));
    }
}
