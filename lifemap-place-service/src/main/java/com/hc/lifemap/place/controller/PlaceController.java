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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 地点 REST 入口：列表、详情、新增、修改、软删（P1 的 CRUD 到此收齐）。
 *
 * <p>controller 只做两件事：把请求绑到方法参数、把结果套进统一响应体。参数范围校验
 * （{@code page<1} → 10400、{@code size} 夹到 100）在 {@link PlaceQueryService}——
 * T12 的内部接口会绕过这里直接调服务，规则不能只写一份在前门。</p>
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

    /** 单条详情：点开列表里的一行时取全部字段（含两套坐标与时间戳）。 */
    @GetMapping("/{id}")
    public ApiResponse<PlaceVO> detail(@PathVariable long id) {
        return ApiResponse.ok(placeQueryService.detail(id));
    }

    /**
     * 新增一条到访记录。
     *
     * <p>{@code @Valid} 必须挂在 {@code @RequestBody} 参数上：由
     * {@code RequestResponseBodyMethodProcessor} 触发校验，写到类上或方法上都不生效，
     * 表现是"注解全在、错数据照样进库"。</p>
     *
     * <p>成功返回 HTTP 200 而不是 201：本服务所有接口靠 {@code code} 区分结果（dev_doc §6.2），
     * 混进 201 会让前端多一个"成功"分支。</p>
     */
    @PostMapping
    public ApiResponse<PlaceVO> create(@Valid @RequestBody PlaceCreateRequest request) {
        return ApiResponse.ok(placeCommandService.create(request));
    }

    /** 局部更新：只改请求里出现的字段，其余保持原值。 */
    @PutMapping("/{id}")
    public ApiResponse<PlaceVO> update(@PathVariable long id, @Valid @RequestBody PlaceUpdateRequest request) {
        return ApiResponse.ok(placeCommandService.update(id, request));
    }

    /**
     * 软删：置 {@code deleted_at} 并写一条 {@code PLACE_DELETED} 事件，照片由 media 侧对账清理。
     *
     * <p>幂等，成功时 {@code data} 为 null：重复删、删不存在的 id 都是 code=0，
     * 前端不必区分"刚删掉"和"早就删了"。</p>
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        placeCommandService.delete(id);
        return ApiResponse.ok();
    }
}
