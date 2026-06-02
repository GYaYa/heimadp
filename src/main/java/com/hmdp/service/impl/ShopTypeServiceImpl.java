package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

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
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    //hash结构
    @Override
    public Result queryTypeList() {
        //1.从redis查询商铺信息
        List<String> shopTypeJson = stringRedisTemplate.opsForList().range(RedisConstants.SHOP_TYPE_KEY, 0, -1);
        //2.存在则返回商铺信息
        if (shopTypeJson != null && !shopTypeJson.isEmpty()){
            List<ShopType> shopTypes = shopTypeJson.stream()
                    //将每个 JSON 字符串转换为 ShopType 对象
                    .map(shopType -> JSONUtil.toBean(shopType, ShopType.class))
                    //将处理结果收集成新的 List
                    .collect(Collectors.toList());
            return Result.ok(shopTypes);
        }
        //3.不存在，则查询数据库
        //MyBatis-Plus 的链式查询，用于从数据库查询所有商铺类型并按 sort 字段升序排序。
        List<ShopType> sort = query().orderByAsc("sort").list();
        //4.不存在，返回
        if (sort == null || sort.isEmpty()) {
            return Result.ok("暂无商铺类型");
        }
        //5.存在，把商铺信息写入redis，并返回商铺信息
        for (ShopType shopType:sort){
            stringRedisTemplate.opsForList()
                    //批量向列表的右侧（末尾）添加元素
                    .rightPushAll(CACHE_SHOP_KEY,JSONUtil.toJsonStr(shopType));
        }
        return Result.ok(sort);
    }

    /*
    //String结构
    @Override
    public Result queryTypeList() {
        //1.从redis查询商铺信息
        String shopTypeList = stringRedisTemplate.opsForValue().get(RedisConstants.SHOP_TYPE_KEY);
        //2.存在则返回商铺信息
        if(shopTypeList!=null){
            return Result.ok(JSONUtil.toList(shopTypeList,ShopType.class));
        }
        //3.不存在，则查询数据库
        //MyBatis-Plus 的链式查询，用于从数据库查询所有商铺类型并按 sort 字段升序排序。
        List<ShopType> sort = query().orderByAsc("sort").list();
        //4.不存在，返回
        if (sort == null || sort.isEmpty()) {
            return Result.ok("暂无商铺类型");
        }
        //5.存在，把商铺信息写入redis，并返回商铺信息
        stringRedisTemplate.opsForValue().set(RedisConstants.SHOP_TYPE_KEY,JSONUtil.toJsonStr(sort));
        return Result.ok(sort);
    }
    */
}
