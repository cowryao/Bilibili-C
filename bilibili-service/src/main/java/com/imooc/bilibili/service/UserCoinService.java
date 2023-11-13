package com.imooc.bilibili.service;

import org.springframework.beans.factory.annotation.Autowired;
import com.imooc.bilibili.dao.UserCoinDao;
import org.springframework.stereotype.Service;
import java.util.Date;

@Service
public class UserCoinService {

    @Autowired
    private UserCoinDao userCoinDao;

    public Integer getUserCoinsAmount(Long userId) {
        return userCoinDao.getUserCoinsAmount(userId);
    }


    public void updateUserCoinsAmount(Long userId, Integer amount) {
        Date updateTime = new Date();

    }
}
