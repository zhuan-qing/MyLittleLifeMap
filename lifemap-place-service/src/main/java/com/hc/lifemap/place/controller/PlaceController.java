package com.hc.lifemap.place.controller;

import com.hc.lifemap.common.api.ApiResponse;
import com.hc.lifemap.place.dto.PageResult;
import com.hc.lifemap.place.dto.PlaceCreateRequest;
import com.hc.lifemap.place.dto.PlaceListItemVO;
import com.hc.lifemap.place.dto.PlaceUpdateRequest;
import com.hc.lifemap.place.dto.PlaceVO;
import com.hc.lifemap.place.service.PlaceCommandService;
import com.hc.lifemap.place.service.PlaceQueryService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 地点 REST 入口。已开列表（T04）、详情与修改（T08）、新增（T05），删除由 T09 加上。
 *
 * <p>controller 只做两件事：把请求绑到方法参数、把结果套进统一响应体。
 * 参数范围校验（{@code page<1} → 10400、{@code size} 夹到 100）留在
 * {@link PlaceQueryService}——T12 的内部接口会绕过这里直接调服务，规则不能只写一份在前门。</p>
 *
 * <p>参数一律用 {@code defaultValue} 给默认值而不是 {@code required=true}：
 * 前端首屏就是不带参数的裸请求，缺省不该变成一次错误提示。</p>
 */
@RestController
@RequestMapping("/api/places")
public class PlaceController {

    private final PlaceQueryService placeQueryService;
    private final PlaceCommandService placeCommandService;

    public PlaceController(PlaceQueryService placeQueryService, PlaceCommandService placeCommandService) {
        this.placeQueryService = placeQueryService;
        this.placeCommandService = placeCommandService;
    }

    /**
     * 时间轴列表：分页 + 年份筛选。
     *
     * @param page 页码，从 1 开始，默认 1
     * @param size 每页条数，默认 20，上限 100
     * @param year 只看这一年；省略表示全部年份
     */
    @GetMapping
    public ApiResponse<PageResult<PlaceListItemVO>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) Integer year) {
        return ApiResponse.ok(placeQueryService.list(page, size, year));
    }

    /**
     * 单条详情。
     *
     * <p>路径变量用 {@code long} 而不是 {@code Long}：id 传成 {@code abc} 时会落到
     * {@code MethodArgumentTypeMismatchException} → 10400，而不是在自动拆箱时 NPE 成 500。</p>
     */
    @GetMapping("/{id}")
    public ApiResponse<PlaceVO> detail(@PathVariable long id) {
        return ApiResponse.ok(placeQueryService.detail(id));
    }

    /**
     * 新增一条到访记录。
     *
     * <p>{@code @Valid} 必须写在 {@code @RequestBody} 前面的参数上：它挂在参数级别由
     * {@code RequestResponseBodyMethodProcessor} 触发校验，写到类上或方法上都不生效，
     * 表现是"注解全在、错数据照样进库"。</p>
     *
     * <p>成功仍返回 HTTP 200 而不是 201：本服务所有接口靠 {@code code} 区分结果
     * （dev_doc §6.2），前端拦截器只认这一套；混进 201 会让"成功"多一个分支。</p>
     *
     * @param request 表单数据，字段校验见 {@link PlaceCreateRequest}
     * @return 落库后的完整对象，含数据库生成的 {@code id} 与 {@code createdAt}
     */
    @PostMapping
    public ApiResponse<PlaceVO> create(@Valid @RequestBody PlaceCreateRequest request) {
        return ApiResponse.ok(placeCommandService.create(request));
    }

    /**
     * 局部更新：只改请求里出现的字段，其余保持原值。
     *
     * <p>PUT 而不是 PATCH：字段级 null 语义由 {@link PlaceUpdateRequest} 承载，
     * 前端只需要一个"提交表单"的动词，不必区分"整体替换"与"打补丁"两套语义。</p>
     *
     * @param id     地点 id，不存在或已软删返回 10404
     * @param request 要改的字段，null 表示不改
     */
    @PutMapping("/{id}")
    public ApiResponse<PlaceVO> update(@PathVariable long id,
                                       @Valid @RequestBody PlaceUpdateRequest request) {
        return ApiResponse.ok(placeCommandService.update(id, request));
    }
}
