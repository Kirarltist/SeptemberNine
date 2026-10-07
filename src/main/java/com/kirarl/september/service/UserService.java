package com.kirarl.september.service;

import com.kirarl.september.entity.User;
import com.kirarl.september.mapper.UserMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Service
public class UserService {

    private final UserMapper userMapper;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public UserService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public boolean checkPassword(String username, String password) {
        User user = userMapper.selectByUsername(username);
        return user != null && user.getPassword() != null && password != null
                && passwordEncoder.matches(password, user.getPassword());
    }

    public boolean register(String username, String password) {
        if (userMapper.selectByUsername(username) != null) {
            return false;
        }

        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        return userMapper.insert(user) > 0;
    }

    public User findByUsername(String username) {
        return userMapper.selectByUsername(username);
    }

    public boolean hasEmail(String username) {
        User user = userMapper.selectByUsername(username);
        return user != null && user.getEmail() != null && !user.getEmail().isBlank();
    }

    public boolean bindEmail(String username, String email) {
        return userMapper.selectByUsername(username) != null
                && userMapper.updateEmail(username, email) > 0;
    }

    /**
     * 保存性别与生日，两者都允许为 null，null 表示“保密”。
     */
    public boolean updateProfile(String username, String gender, LocalDate birthday) {
        return userMapper.selectByUsername(username) != null
                && userMapper.updateProfile(username, gender, birthday) > 0;
    }
}