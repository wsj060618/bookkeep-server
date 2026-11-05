package com.bookkeep.web.cli;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bookkeep.model.entity.User;
import com.bookkeep.service.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 单机运维命令：java -jar bookkeep.jar --reset-password=&lt;username&gt; &lt;newPassword&gt;
 * <p>能在这台机器上执行命令本身就是身份证明，所以不校验旧密码；但绝不能做成 HTTP 接口（PLAN §8 第 24 条）。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class ResetPasswordRunner implements ApplicationRunner {

    public static final String OPTION = "reset-password";
    // 与 RegisterReq 同一套规则，否则会写出一个注册/登录流程都认不出的账号或密码
    private static final Pattern USERNAME = Pattern.compile("^[a-zA-Z0-9_]{4,20}$");
    private static final int PASSWORD_MIN = 6;
    private static final int PASSWORD_MAX = 32;

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption(OPTION)) {
            return;
        }
        List<String> values = args.getOptionValues(OPTION);
        List<String> rest = args.getNonOptionArgs();
        String username = values == null || values.isEmpty() ? null : values.get(0);
        String password = rest.isEmpty() ? null : rest.get(0);

        String error = reset(username, password);
        if (error != null) {
            log.error("重置密码失败：{}", error);
            log.error("用法：java -jar bookkeep.jar --reset-password=<username> <newPassword>");
            System.exit(1);
        }
        log.info("用户 {} 的密码已重置（已签发的 token 在过期前仍然有效）", username);
        System.exit(0);
    }

    /**
     * 成功返回 null，失败返回原因；抽出来是为了单测不触发 System.exit。
     */
    public String reset(String username, String password) {
        if (username == null || !USERNAME.matcher(username).matches()) {
            return "用户名需 4-20 位字母、数字或下划线";
        }
        if (password == null || password.length() < PASSWORD_MIN || password.length() > PASSWORD_MAX) {
            return "密码长度需 6-32 位";
        }
        // 逻辑删除的账号会被 MyBatis-Plus 自动过滤，等价于「已注销的账号不能重置」
        User user = userMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getUsername, username));
        if (user == null) {
            return "用户不存在或已注销：" + username;
        }
        user.setPassword(passwordEncoder.encode(password));
        userMapper.updateById(user);
        return null;
    }
}