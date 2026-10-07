package com.kirarl.september.mapper;

import com.kirarl.september.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;

@Mapper
public interface UserMapper {

    @Select("SELECT id, username, password, email, gender, birthday FROM `user` WHERE username = #{username} LIMIT 1")
    User selectByUsername(@Param("username") String username);

    @Insert("INSERT INTO `user` (username, password) VALUES (#{username}, #{password})")
    int insert(User user);

    @Update("UPDATE `user` SET email = #{email} WHERE username = #{username}")
    int updateEmail(@Param("username") String username, @Param("email") String email);

    /**
     * 性别与生日允许为 null，null 表示前端显示“保密”。
     * MyBatis 对 null 参数需要显式指定 jdbcType，否则部分驱动会报错。
     */
    @Update("UPDATE `user` SET gender = #{gender,jdbcType=VARCHAR}, birthday = #{birthday,jdbcType=DATE} "
            + "WHERE username = #{username}")
    int updateProfile(@Param("username") String username,
                      @Param("gender") String gender,
                      @Param("birthday") LocalDate birthday);
}