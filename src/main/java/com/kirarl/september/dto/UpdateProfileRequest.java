package com.kirarl.september.dto;

/**
 * 保存性别与生日。两个字段都可以留空，留空表示“保密”。
 */
public class UpdateProfileRequest {

    private String username;
    private String password;
    private String gender;
    private String birthday;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getGender() {
        return gender;
    }

    public void setGender(String gender) {
        this.gender = gender;
    }

    public String getBirthday() {
        return birthday;
    }

    public void setBirthday(String birthday) {
        this.birthday = birthday;
    }
}