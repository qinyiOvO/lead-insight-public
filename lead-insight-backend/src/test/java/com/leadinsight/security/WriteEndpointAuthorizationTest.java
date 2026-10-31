package com.leadinsight.security;

import com.leadinsight.common.result.PageResult;
import com.leadinsight.module.followup.service.FollowUpService;
import com.leadinsight.module.importtask.service.ImportTaskService;
import com.leadinsight.module.lead.service.LeadService;
import com.leadinsight.module.lead.vo.LeadDetailVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * C-5「主管写越权」回归防线（R1 走查行 8 的写侧扩展）。
 *
 * <p><b>缺陷背景：</b>本轮修复前，线索域 8 个 <b>写</b> 端点的 {@code @PreAuthorize} 为
 * {@code hasAnyRole('SPECIALIST','SUPERVISOR')}——主管被授予了「创建/编辑/删除/认领/改状态/
 * 重新抽取/发起导入/写跟进」等写权限。但其业务定位是<b>全量只读的监督者</b>（offline 的
 * LeadAccess 只读放行、看板写侧无入口），写越权会绕过「线索归属专员」的口径，属横向越权。
 * 修法：8 个写端点收紧为 {@code hasRole('SPECIALIST')}；读端点一律保持
 * {@code hasAnyRole('SPECIALIST','SUPERVISOR')} 不动。</p>
 *
 * <p><b>测试基建：</b>{@code @SpringBootTest} 加载真实上下文（含 {@code SecurityConfig} 的
 * {@code @EnableMethodSecurity}、{@code JwtAuthenticationFilter}、{@code GlobalExceptionHandler}、
 * {@code SecurityFilterChain}），{@code @AutoConfigureMockMvc} 让 MockMvc 套上真实安全过滤器链
 * ——这样 {@code @PreAuthorize} 的判否会真实抛出 {@code AuthorizationDeniedException} 并被
 * {@code GlobalExceptionHandler} 兜成 403/40300 的统一 R 形态，与线上路径一致。
 * 三个业务 Service 用 {@code @MockitoBean} 顶替，避免依赖数据库数据、让断言聚焦在鉴权层。</p>
 *
 * <p><b>覆盖矩阵：</b>①8 个写端点 × SUPERVISOR → 403/40300；②8 个写端点 × SPECIALIST → 非 403；
 * ③读端点 × SUPERVISOR → 200（证明收紧没有误伤读侧）。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class WriteEndpointAuthorizationTest {

    private static final String ROLE_SPECIALIST = "SPECIALIST";
    private static final String ROLE_SUPERVISOR = "SUPERVISOR";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LeadService leadService;

    @MockitoBean
    private ImportTaskService importTaskService;

    @MockitoBean
    private FollowUpService followUpService;

    // ======================= ① 写端点：主管必须 403 =======================

    @ParameterizedTest(name = "SUPERVISOR 写越权被拒 403/40300: {0}")
    @MethodSource("supervisorWriteRequests")
    @DisplayName("8 个写端点对主管一律 403/40300（C-5 主管写越权）")
    void supervisorWrite_isForbidden(String label, RequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    // ======================= ② 写端点：专员不得被误伤 =======================

    @ParameterizedTest(name = "SPECIALIST 写端点放行(非 403): {0}")
    @MethodSource("specialistWriteRequests")
    @DisplayName("8 个写端点对专员必须放行（非 403，证明收紧未误伤写侧合法角色）")
    void specialistWrite_isNotForbidden(String label, RequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.code").value(0));
    }

    // ======================= ③ 读端点：主管必须 200（未误伤读侧） =======================

    @Test
    @DisplayName("主管打读端点 GET /leads 必须 200（读侧全量只读，未误伤）")
    void supervisorCanReadLeadList() throws Exception {
        when(leadService.page(any(), anyLong(), anyString())).thenReturn(PageResult.of(List.of(), 0, 1, 20));
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/leads").with(asUser(201L, ROLE_SUPERVISOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("主管打读端点 GET /leads/{id} 必须 200（读侧全量只读，未误伤）")
    void supervisorCanReadLeadDetail() throws Exception {
        when(leadService.detail(anyLong(), anyLong(), anyString())).thenReturn(new LeadDetailVO());
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/leads/1").with(asUser(201L, ROLE_SUPERVISOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("主管打读端点 GET /leads/{id}/scores 必须 200")
    void supervisorCanReadLeadScores() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/leads/1/scores").with(asUser(201L, ROLE_SUPERVISOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("主管打读端点 GET /import-tasks 必须 200")
    void supervisorCanReadImportTaskList() throws Exception {
        when(importTaskService.page(any(), any(), anyLong(), anyString()))
                .thenReturn(PageResult.of(List.of(), 0, 1, 20));
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/import-tasks").with(asUser(201L, ROLE_SUPERVISOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("主管打读端点 GET /import-tasks/{id} 必须 200")
    void supervisorCanReadImportTaskDetail() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/import-tasks/1").with(asUser(201L, ROLE_SUPERVISOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("主管打读端点 GET /leads/{id}/follow-ups 必须 200")
    void supervisorCanReadFollowUps() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/leads/1/follow-ups").with(asUser(201L, ROLE_SUPERVISOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    // ======================= 请求构造 =======================

    private static Stream<Arguments> supervisorWriteRequests() {
        return writeRequests(asUser(201L, ROLE_SUPERVISOR));
    }

    private static Stream<Arguments> specialistWriteRequests() {
        return writeRequests(asUser(101L, ROLE_SPECIALIST));
    }

    /**
     * 8 个写端点。请求体一律给「能通过 DTO Bean Validation」的合法值——
     * 因为 Spring MVC 先做参数校验再进入方法安全拦截器，非法体会先落 400 而非 403，
     * 会掩盖我们要验证的鉴权结论。
     */
    private static Stream<Arguments> writeRequests(RequestPostProcessor auth) {
        return Stream.of(
                Arguments.of("POST /leads（创建）",
                        MockMvcRequestBuilders.post("/api/v1/leads").with(auth)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"originalText\":\"客户咨询采购一批设备，希望本月内报价\"}")),
                Arguments.of("PATCH /leads/{id}（编辑）",
                        MockMvcRequestBuilders.patch("/api/v1/leads/1").with(auth)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}")),
                Arguments.of("DELETE /leads/{id}（删除）",
                        MockMvcRequestBuilders.delete("/api/v1/leads/1").with(auth)),
                Arguments.of("POST /leads/{id}/claim（认领）",
                        MockMvcRequestBuilders.post("/api/v1/leads/1/claim").with(auth)),
                Arguments.of("POST /leads/{id}/status（改状态）",
                        MockMvcRequestBuilders.post("/api/v1/leads/1/status").with(auth)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"toStatus\":\"FOLLOWING\"}")),
                Arguments.of("POST /leads/{id}/extract（重新抽取）",
                        MockMvcRequestBuilders.post("/api/v1/leads/1/extract").with(auth)),
                Arguments.of("POST /import-tasks（发起批量导入）",
                        MockMvcRequestBuilders.post("/api/v1/import-tasks").with(auth)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"textList\":[\"邮件正文A（模拟）\"]}")),
                Arguments.of("POST /leads/{id}/follow-ups（写跟进）",
                        MockMvcRequestBuilders.post("/api/v1/leads/1/follow-ups").with(auth)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"followType\":\"CALL\",\"content\":\"已与客户电话沟通并记录需求\"}")));
    }

    /** 用真实 {@link LoginUser} 作为主体——保证 {@code @AuthenticationPrincipal LoginUser} 可解析、角色走 UserRole.authority()。 */
    private static RequestPostProcessor asUser(Long userId, String role) {
        LoginUser principal = new LoginUser(userId, role.toLowerCase() + "-" + userId, "",
                "集成测试用户", role, true);
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        return SecurityMockMvcRequestPostProcessors.authentication(authentication);
    }
}
