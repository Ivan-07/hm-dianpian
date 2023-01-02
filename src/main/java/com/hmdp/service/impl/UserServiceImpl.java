package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author ivan
 * @since 2022-12-16
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1. 校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式不正确！");
        }
        // 2. 生成验证码
        String code = RandomUtil.randomNumbers(6);
        // 3. 保存验证码
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + phone, code, RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);

        // 4. 发送验证码
        log.debug("发送短信验证码成功， 验证码为：{}", code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1. 校验手机号
        String phone = loginForm.getPhone();
        if (phone == null || RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式不正确！");
        }
        // 2. 校验验证码
        String code = loginForm.getCode();
        String cachedCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
        if (cachedCode == null) {
            return Result.fail("验证码已失效");
        }
        if (code == null || !cachedCode.equals(code)) {
            return Result.fail("验证码不正确");
        }
        // 3. 从数据库中取出用户
        User user = query().eq("phone", phone).one();
        // 4. 用户不存在则创建用户
        if (user == null) {
            user = new User();
            user.setPhone(phone);
            String nickName = SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(5);
            user.setNickName(nickName);
            save(user);
        }
        // 5. 保存用户到redis中
        String token = UUID.randomUUID().toString();
        String key = RedisConstants.LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(key, BeanUtil.beanToMap(BeanUtil.copyProperties(user, UserDTO.class), new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(false).setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString())));
        stringRedisTemplate.expire(key, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        return Result.ok(token);
    }

    @Override
    public Result logout(HttpServletRequest request) {
        // 1. 从请求头中拿到token
        String token = request.getHeader("authorization");
        // 2. 从redis中删除数据
        String key = RedisConstants.LOGIN_USER_KEY + token;
        Boolean delete = stringRedisTemplate.delete(key);
        if (BooleanUtil.isFalse(delete)) {
            return Result.fail("登出失败");
        }
        UserHolder.removeUser();
        return Result.ok("登出成功");
    }

    @Override
    public Result sign() {
        // 1. 获取当前登陆用户
        Long userId = UserHolder.getUser().getId();
        // 2. 获取日期
        LocalDateTime now = LocalDateTime.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern("yyyyMM"));
        // 3. 拼接key
        String key = RedisConstants.USER_SIGN_KEY + userId + ":" + keySuffix;
        // 4. 写入redis
        int dayOfMonth = now.getDayOfMonth();
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        // 1. 获取当前登陆用户
        Long userId = UserHolder.getUser().getId();
        // 2. 获取日期
        LocalDateTime now = LocalDateTime.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern("yyyyMM"));
        // 3. 拼接key
        String key = RedisConstants.USER_SIGN_KEY + userId + ":" + keySuffix;
        // 4. 获得截止今天签到结果
        int dayOfMonth = now.getDayOfMonth();
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        if (result == null || result.isEmpty()) {
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num < 0) {
            return Result.ok(0);
        }
        // 计算连续签到天数
        int count = 0;
        while (true) {
            if ((num & 1) == 0) {
                break;
            }else {
                count++;
            }
            num >>>= 1;
        }
        return Result.ok(count);
    }
}
