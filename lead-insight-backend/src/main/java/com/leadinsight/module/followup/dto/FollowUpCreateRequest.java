package com.leadinsight.module.followup.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增跟进记录入参（openapi FollowUpCreateRequest）。
 */
@Data
public class FollowUpCreateRequest {

    @Pattern(regexp = "NOTE|CALL|EMAIL|VISIT|OTHER",
            message = "跟进方式取值必须为 NOTE/CALL/EMAIL/VISIT/OTHER 之一")
    private String followType = "NOTE";

    @NotBlank(message = "跟进内容不能为空")
    @Size(max = 1000, message = "跟进内容长度不能超过 1000 个字符")
    private String content;
}
