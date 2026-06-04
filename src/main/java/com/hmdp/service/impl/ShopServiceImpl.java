package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static org.apache.tomcat.jni.Global.unlock;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private CacheClient cacheClient;

    //根据id查询商铺信息
    @Override
    public Result queryById(Long id) {
        //解决缓存穿透
        //Shop shop = queryWithPassThrough(id);

        //解决缓存击穿-互斥锁
        //Shop shop = queryWithMutex(id);

        //解决缓存击穿-逻辑过期
        //Shop shop = queryWithLogicExpire(id);

        //封装工具类后调用
        Shop shop = cacheClient.
                queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if(shop==null){

            return Result.fail("店铺不存在！");
        }
        return Result.ok(shop);
    }

    //线程池
    private final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);




    /**
    //解决缓存击穿-逻辑过期
    //逻辑过期：提前将热key存入缓存，设置逻辑过期时间，当缓存中的数据过期了，返回空值，开启另一线程再从数据库中查询数据，更新缓存
    //热key场景，不需要判断缓存是否为空值
    public Shop queryWithLogicExpire(Long id){
        String key=CACHE_SHOP_KEY+id;
        //1.根据id从redis查询商铺信息
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断redis是否存在商铺信息
        if (StrUtil.isBlank(shopJson)) {
            //3.未命中缓存，返回空值
            return null;
        }
        //4.命中，需要把Json反序列化为对象
        RedisData redisData= JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5判断过期时间是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //5.1 过期时间未过期，返回商铺信息
            return shop;
        }
        //5.2 过期时间过期，需重建缓存
        //6.重建缓存
        //6.1尝试获取互斥锁
        String lockKey=LOCK_SHOP_KEY+id;
        boolean isLock = tryLock(lockKey);
        //6.2判断是否获取成功
        if (isLock) {
            //6.3成功，开启独立线程，从数据库中查询数据，更新缓存
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                        try {
                    //重建缓存
                    this.saveShop2Redis(id,20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unLock(lockKey);
                }
            });
        }
        //6.4无论是否成功，都返回旧的商铺信息
        return shop;
    }
     **/




    /**
    //解决缓存穿透(缓存空值)
    public Shop queryWithPassThrough(Long id){
        String key=CACHE_SHOP_KEY+id;
        //1.根据id从redis查询商铺信息
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断redis是否存在商铺信息
        if (StrUtil.isNotBlank(shopJson)) {//判断字符串既不为null，也不是空字符串(""),且也不是空白字符
            //3.存在，返回商铺信息
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //判断是否为空值
        if (shopJson!=null) {
            //缓存了空值，返回店铺不存在
            return null;
        }
        //4.缓存没有存任何东西，根据id查询数据库
        Shop shop = getById(id);
        //5.数据库中不存在，返回失败结果
        if (shop==null){
            //店铺不存在,把空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //6.数据库中存在，把商铺信息写入redis，并返回商铺信息
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }
     **/


    /**
    // 解决缓存击穿(互斥锁 + 自旋)
    public Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;

        // 1. 从 redis 查询商铺信息
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2. 存在有效数据，直接返回
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        // 3. 缓存了空值（已确认不存在），直接返回 null
        if (shopJson != null) {
            return null;
        }

        // 4. 缓存未命中，开始自旋获取锁 + 重建缓存
        int retryCount = 0;
        int maxRetries = 10;  // 最多自旋 10 次

        while (retryCount < maxRetries) {
            // 4.1 每次重试前先查一次缓存（可能已经被其他线程重建了）
            shopJson = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(shopJson)) {
                return JSONUtil.toBean(shopJson, Shop.class);
            }
            if (shopJson != null) {
                return null;
            }

            // 4.2 尝试获取锁
            String lockKey = LOCK_SHOP_KEY + id;
            boolean isLock = tryLock(lockKey);

            if (isLock) {
                try {
                    // 4.3 拿到锁后 double check（防止在获取锁瞬间缓存被重建）
                    shopJson = stringRedisTemplate.opsForValue().get(key);
                    if (StrUtil.isNotBlank(shopJson)) {
                        return JSONUtil.toBean(shopJson, Shop.class);
                    }
                    if (shopJson != null) {
                        return null;
                    }

                    // 4.4 查询数据库
                    Shop shop = getById(id);

                    // 5. 数据库中不存在
                    if (shop == null) {
                        // 缓存空值，防止缓存穿透
                        stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                        return null;
                    }

                    // 6. 数据库中存在，写入缓存
                    stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
                    return shop;

                } finally {
                    // 7. 释放锁
                    unlock(lockKey);
                }
            }

            // 8. 没拿到锁，自旋等待（增加重试次数，短暂休眠）
            retryCount++;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }

        // 9. 自旋超时后的降级策略：直接查数据库（避免无限等待）
        Shop shop = getById(id);
        if (shop == null) {
            // 缓存空值
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
        } else {
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        }
        return shop;
    }

     **/

    //获取互斥锁
    private boolean tryLock(String lockKey){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.MINUTES);
        //不能直接返回flag，因为flag是Boolean类型，方法要返回的是boolean类型，而BooleanUtil.isTrue()是boolean类型
        return BooleanUtil.isTrue(flag);
    }

    //释放互斥锁
    private void unLock(String lockKey){
        stringRedisTemplate.delete(lockKey);
    }


    //保存店铺数据到Redis
    public void saveShop2Redis(Long id,Long expireSeconds) throws InterruptedException {
        //1.查询店铺数据
        Shop shop = getById(id);
        Thread.sleep(200);
        //2.封装成逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3.写入Redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }




    //更新商铺信息
    @Transactional
    @Override
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id==null){
            return Result.fail("店铺不存在");
        }
        //1.更新数据库
        updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY+id);
        return Result.ok();
    }
}
