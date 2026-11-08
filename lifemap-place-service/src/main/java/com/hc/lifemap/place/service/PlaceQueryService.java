package com.hc.lifemap.place.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hc.lifemap.common.api.ErrorCode;
import com.hc.lifemap.place.dto.PageResult;
import com.hc.lifemap.place.dto.PlaceListItemVO;
import com.hc.lifemap.place.entity.Place;
import com.hc.lifemap.place.exception.BizException;
import com.hc.lifemap.place.mapper.PlaceMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * 地点读取侧：列表与（T08 起的）详情。只管"按什么条件查、查出来长什么样"，
 * 不做写入、不做统计聚合。
 *
 * <p>入参规整也放在这里而不是 controller：T12 的内部接口会直接调本服务，
 * 校验只写在 controller 时那条路径就成了缺口。</p>
 */
@Service
public class PlaceQueryService {

    /** 默认每页条数：一屏左栏大概能放 10 条，20 条足够覆盖首次滚动。 */
    public static final int DEFAULT_SIZE = 20;

    /** 每页上限：超过这个数响应体开始变大，且左栏一次也渲染不完，收益归零。 */
    public static final int MAX_SIZE = 100;

    private final PlaceMapper placeMapper;

    public PlaceQueryService(PlaceMapper placeMapper) {
        this.placeMapper = placeMapper;
    }

    /**
     * 时间轴分页：默认全部年份，最近去的排最前。
     *
     * @param page 页码，从 1 开始；小于 1 抛 10400（这是调用方写错了，不是服务出错）
     * @param size 每页条数，进来先规整到 1–100
     * @param year 年份筛选，null 表示不限
     */
    public PageResult<PlaceListItemVO> list(long page, long size, Integer year) {
        if (page < 1) {
            throw new BizException(ErrorCode.BAD_REQUEST, "page 必须从 1 开始");
        }

        // selectPage 的 LIMIT 由 T01 注册的 PaginationInnerInterceptor 拼出，缺了它会退化成全表查
        Page<Place> request = Page.of(page, normalizeSize(size));
        Page<Place> result = placeMapper.selectPage(request, timelineQuery(year));
        return PageResult.of(result, PlaceListItemVO::from);
    }

    /**
     * 条件三件事：软删过滤、年份区间、稳定排序。
     *
     * <p>年份走区间而不是 {@code EXTRACT(YEAR FROM visited_on)}：后者对列做函数运算，
     * {@code idx_place_visited_on} 用不上，数据一多就变全表扫描。</p>
     *
     * <p>排序补 {@code id DESC} 是因为同一天可能打卡多个地方，只按 {@code visited_on} 排时
     * PG 不保证同值行顺序稳定，翻页会出现"第 2 页又看到第 1 页的记录"。</p>
     */
    private LambdaQueryWrapper<Place> timelineQuery(Integer year) {
        LambdaQueryWrapper<Place> wrapper = Wrappers.lambdaQuery(Place.class)
                .isNull(Place::getDeletedAt)
                .orderByDesc(Place::getVisitedOn)
                .orderByDesc(Place::getId);

        if (year != null) {
            LocalDate start = LocalDate.of(year, 1, 1);
            wrapper.ge(Place::getVisitedOn, start).lt(Place::getVisitedOn, start.plusYears(1));
        }
        return wrapper;
    }

    /** {@code size≤0} 视为没传、取默认值；超过上限直接压到 100（不报错，少一屏比报错好）。 */
    private long normalizeSize(long size) {
        if (size < 1) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }
}
