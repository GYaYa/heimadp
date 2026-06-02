package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

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

    //根据id查询商铺信息
    @Override
    public Result queryById(Long id) {
        String key=CACHE_SHOP_KEY+id;
        //1.根据id从redis查询商铺信息
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断redis是否存在商铺信息
        if (StrUtil.isNotBlank(shopJson)) {//判断字符串既不为null，也不是空字符串(""),且也不是空白字符
            //3.存在，返回商铺信息
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        //判断是否为空值
        if (shopJson!=null) {
            //缓存了空值，返回店铺不存在
            return Result.fail("店铺不存在");
        }
        //4.缓存没有存任何东西，根据id查询数据库
        Shop shop = getById(id);
        //5.数据库中不存在，返回失败结果
        if (shop==null){
            //店铺不存在,把空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return Result.fail("店铺不存在");
        }
        //6.数据库中存在，把商铺信息写入redis，并返回商铺信息
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return Result.ok(shop);
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
