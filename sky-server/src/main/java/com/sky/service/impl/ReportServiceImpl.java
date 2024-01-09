package com.sky.service.impl;

import com.sky.dto.GoodsSalesDTO;
import com.sky.entity.Orders;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.UserMapper;
import com.sky.service.ReportService;
import com.sky.service.WorkspaceService;
import com.sky.vo.*;
import io.swagger.models.auth.In;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
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

    @Autowired
    private WorkspaceService workspaceService; //service层除了调mapper也可以调用其他service方法
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
     * 导出运营数据报表
     * @param response
     */
    @Override
    public void exportBusinessData(HttpServletResponse response) {
        //1. 插叙那数据库，获取营业数据
        LocalDate dateBegin = LocalDate.now().minusDays(30); //今天的30天前
        LocalDate dateEnd = LocalDate.now().minusDays(1);

        LocalDateTime beginTime = LocalDateTime.of(dateBegin, LocalTime.from(LocalDateTime.MIN));//转换成当天0点0分0秒
        LocalDateTime endTime = LocalDateTime.of(dateEnd, LocalTime.from(LocalDateTime.MAX));//当天24点

        //查询概览数据
        BusinessDataVO businessDataVO = workspaceService.getBusinessData(beginTime, endTime);

        //2. 通过POI将数据写入到excel文件中
        //输入流对象
        InputStream in = this.getClass().getClassLoader().getResourceAsStream("template/运营数据报表模板.xlsx");

        //基于模板文件创建一个新的Excel
        try {
            XSSFWorkbook excel = new XSSFWorkbook(in);

            //填充数据 -- 时间
            //获取sheet1
            XSSFSheet sheet1 = excel.getSheet("Sheet1");

            //获取第二行
            XSSFRow row = sheet1.getRow(1);

            //第二个单元格
            row.getCell(1).setCellValue("时间：" + dateBegin + "至" + dateEnd);

            //填充数据 -- 营业额、订单完成率、新增用户数、有效订单数、平均客单价
            sheet1.getRow(3).getCell(2).setCellValue(businessDataVO.getTurnover());

            sheet1.getRow(3).getCell(4).setCellValue(businessDataVO.getOrderCompletionRate());

            sheet1.getRow(3).getCell(6).setCellValue(businessDataVO.getNewUsers());

            sheet1.getRow(4).getCell(2).setCellValue(businessDataVO.getValidOrderCount());

            sheet1.getRow(4).getCell(4).setCellValue(businessDataVO.getUnitPrice());

            //填充明细数据， 30天
            for (int i = 0; i < 30; i++) {
                LocalDate date = dateBegin.plusDays(i);
                BusinessDataVO businessData = workspaceService.getBusinessData(LocalDateTime.of(date, LocalTime.from(LocalDateTime.MIN)), LocalDateTime.of(date, LocalTime.from(LocalDateTime.MAX)));

                XSSFRow row1 = sheet1.getRow(i + 7); //i = 0, 第1天就是第8行

                row1.getCell(1).setCellValue(date.toString());
                row1.getCell(2).setCellValue(businessData.getTurnover());
                row1.getCell(3).setCellValue(businessData.getValidOrderCount());
                row1.getCell(4).setCellValue(businessData.getOrderCompletionRate());
                row1.getCell(5).setCellValue(businessData.getUnitPrice());
                row1.getCell(6).setCellValue(businessData.getNewUsers());

            }

            //3. 通过输出流将excel文件下载到客户端浏览器
            //输出流对象
            ServletOutputStream out = response.getOutputStream();
            excel.write(out);

            //关闭资源
            out.close();
            excel.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

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
