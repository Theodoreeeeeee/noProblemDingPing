package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable {
        String queueName = "stream.orders";

        @Override
        public void run() {
            while (true) {
                try {
                    // 1.获取消息队列中的订单信息 xreadgroup group g1 c1 count 1 block 2000 streams streams.order >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );

                    // 2.判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        // 2.1如果获取失败，说明没有消息，继续下一次循环
                        continue;
                    }
                    // 解析消息订单中的信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 2.2如果获取成功，可以下单
                    handleVoucherOrder(voucherOrder);
                    // 3 ACK确认 sack stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true) {
                try {
                    // 1.获取消息队列中的订单信息 xreadgroup group g1 c1 count 1 block 2000 streams streams.order >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );

                    // 2.判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        // 2.1如果获取失败，说明pending-list没有异常消息，结束循环
                        break;
                    }
                    // 解析消息订单中的信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 2.2如果获取成功，可以下单
                    handleVoucherOrder(voucherOrder);
                    // 3 ACK确认 sack stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理pending-list订单异常", e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        }
    }



//    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
//    private class VoucherOrderHandler implements Runnable {
//
//        @Override
//        public void run() {
//            while (true) {
//                try {
//                    // 获取队列中的订单信息
//                    VoucherOrder voucherOrder = orderTasks.take();
//                    // 创建订单
//                    handleVoucherOrder(voucherOrder);
//                } catch (Exception e) {
//                    log.error("处理订单异常: " + e);
//                }
//            }
//        }
//    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 分布式锁 ↓
        // 获取用户id，此时只能从voucherOrder中getId(), 现在是一个新线程，不能用UserHolder(ThreadLocal)
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            // 获取锁失败，返回错误或重试
            log.error("不允许重复下单");
            return;
        }
        // 获取代理对象（事务）
        try {
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            // 释放锁
            lock.unlock();
        }
    }

    private IVoucherOrderService proxy;

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 获取用户
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");


        // 1.执行lua脚本
        Long res = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(), String.valueOf(orderId));
        // 2.判断结果是否为0
        if (res == null) {
            return Result.fail("出错啦！");
        }
        int r = res.intValue();
        if (r != 0) {
            // 2.1不为0，没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
//        // 2.2为0，有购买资格，把下单信息保存到阻塞队列中
//        VoucherOrder voucherOrder = new VoucherOrder();
//        //  保存阻塞队列 done
//        voucherOrder.setId(orderId);  // 订单id
//        voucherOrder.setUserId(userId); // 用户id
//        voucherOrder.setVoucherId(voucherId); // 代金券id
//        // 创建阻塞队列
//        orderTasks.add(voucherOrder);
        // 获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 3.返回订单id
        return Result.ok(orderId);
    }


//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        // 获取用户
//        Long userId = UserHolder.getUser().getId();
//        // 1.执行lua脚本
//        Long res = stringRedisTemplate
//                .execute(SECKILL_SCRIPT, Collections.emptyList(), voucherId.toString(), userId.toString());
//        // 2.判断结果是否为0
//        int r = res.intValue();
//        if (r != 0) {
//            // 2.1不为0，没有购买资格
//            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
//        }
//        // 2.2为0，有购买资格，把下单信息保存到阻塞队列中
//        VoucherOrder voucherOrder = new VoucherOrder();
//        long orderId = redisIdWorker.nextId("order");
//        //  保存阻塞队列 done
//        voucherOrder.setId(orderId);  // 订单id
//        voucherOrder.setUserId(userId); // 用户id
//        voucherOrder.setVoucherId(voucherId); // 代金券id
//        // 创建阻塞队列
//        orderTasks.add(voucherOrder);
//        // 获取代理对象
//        proxy = (IVoucherOrderService) AopContext.currentProxy();
//        // 3.返回订单id
//        return Result.ok(orderId);
//    }


//    @Override
//    public Result seckillVoucher(Long voucherId) throws InterruptedException {
//        // 1.查询优惠券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        // 2.判断秒杀是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀尚未开始");
//        }
//        // 3.判断秒杀是否结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("秒杀已经结束");
//        }
//        // 4.判断库存是否充足
//        if (voucher.getStock() < 1) {
//            return Result.fail("库存不足");
//        }
//
//        // 6.一人一单
//        Long userId = UserHolder.getUser().getId();
//        // 当用户的值一样时，锁就一样
//        // 加在方法调用处，是因为这个方法被spring管理事务，原样子加锁，先释放锁，在提交事务，这之间会有其他线程，不安全，所以加载调用出，先提交完事务才释放锁
//        // 以下代码在单体系统ok， 分布式系统下每个synchronized不同的jvm锁，需要分布式锁
////        synchronized (userId.toString().intern()) {
////            // 获取代理对象（事务）
////            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
////            return proxy.createVoucherOrder(voucherId);
////        }
//
//        // 分布式锁 ↓
////        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        boolean isLock = lock.tryLock();
////        boolean isLock = lock.tryLock(1L, TimeUnit.SECONDS);
//        if (!isLock) {
//            // 获取锁失败，返回错误或重试
//            return Result.fail("一个人只能下一单");
//        }
//        // 获取代理对象（事务）
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            // 释放锁
//            lock.unlock();
//        }
//    }


    // 由于判断一人一单不是修改操作，只能加悲观锁 synchronized
    // 在方法上加锁，就是给this加锁，this = VoucherOrderServiceImpl
    // 降低锁的粒度，不同用户间无需加锁
    @Transactional
//    public synchronized Result createVoucherOrder(Long voucherId) {
    public void createVoucherOrder(VoucherOrder voucherOrder) {
//        // 6.一人一单
        Long userId = voucherOrder.getUserId();
//        // 当用户的值一样时，锁就一样
//        synchronized (userId.toString().intern()) {
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        // 判断是否存在
        if (count > 0) {
            log.error("用户已经购买过一次!");
            return;
        }
        // 5.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
//                .eq("voucher_id", voucherId).eq("stock", voucher.getStock()) // CAS乐观锁 where id = ? and stock = ? -> stock > 0
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0) // CAS乐观锁 where id = ? and stock = ? -> stock > 0
                .update();
        if (!success) {
            // 扣减失败
            log.error("库存不足");
            return;
        }
        // 6.创建订单
        save(voucherOrder);
    }
}
