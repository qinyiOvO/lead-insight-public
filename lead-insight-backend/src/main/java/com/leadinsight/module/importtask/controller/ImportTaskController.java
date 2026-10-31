package com.leadinsight.module.importtask.controller;

import com.leadinsight.common.result.PageResult;
import com.leadinsight.common.result.R;
import com.leadinsight.module.importtask.dto.BatchImportRequest;
import com.leadinsight.module.importtask.service.ImportTaskService;
import com.leadinsight.module.importtask.vo.ImportTaskItemVO;
import com.leadinsight.module.importtask.vo.ImportTaskProgressVO;
import com.leadinsight.module.importtask.vo.ImportTaskVO;
import com.leadinsight.module.lead.service.LeadService;
import com.leadinsight.module.lead.vo.LeadVO;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 批量导入任务接口（openapi 1.3.1 六端点中的五个：创建/列表/详情/进度/明细；
 * 线索列表经 LeadService.pageByImportTask 提供）。Controller 只做参数校验、
 * 调用 Service、组装响应，绝不直连数据库。
 */
@RestController
@RequestMapping("/api/v1/import-tasks")
@RequiredArgsConstructor
public class ImportTaskController {

    private final ImportTaskService importTaskService;
    private final LeadService leadService;

    @PostMapping
    @PreAuthorize("hasRole('SPECIALIST')")
    public ResponseEntity<R<ImportTaskVO>> create(@Valid @RequestBody BatchImportRequest request,
                                                  @AuthenticationPrincipal LoginUser loginUser) {
        ImportTaskVO vo = importTaskService.create(request, loginUser.getUserId());
        // openapi 1.3.1：任务已接受（异步处理中）返回 202
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(R.ok(vo));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SPECIALIST','SUPERVISOR')")
    public R<PageResult<ImportTaskVO>> page(@RequestParam(required = false) Integer page,
                                            @RequestParam(required = false) Integer limit,
                                            @AuthenticationPrincipal LoginUser loginUser) {
        return R.ok(importTaskService.page(page, limit, loginUser.getUserId(), loginUser.getRole()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SPECIALIST','SUPERVISOR')")
    public R<ImportTaskVO> detail(@PathVariable("id") Long id,
                                  @AuthenticationPrincipal LoginUser loginUser) {
        return R.ok(importTaskService.detail(id, loginUser.getUserId(), loginUser.getRole()));
    }

    @GetMapping("/{id}/progress")
    @PreAuthorize("hasAnyRole('SPECIALIST','SUPERVISOR')")
    public R<ImportTaskProgressVO> progress(@PathVariable("id") Long id,
                                            @AuthenticationPrincipal LoginUser loginUser) {
        return R.ok(importTaskService.progress(id, loginUser.getUserId(), loginUser.getRole()));
    }

    @GetMapping("/{id}/leads")
    @PreAuthorize("hasAnyRole('SPECIALIST','SUPERVISOR')")
    public R<PageResult<LeadVO>> leads(@PathVariable("id") Long id,
                                       @RequestParam(required = false) Integer page,
                                       @RequestParam(required = false) Integer limit,
                                       @AuthenticationPrincipal LoginUser loginUser) {
        return R.ok(leadService.pageByImportTask(id, page, limit,
                loginUser.getUserId(), loginUser.getRole()));
    }

    @GetMapping("/{id}/items")
    @PreAuthorize("hasAnyRole('SPECIALIST','SUPERVISOR')")
    public R<List<ImportTaskItemVO>> items(@PathVariable("id") Long id,
                                           @AuthenticationPrincipal LoginUser loginUser) {
        // openapi：数组、非分页，覆盖全部条目，前端逐条结果表以此为准
        return R.ok(importTaskService.listItems(id, loginUser.getUserId(), loginUser.getRole()));
    }
}
