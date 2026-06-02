package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

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
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    // 发送手机验证码
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            //2.不符合，返回
            return Result.fail("手机号格式错误");
        }
        //3.符合，生成验证码
        String code = RandomUtil.randomNumbers(6);
        //4.将验证码保存到redis中,key是手机号码，value是验证码
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //5.发送验证码
        log.info("发送验证码成功：{}",code);
        return Result.ok();
    }

    // 登录功能
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.校验手机号
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            //不符合，返回
            return Result.fail("手机号格式错误");
        }

        //2.从redis中获取验证码，校验验证码
        String cachecode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);//缓存的验证码
        String code = loginForm.getCode();//输入的验证码
        if (cachecode == null||!cachecode.equals(code)) {
            //3.不一致，返回错误
            return Result.fail("验证码错误");
        }
        //4.一致，根据手机号查询用户
        User user = query().eq("phone", phone).one();

        //5.用户不存在，创建用户并保存用户到数据库
        if (user == null) {
            user = createUserWithPhone(phone);
        }

        //6.保存用户信息到redis中(无论是否存在，都保存到redis)
        //userDTO中只保存id和昵称等，不保存敏感信息，返回给前端的是userDTO对象
        //6.1随机生成token，保存到redis中，key是token,value是userDTO对象
        String token = UUID.randomUUID().toString(true);

        //6.2把user对象转成hashmap结构(StringRedisTemplate要求key和value都是字符串)
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)//如果某个属性值是 null，那么转换时跳过这个属性,避免 Redis 中存一堆无意义的 null 字段。
                        .setFieldValueEditor((fieldName,fieldValue)->fieldValue.toString()));//对每一个属性值都调用 .toString() 方法，强制转成字符串

        //6.3保存到redis中
        String tokenKey = RedisConstants.LOGIN_USER_KEY+token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
        //6.4设置过期时间
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL, TimeUnit.MINUTES);

        //7.返回token给客户端
        return Result.ok(token);
    }



    //注册
    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));
        save(user);
        return user;
    }




}
