package com.sky.service.impl;

import com.sky.dto.GoodsSalesDTO;
import com.sky.entity.Orders;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.UserMapper;
import com.sky.service.ReportService;
import com.sky.vo.OrderReportVO;
import com.sky.vo.SalesTop10ReportVO;
import com.sky.vo.TurnoverReportVO;
import com.sky.vo.UserReportVO;
import io.swagger.models.auth.In;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ReportServiceImpl implements ReportService {

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private UserMapper userMapper;
    /**
     * 统计指定时间区间内的营业额数据
     * @param begin
     * @param end
     * @return
     */
    public TurnoverReportVO getTurnoverStatistics(LocalDate begin, LocalDate end) {

        //存放begin到end范围内每天的日期
        List<LocalDate> dateList = new ArrayList<>();
        dateList.add(begin);

        while (!begin.equals(end)){
            begin = begin.plusDays(1);
            dateList.add(begin);
        }

        List<Double> turnoverList = new ArrayList<>();
        for (LocalDate data : dateList) {
            //查询date日期对应的营业额数据（已完成的）
            LocalDateTime beginTime = LocalDateTime.of(data, LocalTime.from(LocalDateTime.MIN));//传换成当天0点0分0秒
            LocalDateTime endTime = LocalDateTime.of(data, LocalTime.from(LocalDateTime.MAX));//当天24点

            //select sum(amount) from orders where order_time > ? and order_time < ? and status= 5
            Map map = new HashMap<>();
            map.put("begin", beginTime);
            map.put("end", endTime);
            map.put("status", Orders.COMPLETED);
            Double turnover = orderMapper.sumByMap(map);
            turnover = turnover == null ? 0.0 : turnover; //如果查不到说明当日没订单，那应该把销售额改成0.0
            turnoverList.add(turnover);
        }

        return TurnoverReportVO
                .builder().
                dateList(StringUtils.join(dateList, ","))
                .turnoverList(StringUtils.join(turnoverList))
                .build();
    }

    /**
     * 统计指定时间区间内的用户量数据
     * @param begin
     * @param end
     * @return
     */
    @Override
    public UserReportVO getUserStatistics(LocalDate begin, LocalDate end) {
        //存放begin到end范围内每天的日期
        List<LocalDate> dateList = new ArrayList<>();

        dateList.add(begin);
        while (!begin.equals(end)){
            begin = begin.plusDays(1);
            dateList.add(begin);
        }

        //存放每天新增用户数量 select count(id) from user where creat_time > ? and creat_time < ?
        List<Integer> newUserList = new ArrayList<>();
        //存放每天总用户数量 select count(id) from user where creat_time < ?
        List<Integer> totalUserList = new ArrayList<>();

        for (LocalDate data : dateList) {
            LocalDateTime beginTime = LocalDateTime.of(data, LocalTime.from(LocalDateTime.MIN));//转换成当天0点0分0秒
            LocalDateTime endTime = LocalDateTime.of(data, LocalTime.from(LocalDateTime.MAX));//当天24点

            //select count(id) from user where creat_time < ?
            Map map = new HashMap<>();
            map.put("end", endTime);
            //总用户数量
            Integer totalUser = userMapper.countByMap(map);

            map.put("begin", beginTime);
            //新增用户数量
            Integer newUser = userMapper.countByMap(map);

            totalUserList.add(totalUser);
            newUserList.add(newUser);
        }

        return UserReportVO
                .builder()
                .dateList(StringUtils.join(dateList,","))
                .totalUserList(StringUtils.join(totalUserList,","))
                .newUserList(StringUtils.join(newUserList,","))
                .build();
    }

    /**
     * 统计指定时间区间内订单数据
     * @param begin
     * @param end
     * @return
     */
    @Override
    public OrderReportVO getOrderStatistics(LocalDate begin, LocalDate end) {
        //存放begin到end范围内每天的日期
        List<LocalDate> dateList = new ArrayList<>();

        dateList.add(begin);
        while (!begin.equals(end)){
            begin = begin.plusDays(1);
            dateList.add(begin);
        }

        List<Integer> orderCountList = new ArrayList<>();
        List<Integer> validOrderCountList = new ArrayList<>();

        for (LocalDate data : dateList) {
            LocalDateTime beginTime = LocalDateTime.of(data, LocalTime.from(LocalDateTime.MIN));//转换成当天0点0分0秒
            LocalDateTime endTime = LocalDateTime.of(data, LocalTime.from(LocalDateTime.MAX));//当天24点

            //查询每天的订单数
            //select count(id) from orders where order_time > ? and order_time < ?
            Integer orderCount = getOrderCount(beginTime, endTime, null);
            //查询每天有效订单数
            //select count(id) from orders where order_time > ? and order_time < ? and status = ?
            Integer validOrderCount = getOrderCount(beginTime, endTime, Orders.COMPLETED);

            orderCountList.add(orderCount);
            validOrderCountList.add(validOrderCount);
        }

        //计算时间区间内的订单总数量
        Integer totalOrderCount = orderCountList.stream().reduce(Integer::sum).get();
        //计算时间区间内的有效时订单数量
        Integer validOrderCount = validOrderCountList.stream().reduce(Integer::sum).get();

        //极端订单完成率
        Double orderCompletionRate = 0.0;
        if (totalOrderCount != 0){
            orderCompletionRate = validOrderCount.doubleValue() / totalOrderCount;
        }

        OrderReportVO orderReportVO = OrderReportVO.builder()
                .dateList(StringUtils.join(dateList, ","))
                .orderCountList(StringUtils.join(orderCountList, ","))
                .validOrderCountList(StringUtils.join(validOrderCountList, ","))
                .totalOrderCount(totalOrderCount)
                .validOrderCount(validOrderCount)
                .orderCompletionRate(orderCompletionRate)
                .build();

        return orderReportVO;
    }

    /**
     * 统计指定时间区间内销量排名
     * @param begin
     * @param end
     * @return
     */
    public SalesTop10ReportVO getSalesTop10(LocalDate begin, LocalDate end) {

        LocalDateTime beginTime = LocalDateTime.of(begin, LocalTime.from(LocalDateTime.MIN));//转换成当天0点0分0秒
        LocalDateTime endTime = LocalDateTime.of(end, LocalTime.from(LocalDateTime.MAX));//当天24点

        //select od.name, sum(od.number) number from order_detail od , orders o where od.order_id = o.id and o.status = 5 and o.order_time > '2023-10-01' and o.order_time < '2024-02-01'
        //group by od.name
        //order by number desc
        //limit 0,10
        //通过聚合函数查询top10菜品
        List<GoodsSalesDTO> salesTop10 = orderMapper.getSalesTop10(beginTime, endTime);

        List<String> names = salesTop10.stream().map(GoodsSalesDTO::getName).collect(Collectors.toList());
        String nameList = StringUtils.join(names, ",");

        List<Integer> numbers = salesTop10.stream().map(GoodsSalesDTO::getNumber).collect(Collectors.toList());
        String numberList = StringUtils.join(numbers, ",");

        //封装返回结果数据
        return SalesTop10ReportVO
                .builder()
                .nameList(nameList)
                .numberList(numberList)
                .build();
    }

    /**
     * 根据条件统计订单数量
     * @param begin
     * @param end
     * @param status
     * @return
     */
    private Integer getOrderCount(LocalDateTime begin, LocalDateTime end, Integer status){
        Map map = new HashMap<>();
        map.put("begin", begin);
        map.put("end", end);
        map.put("status", status);

        return orderMapper.countByMap(map);
    }
}
