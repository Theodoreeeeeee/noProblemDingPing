package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

public class tmpServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
        Shop shop = queryWithMutex(id);
        return Result.ok(shop);
    }


    private Shop queryWithMutex(Long id) {
        // 首先从redis中查询缓存
        String key = "cache:shop:" + id;
        String shopStr = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(shopStr)) {
            // shopStr 转回shop对象返回
            return JSONUtil.toBean(shopStr, Shop.class);
        }
        // 为空字符串的时候 shopStr.equals("") = true
        if (shopStr != null) {
            // 缓存的空数据
            return null;
        }
        // 彻底没有，查询数据库，重建缓存
        // 获取互斥锁
        String lockKey = "lock:shop:" + id;
        boolean isLock = tryLock(lockKey);
        Shop shop = null;
        try {
            if (!isLock) {
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            shop = getById(id);
            if (shop == null) {
                // 缓存空数据
                stringRedisTemplate.opsForValue().set(key, "", 2L, TimeUnit.MINUTES);
                return null;
            }
            // 缓存真实店铺数据
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), 30L, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            // 释放锁
            unLock(lockKey);
        }
        return shop;
    }

    private boolean tryLock(String key) {
        // 获取互斥锁 setnx key val ex 10s  重建缓存时先获取互斥锁  val 可以是任何值？
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1 ", 10L, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }


    @Override
    public Result update(Shop shop) {
        return null;
    }
}
