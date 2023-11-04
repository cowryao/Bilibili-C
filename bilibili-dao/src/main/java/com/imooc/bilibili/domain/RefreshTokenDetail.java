package com.imooc.bilibili.domain;

import javax.xml.crypto.Data;

public class RefreshTokenDetail {
    private Long id;
    private String refreshToken;

    private Data CreateTime;
    private Long userId;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }

    public Data getCreateTime() {
        return CreateTime;
    }

    public void setCreateTime(Data createTime) {
        CreateTime = createTime;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }
}
