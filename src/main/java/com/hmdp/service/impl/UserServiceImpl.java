package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

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

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1. 校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式不正确！");
        }
        // 2. 生成验证码
        String code = RandomUtil.randomNumbers(6);
        // 3. 保存手机号和验证码
        session.setAttribute("phone", phone);
        session.setAttribute("code", code);
        // 4. 发送验证码
        log.debug("发送短信验证码成功， 验证码为：{}", code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1. 校验手机号
        String phone = loginForm.getPhone();
        if (phone==null || RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式不正确！");
        }
        Object cachedPhone = session.getAttribute("phone");
        if (!cachedPhone.equals(phone)) {
            return Result.fail("手机号不一致");
        }
        // 2. 校验验证码
        String code = loginForm.getCode();
        String cachedCode = (String) session.getAttribute("code");
        if (code == null || !cachedCode.equals(code)) {
            return Result.fail("验证码不正确");
        }
        // 3. 从数据库中取出用户
        User user = query().eq("phone", phone).one();
        // 4. 用户不存在则创建用户
        if (user == null) {
            user = new User();
            user.setPhone(phone);
            String nickName = SystemConstants.USER_NICK_NAME_PREFIX+RandomUtil.randomString(5);
            user.setNickName(nickName);
            save(user);
        }
        // 5. 保存用户到session中
        UserDTO userDTO = new UserDTO();
        BeanUtils.copyProperties(user, userDTO);
        session.setAttribute("user", userDTO);
        return Result.ok();
    }
}
