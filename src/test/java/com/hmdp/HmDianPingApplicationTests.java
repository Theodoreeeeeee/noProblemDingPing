package com.hmdp;

import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Shop;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private CacheClient cacheClient;

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    private ExecutorService es = Executors.newFixedThreadPool(500);

    @Test
    void testIdWorker() throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(300);
        Runnable runnable = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("cacheId:");
//                System.out.println("id = " + id);
            }
            countDownLatch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(runnable);
        }
        countDownLatch.await();
        long end = System.currentTimeMillis();
        System.out.println("totalTime = " + (end - begin));
    }

    @Test
    void testSaveShop() throws InterruptedException {
        Shop shop = shopService.getById(1L);
        cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY + 1L, shop, 10L, TimeUnit.SECONDS);
    }

    @Test
    void testSql() {
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(10);
        System.out.println("seckillVoucher = " + seckillVoucher);
    }

    @Test
    void testSet() {
        Set<Integer> set = new TreeSet<>();
        for (int x : new int[]{3, 2, 5, 6, 8, 1, 0, 9, 7}) set.add(x);
        for (int x : set) System.out.println(x);
    }

    @Test
    void testLocalDateTime() {
        LocalDateTime now = LocalDateTime.of(2023, 6, 12, 0, 0, 0);
        long time = now.toEpochSecond(ZoneOffset.UTC);
        System.out.println("time: " + time);
        String DATE = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        System.out.println("Date: " + DATE);
    }
}
